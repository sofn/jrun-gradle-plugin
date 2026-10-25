package com.archforge.jrun

import org.gradle.api.Project

/**
 * 顶层 DSL：一行声明一个 jrun script，等价于 `jrun { scripts.put(name, target) }`。
 *
 * 用法：
 * ```kotlin
 * import com.archforge.jrun.script
 *
 * plugins { id("com.archforge.jrun") version "0.1.0" }
 *
 * script("hello3", "com.example.demo.HelloApplication")
 * ```
 *
 * 声明后：
 * - 会注册对应的 Gradle 任务（默认 `jrunHello3`，可用 `jrun { taskPrefix = "" }` 去掉前缀）
 * - `./jrun hello3` 可用
 * - 会自动生成 IntelliJ 运行配置
 *
 * **注意**：这个写法**不会**在 IDEA 编辑器里显示 gutter 绿色三角——IDEA 只识别
 * 直接写出来的 `tasks.register(...)`。想要三角请用：
 * ```kotlin
 * tasks.register<JavaExec>("hello3") { jrunMain("com.example.demo.HelloApplication") }
 * ```
 *
 * 如果项目里已有同名的 `script` 函数导致歧义，可用全限定调用
 * `com.archforge.jrun.script("hello3", "...")`。
 */
fun Project.script(name: String, target: String) {
    val extension = extensions.getByType(JrunExtension::class.java)
    extension.scripts.put(name, target)
}
