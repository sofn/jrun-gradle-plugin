package com.archforge.jrun;

import javax.inject.Inject;

import org.gradle.api.Named;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * 块写法声明的 script：
 * <pre>
 * jrun {
 *     script("api", "com.example.App") {
 *         args("--server.port=8080", "--greeting=hello world")   // 数组参数，不按空格拆分
 *         dependsOn("codegen")                                    // 先按顺序执行这些 script
 *     }
 * }
 * </pre>
 */
public abstract class JrunScript implements Named {

    private final String name;

    @Inject
    public JrunScript(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    /** 目标：{@code a.b.C}、{@code Xxx.java}、{@code gradle:task}、{@code shell:命令}。 */
    public abstract Property<String> getTarget();

    /** 固定参数（只用于 Java 目标），每个元素就是一个参数，可以包含空格和引号。 */
    public abstract ListProperty<String> getArgs();

    /** 运行前先依次执行的其他 script。 */
    public abstract ListProperty<String> getDependsOn();

    public void args(String... values) {
        getArgs().addAll(values);
    }

    public void dependsOn(String... scripts) {
        getDependsOn().addAll(scripts);
    }
}
