package com.archforge.jrun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 把 {@code jrun { scripts }} 解析成可运行目标，供任务注册与 IDE 运行配置共用。
 *
 * <p>判断依据是「能否推出主类」，而不是字符串形态——{@code a.b.C}、默认包的 {@code Hello}、
 * {@code Hello.java} 都能覆盖；{@code gradle:xxx} 不生成目标（用户直接跑那个 Gradle 任务）。
 */
final class ScriptResolver {

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)");

    /** 一个可运行的 script。 */
    record ResolvedScript(String name, String taskName, String mainClass, List<String> args) {
    }

    private final Path projectDir;
    private final String taskPrefix;

    ScriptResolver(Path projectDir, String taskPrefix) {
        this.projectDir = projectDir;
        this.taskPrefix = taskPrefix;
    }

    List<ResolvedScript> resolve(Map<String, String> scripts) {
        List<ResolvedScript> targets = new ArrayList<>();
        for (Map.Entry<String, String> entry : scripts.entrySet()) {
            String command = entry.getValue().trim();
            if (command.isEmpty()) {
                continue;
            }
            String[] parts = command.split("\\s+");
            String target = parts[0];
            if (target.startsWith("gradle:")) {
                continue;
            }
            String mainClass = looksLikeSourceFile(target) ? mainClassFromSource(target) : target;
            String taskName = taskName(entry.getKey());
            if (mainClass == null || JrunPlugin.INIT_TASK.equals(taskName)) {
                continue;
            }
            targets.add(new ResolvedScript(entry.getKey(), taskName, mainClass,
                    List.of(parts).subList(1, parts.length)));
        }
        return targets;
    }

    String taskName(String scriptName) {
        return taskPrefix.isEmpty()
                ? scriptName
                : taskPrefix + Character.toUpperCase(scriptName.charAt(0)) + scriptName.substring(1);
    }

    static boolean looksLikeSourceFile(String target) {
        return target.endsWith(".java") || target.endsWith(".kt") || target.endsWith(".groovy");
    }

    /**
     * 从源文件推导主类名：读 {@code package} 声明 + 文件名。
     * Kotlin 的顶层 main 编译后是 {@code <File>Kt}，所以 .kt 要加 Kt 后缀。
     */
    String mainClassFromSource(String target) {
        Path file = locate(target);
        if (file == null) {
            return null;
        }
        String pkg = "";
        try {
            for (String line : Files.readAllLines(file)) {
                Matcher matcher = PACKAGE.matcher(line);
                if (matcher.find()) {
                    pkg = matcher.group(1);
                    break;
                }
            }
        } catch (IOException e) {
            return null;
        }
        String name = file.getFileName().toString();
        String base = name.replaceAll("\\.[^.]+$", "");
        String suffix = name.endsWith(".kt") ? "Kt" : "";
        return pkg.isEmpty() ? base + suffix : pkg + "." + base + suffix;
    }

    /** 按 ./jrun 相同的顺序找源文件：原路径 → main/ 下同路径 → main/ 下同名文件。 */
    private Path locate(String target) {
        Path direct = projectDir.resolve(target);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        Path mainDir = projectDir.resolve("main");
        if (Files.isRegularFile(mainDir.resolve(target))) {
            return mainDir.resolve(target);
        }
        if (!Files.isDirectory(mainDir)) {
            return null;
        }
        String fileName = Path.of(target).getFileName().toString();
        try (Stream<Path> stream = Files.walk(mainDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
