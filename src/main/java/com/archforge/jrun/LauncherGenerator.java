package com.archforge.jrun;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.gradle.api.Project;

/** 在项目根目录生成 ./jrun（bash）与 jrun.bat（CMD），并清理被关掉的或旧版本遗留的启动器。 */
final class LauncherGenerator {

    static final String VERSION_PLACEHOLDER = "@JRUN_VERSION@";

    private LauncherGenerator() {
    }

    static void generate(Project project, JrunExtension extension) throws IOException {
        Set<String> requested = extension.getLaunchers().get().stream()
                .map(s -> s.toLowerCase(Locale.ROOT).trim())
                .collect(Collectors.toSet());

        boolean wantBash = requested.contains("bash") || requested.contains("sh");
        boolean wantCmd = requested.contains("cmd") || requested.contains("bat") || requested.contains("windows");

        // PowerShell 启动器已被移除：PowerShell 可以直接执行 jrun.bat，
        // 多维护一份 .ps1 只会带来行为漂移的风险（Gradle 本身也只有 gradlew / gradlew.bat）。
        if (requested.contains("powershell") || requested.contains("ps1") || requested.contains("pwsh")) {
            project.getLogger().warn(
                    "[jrun] The PowerShell launcher has been removed. PowerShell can run jrun.bat "
                            + "directly, so 'powershell' in jrun.launchers is ignored.");
        }

        // bash：LF；CMD：CRLF（CMD 的 goto/label 解析要求 CRLF）。
        // 未启用的平台会删掉此前生成的文件，避免"关了还在"的困惑。
        Path root = project.getProjectDir().toPath();
        writeOrRemove(project, wantBash, root.resolve("jrun"), "jrun.sh", false);
        writeOrRemove(project, wantCmd, root.resolve("jrun.bat"), "jrun.bat", true);

        // 清理旧版本升级后遗留的文件（jrun.ps1 启动器、jrun.json 清单都已移除）
        Files.deleteIfExists(root.resolve("jrun.ps1"));
        Files.deleteIfExists(root.resolve("jrun.json"));
    }

    /** 启动器模板 → 最终内容（换行统一为 LF，替换版本号）；.bat 的 CRLF 由写入方处理。 */
    static String render(String resourceName) throws IOException {
        String content = readResource("/jrun/" + resourceName).replace("\r\n", "\n");
        return content.replace(VERSION_PLACEHOLDER, JrunVersion.get());
    }

    private static void writeOrRemove(Project project, boolean enabled, Path target, String resourceName,
                                      boolean crlf) throws IOException {
        if (!enabled) {
            Files.deleteIfExists(target);
            return;
        }
        String content = render(resourceName);

        // .bat 必须保持纯 ASCII：Windows 控制台默认代码页常常是 GBK/CP936，
        // UTF-8 中文会乱码，甚至破坏命令解析。这里做一道运行期防护。
        if (crlf && content.chars().anyMatch(c -> c > 0x7F)) {
            project.getLogger().warn(
                    "[jrun] {} contains non-ASCII characters; a GBK console may garble them. "
                            + "Keep .bat launcher templates ASCII-only.", resourceName);
        }
        if (crlf) {
            content = content.replace("\n", "\r\n");
        }
        FileWrites.writeIfChanged(target, content);
        if (!crlf) {
            File file = target.toFile();
            if (!file.canExecute()) {
                file.setExecutable(true, false);
            }
        }
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = LauncherGenerator.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("插件资源缺失: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
