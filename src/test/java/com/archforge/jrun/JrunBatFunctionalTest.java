package com.archforge.jrun;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * jrun.bat 端到端（CMD），只在 Windows 上运行（CI 的 windows-latest）。
 *
 * <p>每个用例先用 TestKit 跑 jrunInit 准备缓存，再放一个假 gradlew.bat：它把调用参数记到
 * gradle-calls.log，jrunResolve 一律失败（用来验证 add 的回滚），其余调用成功。
 */
@EnabledOnOs(OS.WINDOWS)
class JrunBatFunctionalTest extends AbstractJrunTest {

    private ProcessResult bat(Path dir, Map<String, String> env, String... args) {
        List<String> command = new ArrayList<>(List.of("cmd", "/c", dir.resolve("jrun.bat").toString()));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().put("JAVA_HOME", System.getProperty("java.home"));
        pb.environment().putAll(env);
        try {
            Process process = pb.start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "jrun.bat timed out:\n" + out);
            return new ProcessResult(process.exitValue(), out);
        } catch (IOException e) {
            throw new AssertionError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private void project(String jrunBlock) {
        settings("bat");
        buildScript("plugins { id 'com.archforge.jrun' }\n" + jrunBlock);
        write("main/Hello.java", """
                public class Hello {
                    public static void main(String[] args) {
                        System.out.println("HELLO");
                        for (String a : args) {
                            System.out.println("ARG=[" + a + "]");
                        }
                        System.out.println("PROP_B=" + System.getProperty("jrun.b"));
                    }
                }
                """);
        runner("jrunInit").build();
        write("gradlew.bat", "@echo off\r\necho %* >> \"%~dp0gradle-calls.log\"\r\n"
                + "echo %* | findstr /c:\"jrunResolve\" >nul && exit /b 1\r\nexit /b 0\r\n");
    }

    @Test
    void namedScriptsKeepArrayArgumentsAndHonourJrunOpts() {
        project("jrun { script('greet', 'Hello') { args '--greeting=hello world' } }\n");

        ProcessResult r = bat(projectDir, Map.of("JRUN_OPTS", "-Djrun.b=jrun"), "greet", "extra");
        assertEquals(0, r.exitCode(), r.output());
        assertTrue(r.output().contains("ARG=[--greeting=hello world]"), r.output());
        assertTrue(r.output().contains("ARG=[extra]"), r.output());
        assertTrue(r.output().contains("PROP_B=jrun"), r.output());
    }

    @Test
    void dependsOnRunsShellScriptsFirst() {
        project("""
                jrun {
                    script('first', 'shell:echo FIRST')
                    script('all', 'Hello') { dependsOn 'first' }
                }
                """);

        String out = bat(projectDir, Map.of(), "all").output();
        int first = out.indexOf("FIRST");
        assertTrue(first >= 0 && first < out.indexOf("HELLO"), out);
    }

    @Test
    void changedBuildInputsRefreshTheCache() throws IOException {
        project("");
        Files.writeString(projectDir.resolve("build.gradle"), read("build.gradle") + "// touched\n");

        bat(projectDir, Map.of(), "Hello.java");
        assertTrue(read("gradle-calls.log").contains("jrunInit"), read("gradle-calls.log"));
    }

    @Test
    void addRestoresTheBuildScriptWhenResolutionFails() {
        project("");
        String original = read("build.gradle");

        ProcessResult r = bat(projectDir, Map.of(), "add", "com.example:nope:1.0");
        assertNotEquals(0, r.exitCode(), r.output());
        assertEquals(original, read("build.gradle"));
    }

    @Test
    void theGlobalLauncherPrintsItsVersionAndScaffolds() throws IOException {
        Path bin = Files.createDirectories(projectDir.resolve("bin"));
        Files.writeString(bin.resolve("jrun.bat"),
                LauncherGenerator.render("jrun.bat").replace("\r\n", "\n").replace("\n", "\r\n"));
        assertEquals("jrun " + JrunVersion.get(), bat(bin, Map.of(), "--version").output().trim());

        ProcessResult init = bat(bin, Map.of("JRUN_HOME", projectDir.resolve("data").toString()), "init",
                projectDir.resolve("demo").toString());
        assertEquals(0, init.exitCode(), init.output());
        assertTrue(read("demo/build.gradle.kts").contains("version \"" + JrunVersion.get() + "\""));
        assertTrue(exists("demo/main/Main.java") && exists("demo/jrun.bat"));
        assertFalse(read("demo/main/Main.java").isBlank());
    }
}
