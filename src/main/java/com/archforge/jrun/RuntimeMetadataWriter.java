package com.archforge.jrun;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.gradle.api.JavaVersion;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.jvm.toolchain.JavaLanguageVersion;

/** jrunInit 的产物：java @argfile、默认目标与 scripts 元数据，都在 build/jrun 下。 */
final class RuntimeMetadataWriter {

    static final String OUTPUT_DIR = "jrun";

    private RuntimeMetadataWriter() {
    }

    static void write(Project project, JrunExtension extension) throws IOException {
        Path outDir = project.getLayout().getBuildDirectory().get().getAsFile().toPath().resolve(OUTPUT_DIR);
        // argfile 里的 classpath 是平台相关的（Windows 用 D:\... + ";"，Linux/WSL 用 /mnt/d/... + ":"）。
        // 同一台机器上 Windows 与 WSL 共用 build/ 目录，必须按平台分目录，否则会互相覆盖。
        Path argsDir = outDir.resolve(platformDir());

        String classpath = project.getExtensions().getByType(SourceSetContainer.class)
                .getByName(SourceSet.MAIN_SOURCE_SET_NAME).getRuntimeClasspath().getAsPath();
        // argfile：classpath 作为一个整体参数。Java @argfile 里反斜杠是转义符，Windows 路径必须写成 \\，
        // 否则 "D:\idea\..." 会被吞成 "D:idea..."，classpath 完全失效。
        String escapedClasspath = classpath.replace("\\", "\\\\");
        FileWrites.writeIfChanged(argsDir.resolve("class.args"),
                "--class-path\n\"" + escapedClasspath + "\"\n");
        FileWrites.writeIfChanged(argsDir.resolve("source.args"),
                "--class-path\n\"" + escapedClasspath + "\"\n--source\n" + resolveRelease(project) + "\n");

        FileWrites.writeIfChanged(outDir.resolve("default-target.txt"),
                extension.getMainClass().getOrElse("") + "\n");

        // scripts 元数据：脚本运行期从 properties 读取（避免在 shell 里解析 JSON）
        StringBuilder props = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(extension.getScripts().get()).entrySet()) {
            props.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        FileWrites.writeIfChanged(outDir.resolve("scripts.properties"), props.toString());

        // 内容指纹：启动器据此判断缓存是否最新（替代 mtime 比较）。必须最后写：
        // 构建中途失败时不能留下"缓存是新的"这种假信号
        Path projectDir = project.getProjectDir().toPath();
        FileWrites.writeIfChanged(argsDir.resolve("inputs.sha256"),
                InputsFingerprint.inputs(projectDir, project.getRootDir().toPath()));
        FileWrites.writeIfChanged(argsDir.resolve("sources.sha256"), InputsFingerprint.sources(projectDir));
    }

    /** argfile 的平台子目录名，与 jrun 启动器里的 PLATFORM 判定保持一致。 */
    static String platformDir() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "win" : "unix";
    }

    private static int resolveRelease(Project project) {
        // JavaPluginExtension#getToolchain() 是 Gradle 7.6 才有的 API。低版本上直接调用会抛
        // NoSuchMethodError（Error，不是 Exception），因此这里连带捕获并降级。
        try {
            JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
            Integer configured = java.getToolchain().getLanguageVersion()
                    .map(JavaLanguageVersion::asInt).getOrNull();
            if (configured != null) {
                return configured;
            }
        } catch (NoSuchMethodError | Exception ignored) {
            // 降级：用当前 JVM 版本
        }
        return Integer.parseInt(JavaVersion.current().getMajorVersion());
    }
}
