package com.archforge.jrun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.gradle.api.InvalidUserDataException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.archforge.jrun.ScriptResolver.Kind;
import com.archforge.jrun.ScriptResolver.ResolvedScript;
import com.archforge.jrun.ScriptResolver.Spec;

/** script 解析是纯逻辑：不起 Gradle，直接对临时目录里的源码做断言。 */
class ScriptResolverTest {

    @TempDir
    Path dir;

    private Map<String, ResolvedScript> resolve(Map<String, String> legacy, Map<String, Spec> specs) {
        return new ScriptResolver(dir, "jrun").resolve(legacy, specs).stream()
                .collect(Collectors.toMap(ResolvedScript::name, Function.identity()));
    }

    private static Spec spec(String target, List<String> args, List<String> dependsOn) {
        return new Spec(target, args, dependsOn);
    }

    @Test
    void legacyStringsKeepWhitespaceSplitting() {
        ResolvedScript api = resolve(Map.of("api", "com.example.App --server.port=8080 a=1"), Map.of()).get("api");
        assertEquals(Kind.CLASS, api.kind());
        assertEquals("com.example.App", api.target());
        assertEquals(List.of("--server.port=8080", "a=1"), api.args());
        assertEquals("jrunApi", api.taskName());
        assertEquals("com.example.App --server.port=8080 a=1", api.display());
    }

    @Test
    void blockArgumentsKeepSpacesAndQuotesIntact() {
        ResolvedScript api = resolve(Map.of(),
                Map.of("api", spec("com.example.App", List.of("--greeting=hello world", "say \"hi\""), List.of())))
                .get("api");
        assertEquals(List.of("--greeting=hello world", "say \"hi\""), api.args());
        assertEquals("com.example.App \"--greeting=hello world\" \"say \\\"hi\\\"\"", api.display());
    }

    @Test
    void prefixesSelectGradleAndShellKinds() {
        Map<String, ResolvedScript> scripts = resolve(
                Map.of("verify", "gradle:build --info", "gen", "shell:./gen.sh && echo done"), Map.of());
        assertEquals(Kind.GRADLE, scripts.get("verify").kind());
        assertEquals("build --info", scripts.get("verify").target());
        assertNull(scripts.get("verify").taskName(), "gradle: scripts reuse the user's own task");
        assertEquals(Kind.SHELL, scripts.get("gen").kind());
        assertEquals("./gen.sh && echo done", scripts.get("gen").target(), "shell commands are never split");
        assertEquals("jrunGen", scripts.get("gen").taskName());
    }

    @Test
    void sourceFilesResolvePackageAndKotlinSuffix() throws IOException {
        Files.createDirectories(dir.resolve("main/com/example"));
        Files.writeString(dir.resolve("main/com/example/Tool.java"), "package com.example;\nclass Tool {}\n");
        Files.writeString(dir.resolve("main/App.kt"), "package demo\n\nfun main() {}\n");

        Map<String, ResolvedScript> scripts = resolve(Map.of("tool", "Tool.java", "app", "App.kt"), Map.of());
        assertEquals(Kind.SOURCE, scripts.get("tool").kind());
        assertEquals("com.example.Tool", scripts.get("tool").mainClass());
        assertEquals(Kind.JVM_SOURCE, scripts.get("app").kind());
        assertEquals("demo.AppKt", scripts.get("app").mainClass());
    }

    @Test
    void missingSourcesAndReservedTaskNamesGetNoTask() {
        assertFalse(resolve(Map.of("ghost", "Ghost.java"), Map.of()).get("ghost").hasTask());
        ResolvedScript init = new ScriptResolver(dir, "").resolve(Map.of("jrunInit", "Hello"), Map.of()).get(0);
        assertFalse(init.hasTask(), "a script must never shadow the plugin's own jrunInit task");
    }

    @Test
    void dependenciesMustExist() {
        InvalidUserDataException e = assertThrows(InvalidUserDataException.class, () -> resolve(Map.of(),
                Map.of("api", spec("com.example.App", List.of(), List.of("gen")))));
        assertTrue(e.getMessage().contains("'api' depends on unknown script 'gen'"), e.getMessage());
    }

    @Test
    void dependencyCyclesAreReportedWithTheirPath() {
        InvalidUserDataException e = assertThrows(InvalidUserDataException.class, () -> resolve(Map.of(), Map.of(
                "a", spec("shell:echo a", List.of(), List.of("b")),
                "b", spec("shell:echo b", List.of(), List.of("a")))));
        assertTrue(e.getMessage().contains("dependency cycle: a -> b -> a"), e.getMessage());
    }

    @Test
    void invalidNamesAreRejected() {
        assertThrows(InvalidUserDataException.class, () -> resolve(Map.of("build prod", "Hello"), Map.of()));
        assertThrows(InvalidUserDataException.class, () -> resolve(Map.of("../x", "Hello"), Map.of()));
    }

    @Test
    void argsAreOnlyAllowedForJavaTargets() {
        InvalidUserDataException e = assertThrows(InvalidUserDataException.class, () -> resolve(Map.of(),
                Map.of("gen", spec("shell:./gen.sh", List.of("x"), List.of()))));
        assertTrue(e.getMessage().contains("only supported for Java targets"), e.getMessage());
    }

    @Test
    void aScriptCannotBeDeclaredTwice() {
        assertThrows(InvalidUserDataException.class, () -> resolve(Map.of("api", "com.example.App"),
                Map.of("api", spec("com.example.App", List.of(), List.of()))));
    }

    @Test
    void multiLineTargetsAreRejected() {
        assertThrows(InvalidUserDataException.class, () -> resolve(Map.of("gen", "shell:echo a\necho b"), Map.of()));
    }
}
