package com.archforge.jrun;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.io.TempDir;

/**
 * jrun 插件测试基类：负责在临时目录里搭一个最小 Gradle 项目。
 *
 * <p>用 Groovy DSL 写测试项目而不是 Kotlin DSL，原因是 KTS 每次都要编译脚本，
 * 会让每个测试多花 1~2 秒；KTS 的配置语法另有单独用例覆盖。
 */
abstract class AbstractJrunTest {

    @TempDir
    Path projectDir;

    protected void settings(String rootName) {
        write("settings.gradle", "rootProject.name = '" + rootName + "'\n");
    }

    protected void buildScript(String content) {
        write("build.gradle", content);
    }

    /** 写一个最小可用的 build.gradle：只应用 jrun 插件。 */
    protected void minimalBuild() {
        buildScript("plugins { id 'com.archforge.jrun' }\n");
    }

    protected Path write(String relativePath, String content) {
        try {
            Path target = projectDir.resolve(relativePath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected String read(String relativePath) {
        try {
            return Files.readString(projectDir.resolve(relativePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected byte[] readBytes(String relativePath) {
        try {
            return Files.readAllBytes(projectDir.resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected boolean exists(String relativePath) {
        return Files.exists(projectDir.resolve(relativePath));
    }

    protected GradleRunner runner(String... arguments) {
        GradleRunner runner = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(arguments)
                .withPluginClasspath();

        String testKitDir = System.getProperty("jrun.testkit.dir");
        if (testKitDir != null && !testKitDir.isBlank()) {
            // 固定的 TestKit 目录：daemon 与已下载的插件在用例之间、多次构建之间复用
            runner = runner.withTestKitDir(new java.io.File(testKitDir));
        }

        // 兼容性矩阵：CI 通过 -PgradleCompatVersion=<ver> 指定，TestKit 会下载并用该版本运行
        String compatVersion = System.getProperty("jrun.gradleCompatVersion");
        if (compatVersion != null && !compatVersion.isBlank()) {
            runner = runner.withGradleVersion(compatVersion);
        }
        return runner;
    }

    protected static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** 当前平台对应的 argfile 子目录名，与插件的 RuntimeMetadataWriter#platformDir 保持一致。 */
    protected static String platformDir() {
        return isWindows() ? "win" : "unix";
    }

    /** Gradle 运行测试所用的 JDK 主版本，即 argfile 里 --source 的期望值。 */
    protected static int expectedRelease() {
        String spec = System.getProperty("java.specification.version", "17");
        return spec.contains(".") ? Integer.parseInt(spec.substring(0, spec.indexOf('.'))) : Integer.parseInt(spec);
    }
}
