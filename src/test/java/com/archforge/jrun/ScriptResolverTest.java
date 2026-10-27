package com.archforge.jrun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** script 解析是纯逻辑：不起 Gradle，直接对临时目录里的源码做断言。 */
class ScriptResolverTest {

    @TempDir
    Path dir;

    private Map<String, ScriptResolver.ResolvedScript> resolve(String prefix, Map<String, String> scripts) {
        return new ScriptResolver(dir, prefix).resolve(new TreeMap<>(scripts)).stream()
                .collect(Collectors.toMap(ScriptResolver.ResolvedScript::name, Function.identity()));
    }

    @Test
    void classNameScriptsSplitFixedArgumentsOnWhitespace() {
        ScriptResolver.ResolvedScript api =
                resolve("jrun", Map.of("api", "com.example.App --server.port=8080 a=1")).get("api");
        assertEquals("jrunApi", api.taskName());
        assertEquals("com.example.App", api.mainClass());
        assertEquals(List.of("--server.port=8080", "a=1"), api.args());
    }

    @Test
    void sourceFilesResolvePackageAndKotlinSuffix() throws IOException {
        Files.createDirectories(dir.resolve("main/com/example"));
        Files.writeString(dir.resolve("main/com/example/Tool.java"), "package com.example;\nclass Tool {}\n");
        Files.writeString(dir.resolve("main/App.kt"), "package demo\n\nfun main() {}\n");

        Map<String, ScriptResolver.ResolvedScript> scripts =
                resolve("jrun", Map.of("tool", "Tool.java", "app", "App.kt"));
        assertEquals("com.example.Tool", scripts.get("tool").mainClass());
        assertEquals("demo.AppKt", scripts.get("app").mainClass());
    }

    @Test
    void gradleScriptsAndMissingSourcesAreNotRunnable() {
        assertTrue(resolve("jrun", Map.of("verify", "gradle:build", "ghost", "Ghost.java")).isEmpty());
    }

    @Test
    void emptyPrefixUsesTheScriptNameButNeverShadowsJrunInit() {
        Map<String, ScriptResolver.ResolvedScript> scripts =
                resolve("", Map.of("hello", "Hello", "jrunInit", "Hello"));
        assertEquals(List.of("hello"), List.copyOf(scripts.keySet()));
        assertEquals("hello", scripts.get("hello").taskName());
    }
}
