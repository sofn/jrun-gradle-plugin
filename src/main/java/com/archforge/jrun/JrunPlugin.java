package com.archforge.jrun;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.gradle.api.JavaVersion;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.logging.TestExceptionFormat;
import org.gradle.api.tasks.testing.logging.TestLogEvent;

/**
 * jrun Gradle 插件。
 *
 * <p>应用后自动获得：
 * <ul>
 *   <li>扁平目录约定：src/ 与 test/ 直接放 .java，不用 src/main/java</li>
 *   <li>项目根目录的 ./jrun、./jrun.bat、./jrun.ps1 启动器（自动生成）</li>
 *   <li>类似 package.json scripts 的命名命令：jrun { scripts.put(...) }</li>
 *   <li>jrunPrepare 任务：生成 java @argfile，脚本运行期无需拼接超长 classpath</li>
 * </ul>
 *
 * <p>设计要点：所有容易出错的工作（超长 classpath、引号、换行、JDK 版本、
 * Windows 反斜杠）都在插件里用 Java 完成，三个平台的脚本只是薄壳。
 */
public class JrunPlugin implements Plugin<Project> {

    public static final String INIT_TASK = "jrunInit";
    private static final String OUTPUT_DIR = "jrun";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("java");
        warnIfGradleTooOld(project);
        configureRepositories(project);

        JrunExtension extension = project.getExtensions().create("jrun", JrunExtension.class);
        // 默认只生成 bash + cmd，与 gradlew / gradlew.bat 的结构一致：
        // Windows 上有一个 jrun.bat 就够了，PowerShell 版按需显式开启。
        extension.getLaunchers().convention(java.util.List.of("bash", "cmd"));
        // IntelliJ 运行配置：默认开启，IDEA 里开箱即可一键运行
        extension.getGenerateIntellijRuns().convention(true);
        // 任务名前缀：默认 jrun（jrunHello），可设为空串得到 hello
        extension.getTaskPrefix().convention("jrun");

        configureFlatLayout(project);
        configureCompilerAndTests(project);
        TaskProvider<Task> initTask = registerInitTask(project, extension);
        hookStandardTasks(project, initTask);

