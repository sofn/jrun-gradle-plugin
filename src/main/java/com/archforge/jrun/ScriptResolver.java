package com.archforge.jrun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.gradle.api.InvalidUserDataException;

/**
 * 把 {@code jrun { scripts }} 与块写法 {@code script(…) { }} 解析成统一的 script 列表，
 * 供任务注册、IDE 运行配置与 jrunInit 的运行时元数据共用。
 *
 * <p>目标的四种形态：{@code a.b.C}（类名）、{@code Xxx.java/.kt/.groovy/.scala}（源文件，主类由
 * {@code package} 声明 + 文件名推导）、{@code gradle:task}、{@code shell:命令}。
 * 名称、依赖（不存在或成环）与参数用法在这里统一校验，配置阶段就报错。
 */
final class ScriptResolver {

    /** script 名：同时用作文件名与 Gradle 任务名的一部分，只允许安全字符。 */
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)");

    /** 插件自己的任务，script 生成的任务不能与之同名。 */
    private static final Set<String> RESERVED_TASKS =
            Set.of(JrunPlugin.INIT_TASK, JrunPlugin.ADD_TASK, JrunPlugin.RESOLVE_TASK);

    enum Kind {
        CLASS("class"), SOURCE("source"), JVM_SOURCE("jvm-source"), GRADLE("gradle"), SHELL("shell");

        final String id;

        Kind(String id) {
            this.id = id;
        }

        boolean isJava() {
            return this == CLASS || this == SOURCE || this == JVM_SOURCE;
        }
    }

    /** 声明：target 为原始写法（含 gradle:/shell: 前缀）。 */
    record Spec(String target, List<String> args, List<String> dependsOn) {
    }

    /**
     * 解析结果。target 已去掉前缀：CLASS 为类名，SOURCE/JVM_SOURCE 为源文件路径，
     * GRADLE 为"任务 + Gradle 参数"，SHELL 为整条命令。taskName 为 null 表示不生成 Gradle 任务。
     */
    record ResolvedScript(String name, Kind kind, String target, String mainClass, List<String> args,
                          List<String> dependsOn, String taskName, String display) {
        boolean hasTask() {
            return taskName != null;
        }
    }

    private final Path projectDir;
    private final String taskPrefix;

    ScriptResolver(Path projectDir, String taskPrefix) {
        this.projectDir = projectDir;
        this.taskPrefix = taskPrefix;
    }

    List<ResolvedScript> resolve(Map<String, String> legacy, Map<String, Spec> specs) {
        Map<String, Spec> all = new TreeMap<>();
        Map<String, String> displays = new HashMap<>();
        for (Map.Entry<String, String> e : legacy.entrySet()) {
            String value = e.getValue().trim();
            if (!value.isEmpty()) {
                all.put(e.getKey(), parseLegacy(value));
                displays.put(e.getKey(), value);
            }
        }
        for (Map.Entry<String, Spec> e : specs.entrySet()) {
            if (all.containsKey(e.getKey())) {
                throw new InvalidUserDataException("jrun script '" + e.getKey()
                        + "' is declared twice (scripts.put and script { })");
            }
            all.put(e.getKey(), e.getValue());
        }
        validate(all);

        List<ResolvedScript> resolved = new ArrayList<>();
        for (Map.Entry<String, Spec> e : all.entrySet()) {
            resolved.add(toResolved(e.getKey(), e.getValue(), displays.get(e.getKey())));
        }
        return resolved;
    }

    /** 旧写法：shell: / gradle: 整条保留（gradle: 的参数由启动器拆）；其余按空白拆成 目标 + 固定参数。 */
    private static Spec parseLegacy(String value) {
        if (value.startsWith("shell:") || value.startsWith("gradle:")) {
            return new Spec(value, List.of(), List.of());
        }
        String[] parts = value.split("\\s+");
        return new Spec(parts[0], List.of(parts).subList(1, parts.length), List.of());
    }

    private ResolvedScript toResolved(String name, Spec spec, String legacyDisplay) {
        String raw = spec.target().trim();
        Kind kind;
        String target;
        if (raw.startsWith("shell:")) {
            kind = Kind.SHELL;
            target = raw.substring("shell:".length()).trim();
        } else if (raw.startsWith("gradle:")) {
            kind = Kind.GRADLE;
            target = raw.substring("gradle:".length()).trim();
        } else {
            target = raw;
            kind = raw.endsWith(".java") ? Kind.SOURCE : looksLikeSourceFile(raw) ? Kind.JVM_SOURCE : Kind.CLASS;
        }
        if (!kind.isJava() && !spec.args().isEmpty()) {
            throw new InvalidUserDataException("jrun script '" + name + "': args(...) is only supported for "
                    + "Java targets; put the arguments into the \"" + raw + "\" command instead");
        }
        String mainClass = kind == Kind.CLASS ? target : kind.isJava() ? mainClassFromSource(target) : null;
        String taskName = taskName(name);
        boolean runnable = kind == Kind.SHELL || (kind.isJava() && mainClass != null);
        if (!runnable || RESERVED_TASKS.contains(taskName)) {
            taskName = null;
        }
        String display = legacyDisplay != null ? legacyDisplay : displayOf(raw, spec.args());
        return new ResolvedScript(name, kind, target, mainClass, List.copyOf(spec.args()),
                List.copyOf(spec.dependsOn()), taskName, display);
    }

    private static String displayOf(String raw, List<String> args) {
        StringBuilder out = new StringBuilder(raw);
        for (String arg : args) {
            out.append(' ').append(arg.matches("[^\\s\"'\\\\]+") ? arg : ArgFiles.quote(arg));
        }
        return out.toString();
    }

    private static void validate(Map<String, Spec> all) {
        for (Map.Entry<String, Spec> e : all.entrySet()) {
            if (!NAME.matcher(e.getKey()).matches()) {
                throw new InvalidUserDataException("jrun script name '" + e.getKey()
                        + "' is invalid: use letters, digits, '.', '_' or '-'");
            }
            if (e.getValue().target().contains("\n") || e.getValue().target().contains("\r")) {
                throw new InvalidUserDataException("jrun script '" + e.getKey() + "' must be a single line");
            }
            for (String dep : e.getValue().dependsOn()) {
                if (!all.containsKey(dep)) {
                    throw new InvalidUserDataException("jrun script '" + e.getKey()
                            + "' depends on unknown script '" + dep + "'");
                }
            }
        }
        Map<String, Boolean> done = new HashMap<>();
        for (String name : all.keySet()) {
            visit(name, all, done, new ArrayList<>());
        }
    }

    /** 深度优先找环：done=false 表示仍在当前路径上。 */
    private static void visit(String name, Map<String, Spec> all, Map<String, Boolean> done, List<String> path) {
        Boolean state = done.get(name);
        if (Boolean.TRUE.equals(state)) {
            return;
        }
        path.add(name);
        if (state != null) {
            throw new InvalidUserDataException("jrun script dependency cycle: "
                    + String.join(" -> ", path.subList(path.indexOf(name), path.size())));
        }
        done.put(name, false);
        for (String dep : all.get(name).dependsOn()) {
            visit(dep, all, done, path);
        }
        done.put(name, true);
        path.remove(path.size() - 1);
    }

    String taskName(String scriptName) {
        return taskPrefix.isEmpty()
                ? scriptName
                : taskPrefix + Character.toUpperCase(scriptName.charAt(0)) + scriptName.substring(1);
    }

    static boolean looksLikeSourceFile(String target) {
        return target.endsWith(".java") || target.endsWith(".kt") || target.endsWith(".groovy")
                || target.endsWith(".scala");
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
