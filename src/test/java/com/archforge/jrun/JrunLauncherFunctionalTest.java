package com.archforge.jrun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动器端到端：真实执行生成的 ./jrun（bash）。
 *
 * <p>TestKit 的临时项目没有 gradlew。每个用例先用 TestKit 跑一次 jrunInit 准备缓存，
 * 再放一个只记录调用参数的假 gradlew，用来断言启动器有没有调用 Gradle、怎样调用。
 */
class JrunLauncherFunctionalTest extends AbstractJrunTest {

    @BeforeEach
    void requireBash() {
        Assumptions.assumeTrue(findBash() != null, "bash not available on this machine");
    }

    /** main/Hello.java 打印 HELLO、每个参数和两个系统属性；jrunBlock 追加到 build.gradle。 */
    private void helloProject(String jrunBlock) {
        settings("launcher");
        buildScript("plugins { id 'com.archforge.jrun' }\n" + jrunBlock);
        write("main/Hello.java", """
                public class Hello {
                    public static void main(String[] args) {
                        System.out.println("HELLO");
                        for (String a : args) {
                            System.out.println("ARG=[" + a + "]");
                        }
                        System.out.println("PROP_A=" + System.getProperty("jrun.a"));
                        System.out.println("PROP_B=" + System.getProperty("jrun.b"));
                    }
                }
                """);
        runner("jrunInit").build();
    }

    /** 假 gradlew：把参数追加到 gradle-calls.log，再执行 body。 */
    private void fakeGradlew(String body) {
        write("gradlew", "#!/bin/sh\necho \"$@\" >> \"$(dirname \"$0\")/gradle-calls.log\"\n" + body);
    }

    private String gradleCalls() {
        return exists("gradle-calls.log") ? read("gradle-calls.log") : "";
    }

    @Test
    void unchangedProjectRunsWithoutStartingGradle() {
        helloProject("");
        fakeGradlew("exit 1\n");

        ProcessResult r = jrun("Hello");
        assertEquals(0, r.exitCode(), r.output());
        assertTrue(r.output().contains("HELLO"), r.output());
        assertEquals("", gradleCalls(), "a cache hit must not start Gradle");
    }

    @Test
    void editingTheBuildScriptRefreshesTheCache() {
        helloProject("");
        fakeGradlew("exit 0\n");
        write("build.gradle", read("build.gradle") + "// touched\n");

        assertEquals(0, jrun("Hello").exitCode());
        assertTrue(gradleCalls().contains("jrunInit"), "a build script edit must refresh the cache");
    }

    /** find -newer 的盲区：删除文件不会让任何文件变"新"。 */
    @Test
    void deletingASourceFileRefreshesTheCache() throws IOException {
        helloProject("");
        write("main/Extra.java", "class Extra {}\n");
        runner("jrunInit").build();
        fakeGradlew("exit 0\n");
        Files.delete(projectDir.resolve("main/Extra.java"));

        assertEquals(0, jrun("Hello").exitCode());
        assertTrue(gradleCalls().contains("jrunInit"), "a deleted source must refresh the cache");
    }

    /** find -newer 的盲区：解压、cp -p 之类会把 mtime 回退到更早。 */
    @Test
    void aContentChangeWithAnOlderMtimeRefreshesTheCache() throws IOException {
        helloProject("");
        fakeGradlew("exit 0\n");
        Path hello = projectDir.resolve("main/Hello.java");
        FileTime before = Files.getLastModifiedTime(hello);
        Files.writeString(hello, Files.readString(hello) + "// changed\n");
        Files.setLastModifiedTime(hello, FileTime.fromMillis(before.toMillis() - 3_600_000));

        assertEquals(0, jrun("Hello").exitCode());
        assertTrue(gradleCalls().contains("jrunInit"), "content changes must win over mtimes");
    }

    /** 旧实现只看 build/settings/gradle.properties，版本目录变化会被漏掉。 */
    @Test
    void aVersionCatalogChangeRefreshesTheCache() {
        write("gradle/libs.versions.toml", "[versions]\ngson = \"2.10\"\n");
        helloProject("");
        fakeGradlew("exit 0\n");
        write("gradle/libs.versions.toml", "[versions]\ngson = \"2.11\"\n");

        assertEquals(0, jrun("Hello").exitCode());
        assertTrue(gradleCalls().contains("jrunInit"), "version catalog changes must refresh the cache");
    }

    @Test
    void rebuildFlagForcesARefresh() {
        helloProject("");
        fakeGradlew("exit 0\n");

        assertEquals(0, jrun("--rebuild", "Hello").exitCode());
        assertTrue(gradleCalls().contains("jrunInit"), "--rebuild must refresh even on a cache hit");
    }
}
