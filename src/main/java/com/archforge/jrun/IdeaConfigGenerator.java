package com.archforge.jrun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.gradle.api.Project;

/**
 * 为可运行 script 生成 IntelliJ 运行配置（{@code .run/jrun - <name>.run.xml}）。
 *
 * <p>生成的是 <b>Gradle 类型</b>的配置，指向插件注册的 {@code jrun<Name>} 任务：classpath 由 Gradle 解析，
 * 不受 IDE 模块同步状态影响（Application 类型会因为 module 里没有依赖而报 NoClassDefFoundError）。
 * 它会出现在运行配置下拉框里；但这<b>不是</b>编辑器里 main 方法旁的绿色三角——那只能由 IDEA 插件提供。
 */
final class IdeaConfigGenerator {

    private static final String PREFIX = "jrun - ";
    private static final String SUFFIX = ".run.xml";

    private IdeaConfigGenerator() {
    }

    static void generate(Project project, JrunExtension extension, List<ScriptResolver.ResolvedScript> scripts)
            throws IOException {
        Path runDir = project.getProjectDir().toPath().resolve(".run");
        if (!extension.getGenerateIntellijRuns().get()) {
            prune(runDir, Set.of());
            return;
        }
        Set<String> keep = new HashSet<>();
        for (ScriptResolver.ResolvedScript script : scripts) {
            if (!script.hasTask()) {
                continue;
            }
            String fileName = PREFIX + script.name() + SUFFIX;
            keep.add(fileName);
            FileWrites.writeIfChanged(runDir.resolve(fileName),
                    renderGradleRunConfig("jrun " + script.name(), script.taskName()));
        }
        prune(runDir, keep);
    }

    /** 渲染一个指向 Gradle 任务的运行配置（IDEA 的公开格式）。 */
    private static String renderGradleRunConfig(String configName, String taskName) {
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
    private static void prune(Path runDir, Set<String> keep) throws IOException {
        if (!Files.isDirectory(runDir)) {
            return;
        }
        try (Stream<Path> children = Files.list(runDir)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (name.startsWith(PREFIX) && name.endsWith(SUFFIX) && !keep.contains(name)) {
                    Files.deleteIfExists(child);
                }
            }
        }
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
