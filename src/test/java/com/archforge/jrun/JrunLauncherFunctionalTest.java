package com.archforge.jrun;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    @Test
    void jvmOptionsComeFromJavaOptsThenJrunOpts() {
        helloProject("");

        ProcessResult r = runBash(projectDir.resolve("jrun"), projectDir,
                Map.of("JAVA_OPTS", "-Djrun.a=1 -Djrun.b=java", "JRUN_OPTS", "-Djrun.b=jrun"), "Hello");
        assertTrue(r.output().contains("PROP_A=1"), "JAVA_OPTS must reach the JVM:\n" + r.output());
        assertTrue(r.output().contains("PROP_B=jrun"), "JRUN_OPTS comes last and wins:\n" + r.output());
    }

    /** #!/usr/bin/env -S ./jrun：javac 不认 #!，启动器要换成 JDK 会跳过首行的无扩展名副本。 */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void javaFilesWithAShebangRunDirectly() throws IOException {
        helloProject("");
        Path script = write("hello.java", """
                #!/usr/bin/env -S ./jrun
                public class Shebang {
                    public static void main(String[] args) {
                        System.out.println("SHEBANG " + String.join(",", args));
                    }
                }
                """);
        assertTrue(script.toFile().setExecutable(true));

        ProcessBuilder pb = new ProcessBuilder("./hello.java", "a", "b").directory(projectDir.toFile())
                .redirectErrorStream(true);
        pb.environment().put("JAVA_HOME", System.getProperty("java.home"));
        Process process = pb.start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(out.contains("SHEBANG a,b"), "the shebang script should run through ./jrun:\n" + out);
    }

    @Test
    void relativeSourcePathsResolveFromTheCallingDirectory() {
        helloProject("");
        write("tools/Tool.java", """
                public class Tool {
                    public static void main(String[] args) {
                        System.out.println("TOOL");
                    }
                }
                """);

        ProcessResult r = runBash(projectDir.resolve("jrun"), projectDir.resolve("tools"), Map.of(), "Tool.java");
        assertEquals(0, r.exitCode(), r.output());
        assertTrue(r.output().contains("TOOL"), r.output());
    }

    @Test
    void namedScriptsPassArrayArgumentsIntact() {
        helloProject("""
                jrun {
                    script('greet', 'Hello') { args '--greeting=hello world', 'say "hi"' }
                }
                """);

        ProcessResult r = jrun("greet", "extra arg");
        assertEquals(0, r.exitCode(), r.output());
        assertTrue(r.output().contains("ARG=[--greeting=hello world]\nARG=[say \"hi\"]\nARG=[extra arg]"),
                "fixed and user arguments must arrive unsplit and in order:\n" + r.output());
    }

    @Test
    void shellScriptsRunInTheProjectRootWithPositionalArguments() throws IOException {
        helloProject("""
                jrun {
                    script('where', 'shell:echo "PWD=$(pwd)"; echo "ARG1=$1"')
                }
                """);

        ProcessResult r = runBash(projectDir.resolve("jrun"), projectDir.resolve("main"), Map.of(), "where", "foo");
        assertTrue(r.output().contains("PWD=" + projectDir.toRealPath()), r.output());
        assertTrue(r.output().contains("ARG1=foo"), r.output());
    }

    @Test
    void dependsOnRunsInOrderAndStopsAtTheFirstFailure() {
        helloProject("""
                jrun {
                    script('first', 'shell:echo FIRST')
                    script('second', 'shell:echo SECOND')
                    script('all', 'Hello') { dependsOn 'first', 'second' }
                    script('broken', 'shell:exit 3')
                    script('guarded', 'shell:echo NEVER') { dependsOn 'broken' }
                }
                """);

        String out = jrun("all").output();
        int first = out.indexOf("FIRST");
        assertTrue(first >= 0 && first < out.indexOf("SECOND") && out.indexOf("SECOND") < out.indexOf("HELLO"),
                "dependencies run in declaration order before the script:\n" + out);

        ProcessResult guarded = jrun("guarded");
        assertNotEquals(0, guarded.exitCode(), guarded.output());
        assertFalse(guarded.output().contains("NEVER"), "a failed dependency must stop the chain:\n" + guarded.output());
        assertTrue(guarded.output().contains("broken"), "the failing dependency should be named:\n" + guarded.output());
    }

    @Test
    void addHandsTheCoordinatesToGradleAndKeepsTheEdit() {
        helloProject("");
        fakeGradlew("case \"$*\" in *jrunAdd*) echo '// edited' >> \"$(dirname \"$0\")/build.gradle\" ;; esac\nexit 0\n");

        ProcessResult r = jrun("add", "--test", "com.google.code.gson:gson:2.11");
        assertEquals(0, r.exitCode(), r.output());
        assertTrue(gradleCalls().contains(
                "jrunAdd --configuration=testImplementation --dependency=com.google.code.gson:gson:2.11"), gradleCalls());
        assertTrue(gradleCalls().contains("jrunResolve --configuration=testImplementation jrunInit"), gradleCalls());
        assertTrue(read("build.gradle").contains("// edited"), "a successful add keeps the edit");
    }

    @Test
    void addRestoresTheBuildScriptWhenResolutionFails() {
        helloProject("");
        String original = read("build.gradle");
        fakeGradlew("case \"$*\" in *jrunAdd*) echo '// edited' >> \"$(dirname \"$0\")/build.gradle\"; exit 0 ;; esac\nexit 1\n");

        ProcessResult r = jrun("add", "com.example:nope:1.0");
        assertNotEquals(0, r.exitCode(), r.output());
        assertEquals(original, read("build.gradle"), "a failed add must leave the build script as it was");
        assertTrue(r.output().contains("已恢复"), r.output());
    }

    /** 模拟 ~/.local/bin/jrun：同一份模板，放在没有构建文件的目录里。 */
    private Path globalJrun() throws IOException {
        Path jrun = projectDir.resolve("home/.local/bin/jrun");
        Files.createDirectories(jrun.getParent());
        Files.writeString(jrun, LauncherGenerator.render("jrun.sh"));
        return jrun;
    }

    @Test
    void theLauncherReportsItsVersionGloballyAndInsideProjects() throws IOException {
        assertEquals("jrun " + JrunVersion.get(),
                runBash(globalJrun(), projectDir, Map.of(), "--version").output().trim());
        helloProject("");
        assertEquals("jrun " + JrunVersion.get(), jrun("--version").output().trim());
    }

    @Test
    void globalInitScaffoldsAProjectThatBuildsAndRuns() throws IOException {
        Path jrun = globalJrun();
        Path wrapper = projectDir.resolve("home/.local/share/jrun/wrapper");
        Files.createDirectories(wrapper.resolve("gradle/wrapper"));
        for (String file : new String[] {"gradlew", "gradlew.bat"}) {
            Files.copy(Path.of(file), wrapper.resolve(file));
        }
        for (String file : new String[] {"gradle-wrapper.jar", "gradle-wrapper.properties"}) {
            Files.copy(Path.of("gradle/wrapper", file), wrapper.resolve("gradle/wrapper/" + file));
        }

        ProcessResult r = runBash(jrun, projectDir,
                Map.of("HOME", projectDir.resolve("home").toString(), "XDG_DATA_HOME", "", "JRUN_HOME", ""),
                "init", "demo");
        assertEquals(0, r.exitCode(), r.output());
        assertEquals("rootProject.name = \"demo\"\n", read("demo/settings.gradle.kts"));
        assertTrue(read("demo/build.gradle.kts")
                .contains("id(\"com.archforge.jrun\") version \"" + JrunVersion.get() + "\""));
        assertTrue(exists("demo/main/Main.java") && exists("demo/.gitignore"));
        assertTrue(exists("demo/gradlew") && exists("demo/gradle/wrapper/gradle-wrapper.jar"),
                "init should copy the Gradle wrapper so no Gradle install is needed");
        assertTrue(Files.isExecutable(projectDir.resolve("demo/jrun")));

        // 生成的项目要能被插件正常构建；TestKit 注入插件时 plugins 块里不能写 version，先去掉
        Path demo = projectDir.resolve("demo");
        write("demo/build.gradle.kts", read("demo/build.gradle.kts").replaceAll(" version \"[^\"]+\"", ""));
        runnerIn(demo, "jrunInit").build();
        ProcessResult run = runBash(demo.resolve("jrun"), demo, Map.of());
        assertEquals(0, run.exitCode(), run.output());
        assertTrue(run.output().contains("Hello from jrun"), run.output());
    }

    @Test
    void initRefusesToTouchAnExistingProject() throws IOException {
        settings("existing");
        minimalBuild();

        ProcessResult r = runBash(globalJrun(), projectDir, Map.of(), "init", ".");
        assertNotEquals(0, r.exitCode(), r.output());
        assertTrue(r.output().contains("已经是 Gradle 项目"), r.output());
    }

    @Test
    void theGlobalLauncherDelegatesToTheNearestProject() throws IOException {
        helloProject("");

        ProcessResult r = runBash(globalJrun(), projectDir.resolve("main"), Map.of(), "Hello");
        assertEquals(0, r.exitCode(), r.output());
        assertTrue(r.output().contains("HELLO"), r.output());
    }

    @Test
    void theRootLauncherDelegatesToModulesAndModulesFindTheRootGradlew() {
        write("settings.gradle", "rootProject.name = 'multi'\ninclude 'app'\n");
        buildScript("plugins { id 'com.archforge.jrun' }\n");
        write("app/build.gradle", "plugins { id 'com.archforge.jrun' }\njrun { scripts.put('dev', 'Hello') }\n");
        write("app/main/Hello.java",
                "public class Hello { public static void main(String[] a) { System.out.println(\"APP_MODULE\"); } }\n");
        runner("jrunInit").build();
        fakeGradlew("exit 0\n");

        ProcessResult r = jrun("-m", "app", "dev");
        assertEquals(0, r.exitCode(), r.output());
        assertTrue(r.output().contains("APP_MODULE"), r.output());
        assertEquals("", gradleCalls(), "a fresh module cache must not start Gradle");

        write("app/build.gradle", read("app/build.gradle") + "// touched\n");
        jrun("-m", "app", "dev");
        assertTrue(gradleCalls().contains("jrunInit"), "the module launcher must use the root gradlew");
    }

    /** 回归：jrunInit 曾只编译本模块，classpath 上的 lib.jar 不存在；lib 的改动也不在 app 的指纹里。 */
    @Test
    void aModuleRunsAgainstItsProjectDependencyAndNoticesChangesInIt() {
        write("settings.gradle", "rootProject.name = 'multi'\ninclude 'app', 'lib'\n");
        buildScript("plugins { id 'com.archforge.jrun' }\n");
        write("lib/build.gradle", "plugins { id 'java-library' }\n");
        write("lib/src/main/java/lib/Greeter.java",
                "package lib; public class Greeter { public static String greet() { return \"FROM_LIB\"; } }\n");
        write("app/build.gradle", "plugins { id 'com.archforge.jrun' }\n"
                + "dependencies { implementation project(':lib') }\njrun { scripts.put('dev', 'App') }\n");
        write("app/main/App.java",
                "public class App { public static void main(String[] a) { System.out.println(lib.Greeter.greet()); } }\n");
        runner("jrunInit").build();
        fakeGradlew("exit 0\n");

        ProcessResult r = jrun("-m", "app", "dev");
        assertEquals(0, r.exitCode(), r.output());
        assertTrue(r.output().contains("FROM_LIB"), "jrunInit must build the module's project dependencies:\n"
                + r.output());
        assertEquals("", gradleCalls(), "a fresh cache must not start Gradle");

        write("lib/src/main/java/lib/Greeter.java",
                "package lib; public class Greeter { public static String greet() { return \"CHANGED\"; } }\n");
        jrun("-m", "app", "dev");
        assertTrue(gradleCalls().contains("jrunInit"), "a change in a project dependency must refresh the cache");
    }
}
