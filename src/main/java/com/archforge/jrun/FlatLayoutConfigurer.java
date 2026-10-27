package com.archforge.jrun;

import java.util.List;
import java.util.Set;

import org.gradle.api.Project;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.logging.TestExceptionFormat;
import org.gradle.api.tasks.testing.logging.TestLogEvent;

/** 扁平目录约定（main/、test/、resources/）以及编译、测试、仓库的默认值。 */
final class FlatLayoutConfigurer {

    private FlatLayoutConfigurer() {
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
    static void configureRepositories(Project project) {
        if (!project.getRepositories().isEmpty()) {
            return;
        }
        try {
            project.getRepositories().mavenCentral();
        } catch (Exception e) {
            // settings.gradle.kts 里若用 FAIL_ON_PROJECT_REPOS 声明了仓库，这里不允许再加，
            // 直接跳过即可（那种项目本来就已经在 settings 里配好仓库了）。
            project.getLogger().debug("[jrun] skipped auto mavenCentral(): {}", e.getMessage());
        }
    }

    /** main/、test/ 直接作为 source root；编译统一 UTF-8；测试统一 JUnit Platform 与日志格式。 */
    static void configure(Project project) {
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        SourceSet main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        main.getJava().setSrcDirs(List.of("main"));
        main.getResources().setSrcDirs(List.of("resources"));

        SourceSet test = sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME);
        test.getJava().setSrcDirs(List.of("test"));
        test.getResources().setSrcDirs(List.of("test-resources"));

        project.getTasks().withType(JavaCompile.class).configureEach(task ->
                task.getOptions().setEncoding("UTF-8"));

        project.getTasks().withType(Test.class).configureEach(task -> {
            task.useJUnitPlatform();
            task.getTestLogging().setEvents(Set.of(
                    TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED));
            task.getTestLogging().setExceptionFormat(TestExceptionFormat.FULL);
        });
    }

    /**
     * 把 kotlin / groovy / scala 的源码目录也扁平化到 main 与 test。
     *
     * <p>这里刻意不依赖 kotlin-gradle-plugin 的 API（那会让插件编译期就绑定 Kotlin 版本），
     * 而是通过 Gradle 自己的 {@link SourceDirectorySet} 接口访问 sourceSet 上的
     * {@code kotlin} / {@code groovy} 扩展。没装对应插件时 findByName 返回 null，
     * 直接跳过，天然兼容纯 Java 项目。
     */
    static void configureJvmLanguageSources(Project project) {
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        SourceSet main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        SourceSet test = sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME);

        for (String language : List.of("kotlin", "groovy", "scala")) {
            applyLanguageDir(main, language, "main");
            applyLanguageDir(test, language, "test");
        }
    }

    private static void applyLanguageDir(SourceSet sourceSet, String extensionName, String dir) {
        Object ext = sourceSet.getExtensions().findByName(extensionName);
        if (ext instanceof SourceDirectorySet dirs) {
            dirs.setSrcDirs(List.of(dir));
            if ("groovy".equals(extensionName)) {
                // Groovy 插件的 joint compilation 会把源目录里的 .java 也编一遍，
                // 与 java sourceSet 的产物重复，导致 jar 报 "duplicate entry"。
                // Groovy 引用 Java 仍然可行：compileGroovy 的 classpath 含 compileJava 输出。
                dirs.exclude("**/*.java");
            }
        }
    }
}
