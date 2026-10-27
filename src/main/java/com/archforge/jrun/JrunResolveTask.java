package com.archforge.jrun;

import org.gradle.api.DefaultTask;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.api.tasks.options.Option;

/**
 * {@code ./jrun add} 的校验步骤：解析从声明配置派生出来的 classpath（例如 implementation →
 * compileClasspath / runtimeClasspath / test*Classpath），坐标写错或仓库里没有时直接失败。
 */
@UntrackedTask(because = "only verifies that the declared dependencies resolve")
public abstract class JrunResolveTask extends DefaultTask {

    private transient ConfigurationContainer configurations;

    @Option(option = "configuration", description = "Declared configuration to verify (default: implementation)")
    @Input
    public abstract Property<String> getConfiguration();

    void setConfigurations(ConfigurationContainer configurations) {
        this.configurations = configurations;
    }

    @TaskAction
    public void resolve() {
        Configuration declared = configurations.findByName(getConfiguration().get());
        if (declared == null) {
            throw new InvalidUserDataException("Unknown configuration '" + getConfiguration().get() + "'");
        }
        for (Configuration c : configurations) {
            boolean derived = c == declared || c.getName().endsWith("Classpath");
            if (c.isCanBeResolved() && derived && c.getHierarchy().contains(declared)) {
                c.getFiles();
            }
        }
    }
}