        // 脚本、语言目录与 scripts 元数据先等所有插件 apply 完再处理：
        // kotlin / groovy 的 sourceSet 扩展只有在它们自己被应用后才存在。
        project.afterEvaluate(p -> {
            configureJvmLanguageSources(p);
            List<RunTarget> targets = resolveRunTargets(p, extension);
            registerScriptTasks(p, targets);
            generateLaunchers(p, extension, targets);
        });
    }

    /** 一个可运行的 script 解析结果，供任务注册与 IDE 运行配置共用。 */
    private record RunTarget(String scriptName, String taskName, String mainClass, List<String> args) {
    }

    /**
     * 把 scripts 解析成可运行目标。
     *
     * <p>判断依据是「能否推出主类」，而不是字符串形态——这样
     * {@code a.b.C}、默认包的 {@code Hello}、{@code Hello.java} 都能覆盖。
     * {@code gradle:xxx} 不生成目标（用户直接跑那个 Gradle 任务）。
     */
    private List<RunTarget> resolveRunTargets(Project project, JrunExtension extension) {
        List<RunTarget> targets = new java.util.ArrayList<>();

        for (Map.Entry<String, String> entry : extension.getScripts().get().entrySet()) {
            String scriptName = entry.getKey();
            String command = entry.getValue().trim();
            if (command.isEmpty()) {
                continue;
            }

            String[] parts = command.split("\\s+");
            String target = parts[0];
            if (target.startsWith("gradle:")) {
                continue;
            }

            String mainClass;
            if (looksLikeSourceFile(target)) {
                mainClass = mainClassFromSource(project, target);
                if (mainClass == null) {
                    project.getLogger().info(
                            "[jrun] script '{}': cannot resolve a main class from '{}', skipping",
                            scriptName, target);
                    continue;
                }
            } else {
                // 全限定类名，或默认包下的简单类名（如 Hello）
                mainClass = target;
            }

            String prefix = extension.getTaskPrefix().getOrElse("jrun");
            String taskName = prefix.isEmpty()
                    ? scriptName
                    : prefix + Character.toUpperCase(scriptName.charAt(0)) + scriptName.substring(1);
            if (INIT_TASK.equals(taskName)) {
                // 不能和 jrunInit 撞名
                continue;
            }

            List<String> args = parts.length > 1
                    ? List.of(parts).subList(1, parts.length)
                    : List.of();
            targets.add(new RunTarget(scriptName, taskName, mainClass, args));
        }
        return targets;
    }

    /**
     * 为每个可运行目标注册一个 Gradle 任务（{@code jrunHello} 之类）。
     *
     * <p>为什么要有这个：IntelliJ 的 Application 运行配置依赖 **module classpath**，
     * 一旦 IDE 没把 Gradle 依赖同步进 module，classpath 里就只有 {@code build/classes}，
     * 运行时会 {@code NoClassDefFoundError}。而 Gradle 任务用的是
     * {@code sourceSets.main.runtimeClasspath}，由 Gradle 自己解析，与 IDE 状态无关。
     */
    private void registerScriptTasks(Project project, List<RunTarget> targets) {
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        org.gradle.api.file.FileCollection runtimeClasspath =
                sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME).getRuntimeClasspath();

        for (RunTarget target : targets) {
            project.getTasks().register(target.taskName(), org.gradle.api.tasks.JavaExec.class, task -> {
                task.setGroup("jrun");
                task.setDescription("Run jrun script '" + target.scriptName()
                        + "' (" + target.mainClass() + ")");
                task.dependsOn("classes");
                task.setClasspath(runtimeClasspath);
                task.getMainClass().set(target.mainClass());
                task.setArgs(target.args());
            });
        }
    }

    private boolean looksLikeSourceFile(String target) {
        return target.endsWith(".java") || target.endsWith(".kt") || target.endsWith(".groovy");
    }

    /**
     * 从源文件推导主类名：读 {@code package} 声明 + 文件名。
     * Kotlin 的顶层 main 编译后是 {@code <File>Kt}，所以 .kt 要加 Kt 后缀。
     */
    private String mainClassFromSource(Project project, String target) {
        Path root = project.getProjectDir().toPath();
        Path file = root.resolve(target);
        if (!Files.isRegularFile(file)) {
            Path inMain = root.resolve("main").resolve(target);
            if (Files.isRegularFile(inMain)) {
                file = inMain;
            } else {
                Path mainDir = root.resolve("main");
                if (!Files.isDirectory(mainDir)) {
                    return null;
                }
                String fileName = Path.of(target).getFileName().toString();
                try (java.util.stream.Stream<Path> stream = Files.walk(mainDir)) {
                    file = stream.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().equals(fileName))
                            .findFirst()
                            .orElse(null);
                } catch (IOException e) {
                    return null;
                }
                if (file == null) {
                    return null;
                }
            }
        }

        String pkg = "";
        try {
            for (String line : Files.readAllLines(file)) {
                java.util.regex.Matcher matcher =
                        java.util.regex.Pattern.compile("^\\s*package\\s+([\\w.]+)")
                                .matcher(line);
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

    /**
     * 把 {@code jrunInit} 挂到 {@code wrapper} 与 {@code init} 任务的末尾。
     *
     * <p>这样 {@code gradle wrapper} / {@code gradle init} 之后运行器就已经就绪，
     * 不需要额外记一条命令。
     *
     * <p><b>注意</b>：{@code gradle init} 在**空目录**里执行时项目还没有构建脚本，
     * jrun 插件根本不会被应用，所以那种场景下挂载无法生效——只有在已经应用了
     * jrun 插件的项目里执行这两个命令才会自动触发。
     *
     * <p>这里用名字匹配而不是具体任务类型：{@code init} 属于 Gradle 内部
     * {@code org.gradle.buildinit} 包，不是公共 API，按类型引用会随版本失效。
     */
    private void hookStandardTasks(Project project, TaskProvider<Task> initTask) {
        project.getTasks().configureEach(task -> {
            String name = task.getName();
            if ("wrapper".equals(name) || "init".equals(name)) {
                task.finalizedBy(initTask);
            }
        });
    }

    /**
     * Gradle 版本守卫。
     *
     * <p>插件用到的 API 最低要求是 Gradle 8.14（已在 8.14.3 / 9.8.2 上实测）。
     * 更低的版本不直接报错——先给一条清晰警告，避免用户拿到莫名其妙的
     * NoSuchMethodError。
     */
    private void warnIfGradleTooOld(Project project) {
        String minimum = "8.14";
        try {
            org.gradle.util.GradleVersion current = org.gradle.util.GradleVersion.current();
            if (current.compareTo(org.gradle.util.GradleVersion.version(minimum)) < 0) {
                project.getLogger().warn(
                        "[jrun] Gradle {} is older than the tested baseline ({}). "
                                + "The plugin may misbehave; consider upgrading Gradle.",
                        current.getVersion(), minimum);
            }
        } catch (Throwable ignored) {
            // 连版本 API 都拿不到时静默跳过，不影响主流程
        }
    }

    /**
     * 项目一个仓库都没配时，自动补上 {@code mavenCentral()}，省去样板配置。
     *
     * <p>必须尊重 {@code settings.gradle.kts} 里的仓库策略：
     * <ul>
     *   <li>{@code FAIL_ON_PROJECT_REPOS} —— 往 project 加仓库会直接报错</li>
     *   <li>{@code PREFER_SETTINGS} —— settings 已经统一管理，加 project 仓库只会产生警告</li>
     * </ul>
     * 这两种情况下直接跳过。用户自己配了仓库时也不动。
     */
    private void configureRepositories(Project project) {
        if (!project.getRepositories().isEmpty()) {
            return;
        }
        try {
            project.getRepositories().mavenCentral();
        } catch (Exception e) {
            // settings.gradle.kts 里若用 FAIL_ON_PROJECT_REPOS 声明了仓库，这里不允许再加，
            // 直接跳过即可（那种项目本来就已经在 settings 里配好仓库了）。
            project.getLogger().debug(
                    "[jrun] skipped auto mavenCentral(): {}", e.getMessage());
        }
    }

    /**
     * 把 kotlin / groovy 的源码目录也扁平化到 src 与 test。
     *
     * <p>这里刻意不依赖 kotlin-gradle-plugin 的 API（那会让插件编译期就绑定 Kotlin 版本），
     * 而是通过 Gradle 自己的 {@link SourceDirectorySet} 接口访问 sourceSet 上的
     * {@code kotlin} / {@code groovy} 扩展。没装对应插件时 findByName 返回 null，
     * 直接跳过，天然兼容纯 Java 项目。
     */
    private void configureJvmLanguageSources(Project project) {
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        SourceSet main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        SourceSet test = sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME);

        applyLanguageDir(main, "kotlin", "main");
        applyLanguageDir(main, "groovy", "main");
        applyLanguageDir(main, "scala", "main");
        applyLanguageDir(test, "kotlin", "test");
        applyLanguageDir(test, "groovy", "test");
        applyLanguageDir(test, "scala", "test");
    }

    private void applyLanguageDir(SourceSet sourceSet, String extensionName, String dir) {
        Object ext = sourceSet.getExtensions().findByName(extensionName);
        if (ext instanceof SourceDirectorySet) {
            SourceDirectorySet dirs = (SourceDirectorySet) ext;
            dirs.setSrcDirs(java.util.List.of(dir));
            if ("groovy".equals(extensionName)) {
                // Groovy 插件的 joint compilation 会把源目录里的 .java 也编一遍，
                // 与 java sourceSet 的产物重复，导致 jar 报 "duplicate entry"。
                // Groovy 引用 Java 仍然可行：compileGroovy 的 classpath 含 compileJava 输出。
                dirs.exclude("**/*.java");
            }
        }
    }

    private void configureFlatLayout(Project project) {
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        SourceSet main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        main.getJava().setSrcDirs(java.util.List.of("main"));
        main.getResources().setSrcDirs(java.util.List.of("resources"));

        SourceSet test = sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME);
        test.getJava().setSrcDirs(java.util.List.of("test"));
        test.getResources().setSrcDirs(java.util.List.of("test-resources"));
    }

    private void configureCompilerAndTests(Project project) {
        project.getTasks().withType(JavaCompile.class).configureEach(task ->
                task.getOptions().setEncoding("UTF-8"));

        project.getTasks().withType(Test.class).configureEach(task -> {
            task.useJUnitPlatform();
            task.getTestLogging().setEvents(java.util.Set.of(
                    TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED));
            task.getTestLogging().setExceptionFormat(TestExceptionFormat.FULL);
        });
    }

    private TaskProvider<Task> registerInitTask(Project project, JrunExtension extension) {
        return project.getTasks().register(INIT_TASK, task -> {
            task.setGroup("jrun");
            task.setDescription("初始化 jrun：生成 java @argfile 与 scripts 元数据，供 ./jrun 使用");
            // classpath 里含 build/classes/java/main，必须先编译
            task.dependsOn("classes");

            task.doLast(t -> {
                try {
                    Path outDir = project.getLayout().getBuildDirectory()
                            .get().getAsFile().toPath().resolve(OUTPUT_DIR);
                    Files.createDirectories(outDir);

                    // argfile 里的 classpath 是平台相关的（Windows 用 D:\... + ";"，
                    // Linux/WSL 用 /mnt/d/... + ":"）。同一台机器上 Windows 与 WSL
                    // 共用 build/ 目录，必须按平台分目录，否则会互相覆盖。
                    Path argsDir = outDir.resolve(platformDir());

                    SourceSetContainer sourceSets =
                            project.getExtensions().getByType(SourceSetContainer.class);
                    String classpath = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
                            .getRuntimeClasspath().getAsPath();

                    int release = resolveRelease(project);

                    // argfile：把 classpath 作为一个整体参数，避免命令行长度与引号问题。
                    // 注意：Java @argfile 里反斜杠是转义字符，Windows 路径必须写成 \\
                    // 否则 "D:\idea\..." 会被吞成 "D:idea..."，导致 classpath 完全失效。
                    String escapedClasspath = classpath.replace("\\", "\\\\");
                    writeIfChanged(argsDir.resolve("class.args"),
                            "--class-path\n\"" + escapedClasspath + "\"\n");
                    writeIfChanged(argsDir.resolve("source.args"),
                            "--class-path\n\"" + escapedClasspath + "\"\n--source\n" + release + "\n");

                    // 下面两个与平台无关，放在通用目录
                    String defaultTarget = extension.getMainClass().getOrElse("");
                    writeIfChanged(outDir.resolve("default-target.txt"), defaultTarget + "\n");

                    // scripts 元数据：脚本运行期从 properties 读取（避免在 shell 里解析 JSON）
                    Map<String, String> scripts = new TreeMap<>(extension.getScripts().get());
                    StringBuilder props = new StringBuilder();
                    for (Map.Entry<String, String> e : scripts.entrySet()) {
                        props.append(e.getKey()).append('=').append(e.getValue()).append('\n');
                    }
                    writeIfChanged(outDir.resolve("scripts.properties"), props.toString());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        });
    }

    /**
     * argfile 的平台子目录名。
     *
     * <p>classpath 的内容与分隔符都是平台相关的：Windows 是 {@code D:\...} + ";",
     * Linux/WSL 是 {@code /mnt/d/...} + ":"。同一台机器上 Windows 侧与 WSL 侧共用
     * build/ 目录，若不分目录会互相覆盖，导致另一侧 java 无法打开或无法加载类。
     */
    static String platformDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? "win" : "unix";
    }

    private int resolveRelease(Project project) {
        // JavaPluginExtension#getToolchain() 是 Gradle 7.6 才有的 API。
        // 低版本上直接调用会抛 NoSuchMethodError（Error，不是 Exception），
        // 因此这里连带捕获并降级，避免插件在旧 Gradle 上直接崩掉。
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

    private void generateLaunchers(Project project, JrunExtension extension, List<RunTarget> targets) {
        try {
            Set<String> requested = extension.getLaunchers().get().stream()
                    .map(s -> s.toLowerCase(java.util.Locale.ROOT).trim())
                    .collect(java.util.stream.Collectors.toSet());

            boolean wantBash = requested.contains("bash") || requested.contains("sh");
            boolean wantCmd = requested.contains("cmd") || requested.contains("bat") || requested.contains("windows");

            // PowerShell 启动器已被移除：PowerShell 可以直接执行 jrun.bat，
            // 多维护一份 .ps1 只会带来行为漂移的风险（Gradle 本身也只有 gradlew / gradlew.bat）。
            if (requested.contains("powershell") || requested.contains("ps1") || requested.contains("pwsh")) {
                project.getLogger().warn(
                        "[jrun] The PowerShell launcher has been removed. PowerShell can run jrun.bat "
                                + "directly, so 'powershell' in jrun.launchers is ignored.");
            }

            // bash：LF、无 BOM；CMD：CRLF（CMD 的 goto/label 解析要求 CRLF）。
            // 未启用的平台会删掉此前生成的文件，避免"关了还在"的困惑。
            writeOrRemoveLauncher(project, wantBash, "jrun", "jrun.sh", false, false, true);
            writeOrRemoveLauncher(project, wantCmd, "jrun.bat", "jrun.bat", true, false, false);

            // 清理旧版本升级后遗留的文件（jrun.ps1 启动器、jrun.json 清单都已移除）
            Files.deleteIfExists(project.getProjectDir().toPath().resolve("jrun.ps1"));
            Files.deleteIfExists(project.getProjectDir().toPath().resolve("jrun.json"));

            generateIntellijRuns(project, extension, targets);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 为可运行目标生成 IntelliJ 运行配置（{@code .run/jrun - <name>.run.xml}）。
     *
     * <p>生成的是 **Gradle 类型**的配置，指向插件注册的 {@code jrun<Name>} 任务。
     * 这样做的好处：classpath 由 Gradle 解析，不受 IDE 模块同步状态影响
     * （用 Application 类型时会因为 module 里没有依赖而报 NoClassDefFoundError）。
     *
     * <p>它会让配置出现在 IDEA 的运行配置下拉框里，可以一键启动。
     * 注意这**不是**编辑器里 main 方法旁边那个绿色三角——那只能由独立 IDEA 插件提供。
     */
    private void generateIntellijRuns(Project project, JrunExtension extension,
                                      List<RunTarget> targets) throws IOException {
        Path runDir = project.getProjectDir().toPath().resolve(".run");
        String prefix = "jrun - ";
        String suffix = ".run.xml";

        if (!extension.getGenerateIntellijRuns().get()) {
            pruneIntellijRuns(runDir, prefix, suffix, Set.of());
            return;
        }

        Set<String> keep = new java.util.HashSet<>();
        for (RunTarget target : targets) {
            String fileName = prefix + target.scriptName() + suffix;
            keep.add(fileName);
            writeIfChanged(runDir.resolve(fileName),
                    renderGradleRunConfig("jrun " + target.scriptName(), target.taskName()));
        }

        pruneIntellijRuns(runDir, prefix, suffix, keep);
    }

    /** 渲染一个指向 Gradle 任务的运行配置（IDEA 的公开格式）。 */
    private String renderGradleRunConfig(String configName, String taskName) {
        return """
                <component name="ProjectRunConfigurationManager">
                  <configuration default="false" name="%s" type="GradleRunConfiguration" factoryName="Gradle" nameIsGenerated="true">
                    <ExternalSystemSettings>
                      <option name="externalProjectPath" value="$PROJECT_DIR$" />
                      <option name="externalSystemIdString" value="GRADLE" />
                      <option name="scriptParameters" value="" />
                      <option name="taskDescriptions">
                        <list />
                      </option>
                      <option name="taskNames">
                        <list>
                          <option value="%s" />
                        </list>
                      </option>
                      <option name="vmOptions" />
                    </ExternalSystemSettings>
                    <ExternalSystemDebugServerProcess>true</ExternalSystemDebugServerProcess>
                    <ExternalSystemReattachDebugProcess>true</ExternalSystemReattachDebugProcess>
                    <DebugAllEnabled>false</DebugAllEnabled>
                    <RunAsTest>false</RunAsTest>
                    <method v="2" />
                  </configuration>
                </component>
                """.formatted(escapeXml(configName), escapeXml(taskName));
    }

    /** 删掉本插件以前生成、但当前已不存在的运行配置（不改动用户自己的配置文件）。 */
    private void pruneIntellijRuns(Path runDir, String prefix, String suffix, Set<String> keep)
            throws IOException {
        if (!Files.isDirectory(runDir)) {
            return;
        }
        try (java.util.stream.Stream<Path> children = Files.list(runDir)) {
            for (Path child : children.collect(java.util.stream.Collectors.toList())) {
                String name = child.getFileName().toString();
                if (name.startsWith(prefix) && name.endsWith(suffix) && !keep.contains(name)) {
                    Files.deleteIfExists(child);
                }
            }
        }
    }

    private String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private void writeOrRemoveLauncher(Project project, boolean enabled, String targetName,
                                       String resourceName, boolean crlf, boolean bom,
                                       boolean executable) throws IOException {
        Path target = project.getProjectDir().toPath().resolve(targetName);
        if (!enabled) {
            Files.deleteIfExists(target);
            return;
        }
        writeLauncher(project, targetName, resourceName, crlf, bom, executable);
    }

    private void writeLauncher(Project project, String targetName, String resourceName,
                               boolean crlf, boolean bom, boolean executable) throws IOException {
        String content = readResource("/jrun/" + resourceName);
        content = content.replace("\r\n", "\n");

        // .bat 必须保持纯 ASCII：Windows 控制台默认代码页常常是 GBK/CP936，
        // UTF-8 中文会乱码，甚至破坏命令解析。这里做一道运行期防护。
        if (targetName.endsWith(".bat")) {
            for (int i = 0; i < content.length(); i++) {
                if (content.charAt(i) > 0x7F) {
                    project.getLogger().warn(
                            "[jrun] {} contains non-ASCII characters; a GBK console may garble them. "
                                    + "Keep .bat launcher templates ASCII-only.", resourceName);
                    break;
                }
            }
        }

        if (crlf) {
            content = content.replace("\n", "\r\n");
        }
        Path target = project.getProjectDir().toPath().resolve(targetName);
        writeIfChanged(target, content, bom);
        if (executable) {
            File file = target.toFile();
            if (!file.canExecute()) {
                file.setExecutable(true, false);
            }
        }
    }

    private String readResource(String path) throws IOException {
        try (InputStream in = JrunPlugin.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("插件资源缺失: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void writeIfChanged(Path path, String content) throws IOException {
        writeIfChanged(path, content, false);
    }

    private void writeIfChanged(Path path, String content, boolean bom) throws IOException {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        byte[] data = body;
        if (bom) {
            data = new byte[body.length + 3];
            data[0] = (byte) 0xEF;
            data[1] = (byte) 0xBB;
            data[2] = (byte) 0xBF;
            System.arraycopy(body, 0, data, 3, body.length);
        }
        if (Files.exists(path)) {
            byte[] existing = Files.readAllBytes(path);
            if (java.util.Arrays.equals(existing, data)) {
                return;
            }
        }
        Files.createDirectories(path.getParent());
        Files.write(path, data);
    }
}
