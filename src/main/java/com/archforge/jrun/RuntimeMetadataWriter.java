package com.archforge.jrun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import org.gradle.api.JavaVersion;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.jvm.toolchain.JavaLanguageVersion;

/** jrunInit 的产物：java @argfile、默认目标、scripts 元数据与内容指纹，都在 build/jrun 下。 */
final class RuntimeMetadataWriter {

    static final String OUTPUT_DIR = "jrun";

    private RuntimeMetadataWriter() {
    }

    static void write(Project project, JrunExtension extension, List<ScriptResolver.ResolvedScript> scripts)
            throws IOException {
        Path outDir = project.getLayout().getBuildDirectory().get().getAsFile().toPath().resolve(OUTPUT_DIR);
        // argfile 里的 classpath 是平台相关的（Windows 用 D:\... + ";"，Linux/WSL 用 /mnt/d/... + ":"）。
        // 同一台机器上 Windows 与 WSL 共用 build/ 目录，必须按平台分目录，否则会互相覆盖。
        Path argsDir = outDir.resolve(platformDir());

        String classpath = project.getExtensions().getByType(SourceSetContainer.class)
                .getByName(SourceSet.MAIN_SOURCE_SET_NAME).getRuntimeClasspath().getAsPath();
        // argfile：classpath 作为一个整体参数。Java @argfile 里反斜杠是转义符，Windows 路径必须写成 \\，
        // 否则 "D:\idea\..." 会被吞成 "D:idea..."，classpath 完全失效。
        String quotedClasspath = ArgFiles.quote(classpath);
        FileWrites.writeIfChanged(argsDir.resolve("class.args"), "--class-path\n" + quotedClasspath + "\n");
        FileWrites.writeIfChanged(argsDir.resolve("source.args"),
                "--class-path\n" + quotedClasspath + "\n--source\n" + resolveRelease(project) + "\n");

        FileWrites.writeIfChanged(outDir.resolve("default-target.txt"),
                extension.getMainClass().getOrElse("") + "\n");

        // scripts 一览（./jrun --help 展示用）与每个 script 的元数据（启动器执行用）
        StringBuilder props = new StringBuilder();
        for (ScriptResolver.ResolvedScript s : scripts) {
            props.append(s.name()).append('=').append(s.display()).append('\n');
        }
        FileWrites.writeIfChanged(outDir.resolve("scripts.properties"), props.toString());
        writeScriptFiles(outDir.resolve("scripts"), scripts);

        // 内容指纹：启动器据此判断缓存是否最新（替代 mtime 比较）。必须最后写：
        // 构建中途失败时不能留下"缓存是新的"这种假信号
        Path projectDir = project.getProjectDir().toPath();
        FileWrites.writeIfChanged(argsDir.resolve("inputs.sha256"),
                InputsFingerprint.inputs(projectDir, project.getRootDir().toPath()));
        FileWrites.writeIfChanged(argsDir.resolve("sources.sha256"), InputsFingerprint.sources(projectDir));
    }

    /**
     * 每个 script 两个文件，供启动器直接读取：
     * <ul>
     *   <li>{@code <name>.properties}：kind / target / deps（一行一个键）</li>
     *   <li>{@code <name>.args}：Java 目标的固定参数，每行一个 argfile 引用参数</li>
     * </ul>
     */
    private static void writeScriptFiles(Path dir, List<ScriptResolver.ResolvedScript> scripts) throws IOException {
        Set<String> keep = new HashSet<>();
        for (ScriptResolver.ResolvedScript s : scripts) {
            FileWrites.writeIfChanged(dir.resolve(s.name() + ".properties"), "kind=" + s.kind().id
                    + "\ntarget=" + s.target() + "\ndeps=" + String.join(" ", s.dependsOn()) + "\n");
            keep.add(s.name() + ".properties");
            if (s.kind().isJava()) {
                StringBuilder args = new StringBuilder();
                s.args().forEach(a -> args.append(ArgFiles.quote(a)).append('\n'));
                FileWrites.writeIfChanged(dir.resolve(s.name() + ".args"), args.toString());
                keep.add(s.name() + ".args");
            }
        }
        if (Files.isDirectory(dir)) {
            try (Stream<Path> children = Files.list(dir)) {
                for (Path child : children.toList()) {
                    if (!keep.contains(child.getFileName().toString())) {
                        Files.delete(child);
                    }
                }
            }
        }
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
