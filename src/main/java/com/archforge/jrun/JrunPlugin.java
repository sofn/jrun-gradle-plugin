package com.archforge.jrun;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.util.GradleVersion;

/**
 * jrun Gradle 插件：扁平目录（main/、test/）、生成 ./jrun 与 jrun.bat 启动器、
 * package.json 风格的 scripts，以及对应的 Gradle 任务与 IDEA 运行配置。
 *
 * <p>本类只做编排；具体工作分别在 {@link FlatLayoutConfigurer}、{@link ScriptResolver}、
 * {@link LauncherGenerator}、{@link IdeaConfigGenerator}、{@link RuntimeMetadataWriter}。
 */
public class JrunPlugin implements Plugin<Project> {

    public static final String INIT_TASK = "jrunInit";
    static final String GROUP = "jrun";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("java");
        warnIfGradleTooOld(project);
        FlatLayoutConfigurer.configureRepositories(project);

        JrunExtension extension = project.getExtensions().create("jrun", JrunExtension.class);
        // 默认只生成 bash + cmd，与 gradlew / gradlew.bat 的结构一致
        extension.getLaunchers().convention(List.of("bash", "cmd"));
        extension.getGenerateIntellijRuns().convention(true);
        extension.getTaskPrefix().convention(GROUP);

        FlatLayoutConfigurer.configure(project);
        TaskProvider<Task> initTask = registerInitTask(project, extension);
        hookStandardTasks(project, initTask);

        // kotlin / groovy 的 sourceSet 扩展只有在它们自己被应用后才存在，所以放到 afterEvaluate
        project.afterEvaluate(p -> {
            FlatLayoutConfigurer.configureJvmLanguageSources(p);
            List<ScriptResolver.ResolvedScript> scripts =
                    new ScriptResolver(p.getProjectDir().toPath(), extension.getTaskPrefix().getOrElse(GROUP))
                            .resolve(extension.getScripts().get());
            registerScriptTasks(p, scripts);
            try {
                LauncherGenerator.generate(p, extension);
                IdeaConfigGenerator.generate(p, extension, scripts);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * 为每个可运行 script 注册 JavaExec 任务（{@code jrunHello} 之类）。
     *
     * <p>IntelliJ 的 Application 运行配置依赖 module classpath，IDE 没同步 Gradle 依赖时会
     * {@code NoClassDefFoundError}；Gradle 任务用 {@code sourceSets.main.runtimeClasspath}，与 IDE 状态无关。
     */
    private void registerScriptTasks(Project project, List<ScriptResolver.ResolvedScript> scripts) {
        FileCollection runtimeClasspath = project.getExtensions().getByType(SourceSetContainer.class)
                .getByName(SourceSet.MAIN_SOURCE_SET_NAME).getRuntimeClasspath();
        for (ScriptResolver.ResolvedScript script : scripts) {
            project.getTasks().register(script.taskName(), JavaExec.class, task -> {
                task.setGroup(GROUP);
                task.setDescription("Run jrun script '" + script.name() + "' (" + script.mainClass() + ")");
                task.dependsOn("classes");
                task.setClasspath(runtimeClasspath);
                task.getMainClass().set(script.mainClass());
                task.setArgs(script.args());
            });
        }
    }

    private TaskProvider<Task> registerInitTask(Project project, JrunExtension extension) {
        return project.getTasks().register(INIT_TASK, task -> {
            task.setGroup(GROUP);
            task.setDescription("初始化 jrun：生成 java @argfile 与 scripts 元数据，供 ./jrun 使用");
            // classpath 里含 build/classes/java/main，必须先编译
            task.dependsOn("classes");
            task.doLast(t -> {
                try {
                    RuntimeMetadataWriter.write(project, extension);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        });
    }

    /**
     * 把 {@code jrunInit} 挂到 {@code wrapper} 与 {@code init} 任务的末尾，
     * 这样 {@code gradle wrapper} 之后运行器就已经就绪。
     *
     * <p>{@code gradle init} 在空目录里执行时项目还没有构建脚本，插件根本不会被应用，挂载无法生效。
     * 用名字匹配而不是任务类型：{@code init} 属于 Gradle 内部包，不是公共 API，按类型引用会随版本失效。
     */
    private void hookStandardTasks(Project project, TaskProvider<Task> initTask) {
        project.getTasks().configureEach(task -> {
            if ("wrapper".equals(task.getName()) || "init".equals(task.getName())) {
                task.finalizedBy(initTask);
            }
        });
    }

    /**
     * Gradle 版本守卫：插件用到的 API 最低要求是 Gradle 8.14（已在 8.14.3 / 9.8.2 上实测）。
     * 更低的版本先给一条清晰警告，避免用户拿到莫名其妙的 NoSuchMethodError。
     */
    private void warnIfGradleTooOld(Project project) {
        String minimum = "8.14";
        try {
            GradleVersion current = GradleVersion.current();
            if (current.compareTo(GradleVersion.version(minimum)) < 0) {
                project.getLogger().warn(
                        "[jrun] Gradle {} is older than the tested baseline ({}). "
                                + "The plugin may misbehave; consider upgrading Gradle.",
                        current.getVersion(), minimum);
            }
        } catch (Throwable ignored) {
            // 连版本 API 都拿不到时静默跳过，不影响主流程
        }
    }
}
