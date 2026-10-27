package com.archforge.jrun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 清单格式是插件与启动器之间的协议：sha256sum 格式、固定顺序、相对路径。 */
class InputsFingerprintTest {

    /** sha256("hello\n")，与 `printf 'hello\n' | sha256sum` 一致 */
    private static final String HELLO = "5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03";

    @TempDir
    Path root;

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void inputsListProjectFilesThenBuildRootFilesInFixedOrder() throws IOException {
        write("settings.gradle.kts", "hello\n");
        write("gradle/libs.versions.toml", "hello\n");
        write("app/build.gradle.kts", "hello\n");
        write("app/gradle.properties", "hello\n");

        assertEquals(HELLO + "  build.gradle.kts\n"
                        + HELLO + "  gradle.properties\n"
                        + HELLO + "  ../gradle/libs.versions.toml\n"
                        + HELLO + "  ../settings.gradle.kts\n",
                InputsFingerprint.inputs(root.resolve("app"), root));
    }

    @Test
    void inputsOfASingleProjectBuildAreListedOnce() throws IOException {
        write("build.gradle", "hello\n");
        write("settings.gradle", "hello\n");

        assertEquals(HELLO + "  build.gradle\n" + HELLO + "  settings.gradle\n",
                InputsFingerprint.inputs(root, root));
    }

    @Test
    void sourcesIncludeTheSourceRootsOfProjectDependencies() throws IOException {
        write("app/main/App.java", "hello\n");
        write("lib/src/main/java/lib/Greeter.java", "hello\n");

        assertEquals(HELLO + "  ../lib/src/main/java/lib/Greeter.java\n" + HELLO + "  main/App.java\n",
                InputsFingerprint.sources(root.resolve("app"), java.util.List.of("../lib/src/main/java")));
    }

    @Test
    void inputsIncludeTheBuildFilesOfProjectDependencies() throws IOException {
        write("settings.gradle", "hello\n");
        write("app/build.gradle", "hello\n");
        write("lib/build.gradle", "hello\n");

        assertEquals(HELLO + "  build.gradle\n" + HELLO + "  ../settings.gradle\n" + HELLO + "  ../lib/build.gradle\n",
                InputsFingerprint.inputs(root.resolve("app"), root, java.util.List.of(root.resolve("lib"))));
    }

    @Test
    void sourcesCoverMainAndResourcesSortedBytewiseButNotTests() throws IOException {
        write("main/b/B.java", "hello\n");
        write("main/a.java", "hello\n");
        write("main/B.java", "hello\n");
        write("resources/app.properties", "hello\n");
        write("test/ATest.java", "hello\n");

        assertEquals(HELLO + "  main/B.java\n"
                        + HELLO + "  main/a.java\n"
                        + HELLO + "  main/b/B.java\n"
                        + HELLO + "  resources/app.properties\n",
                InputsFingerprint.sources(root));
    }
}
