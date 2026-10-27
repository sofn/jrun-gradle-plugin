package com.archforge.jrun;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

/**
 * jrun 插件的用户配置。
 *
 * <pre>
 * jrun {
 *     // 可选：./jrun 不带参数时默认运行的主类
 *     mainClass = "com.example.demo.HelloApplication"
 *
 *     // 类似 package.json 的 scripts：./jrun dev 即执行对应命令（字符串按空格拆参数）
 *     scripts.put("dev", "com.example.demo.HelloApplication")
 *     scripts.put("hello", "Hello.java")
 *     scripts.put("verify", "gradle:build")
 *
 *     // 块写法：数组参数（可含空格）、依赖其他 script
 *     script("api", "com.example.demo.HelloApplication") {
 *         args("--server.port=8080", "--greeting=hello world")
 *         dependsOn("codegen")
 *     }
 *     script("codegen", "shell:./tools/gen.sh")
 * }
 * </pre>
 *
 * 目标取值支持四种形式：
 * <ul>
 *   <li>{@code Xxx.java} —— 单文件源码模式</li>
 *   <li>{@code a.b.C} —— classpath 模式（Spring Boot 应用用这种）</li>
 *   <li>{@code gradle:taskName} —— 调用某个 Gradle 任务</li>
 *   <li>{@code shell:命令} —— 在项目根目录用 sh（Windows 上是 cmd）执行一条命令</li>
 * </ul>
 */
public abstract class JrunExtension {

    /** 类似 package.json 的 scripts 表。 */
    public abstract MapProperty<String, String> getScripts();

    /**
     * 声明一个 script 的简写，等价于 {@code getScripts().put(name, target)}。
     *
     * <p>这样在 `jrun { }` 块内可以直接写：
     * <pre>
     * jrun {
     *     script("hello3", "com.example.App")
     * }
     * </pre>
     * 块外（build script 顶层）则用同名的 Project 扩展：
     * {@code com.archforge.jrun.script("hello3", "...")}。
     */
    public void script(String name, String target) {
        getScripts().put(name, target);
    }

    /** 块写法声明的 script（{@link #script(String, String, Action)}）。 */
    public abstract NamedDomainObjectContainer<JrunScript> getScriptDefinitions();

    /**
     * 块写法：支持数组参数与依赖。
     * <pre>
     * jrun {
     *     script("api", "com.example.App") { args("--greeting=hello world"); dependsOn("codegen") }
     * }
     * </pre>
     */
    public void script(String name, String target, Action<? super JrunScript> configure) {
        JrunScript script = getScriptDefinitions().maybeCreate(name);
        script.getTarget().set(target);
        configure.execute(script);
    }

    /** ./jrun 无参数时的默认主类（可选）。 */
    public abstract Property<String> getMainClass();

    /**
     * 要生成哪些启动器，默认 {@code ["bash", "cmd"]}，与 Gradle 的
     * {@code gradlew} + {@code gradlew.bat} 保持一致——Windows 上只有一个入口。
     *
     * <p>接受 {@code bash}（生成 {@code jrun}）与 {@code cmd}（生成 {@code jrun.bat}）。
     * 从列表里去掉的启动器，其已生成的文件会被**删除**，避免残留。
     *
     * <p><b>PowerShell 不受支持</b>：PowerShell 可以直接执行 {@code jrun.bat}，
     * 而 Gradle 本身也只提供 {@code gradlew} / {@code gradlew.bat}，没有必要多维护一份
     * {@code .ps1}。若配置中仍写了 {@code powershell}，插件会打一条警告并忽略它。
     */
    public abstract ListProperty<String> getLaunchers();

    /**
     * 是否为 scripts 生成 IntelliJ 运行配置（{@code .run/jrun - <name>.run.xml}），
     * 默认 **true**——开箱即可在 IDEA 的运行配置下拉框里一键启动。
     *
     * <p>生成的是 **Gradle 类型**的配置，指向插件注册的 {@code jrun<Name>} 任务，
     * 所以 classpath 由 Gradle 解析，不受 IDE 模块同步状态影响。
     *
     * <p>不需要时置 false；关闭后此前生成的文件会被自动清理。
     */
    public abstract Property<Boolean> getGenerateIntellijRuns();

    /**
     * 生成的 Gradle 任务名前缀，默认 {@code "jrun"}（如 {@code scripts.put("hello", ...)}
     * → {@code jrunHello}）。
     *
     * <p>设为空串时直接用 script 名（{@code hello}），与手写
     * {@code tasks.register("hello")} 的命名一致；但要注意避免和已有任务撞名。
     */
    public abstract Property<String> getTaskPrefix();
}
