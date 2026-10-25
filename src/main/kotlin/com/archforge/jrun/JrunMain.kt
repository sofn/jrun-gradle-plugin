package com.archforge.jrun

import org.gradle.api.InvalidUserDataException
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer

/**
 * 把一个普通的 [JavaExec] 任务配置成 jrun 的运行任务。
 *
 * 这是**在 build.gradle.kts 里写 `tasks.register`** 的场景下用的：IDEA 会为这种
 * 字面写出的 `tasks.register(...)` 调用显示 gutter 运行图标（点击即可运行），
 * 而插件 DSL（`jrun { script(...) }`）里的调用 IDEA 不认识，不会有图标。
 *
 * 用法：
 * ```kotlin
 * import com.archforge.jrun.jrunMain
 *
 * tasks.register<JavaExec>("hello") {
 *     jrunMain("com.example.demo.HelloApplication")
 *     // 需要时继续加：
 *     args("--server.port=8080")
 *     jvmArgs("-Xmx512m")
 * }
 * ```
 *
 * classpath 取自 `sourceSets.main.runtimeClasspath`，由 Gradle 解析，
 * 因此不受 IDE 模块同步状态影响。
 *
 * @param target 主类名（全限定名，或默认包下的简单类名）
 * @throws InvalidUserDataException target 是 jrun 的 script 语法而不是类名时
 */
fun JavaExec.jrunMain(target: String) {
    // 常见误用：把 jrun 的 script 语法当成主类名传进来。
    // 运行期才会暴露成 ClassNotFoundException，这里在配置阶段就给出明确指引。
    if (target.startsWith("gradle:")) {
        throw InvalidUserDataException(
            """
            jrunMain() expects a main class name, but got "$target".
            "gradle:<task>" is jrun's *script* syntax, not a class name.

            Pick one of these instead:
              // 1) 想运行 Gradle 任务：直接用原生写法
              tasks.register("verify2") { dependsOn("build") }

              // 2) 想让它同时能被 ./jrun 调用：走 script DSL
              jrun { script("verify2", "gradle:build") }
            """.trimIndent()
        )
    }
    if (target.endsWith(".java") || target.endsWith(".kt") || target.endsWith(".groovy")) {
        throw InvalidUserDataException(
            """
            jrunMain() expects a main class name, but got the source file "$target".
            Use the class name instead (e.g. jrunMain("com.example.App")),
            or declare it with the script DSL which derives the class name for you:
              jrun { script("hello", "$target") }
            """.trimIndent()
        )
    }

    group = "jrun"
    description = "Run $target (jrun)"
    dependsOn("classes")

    val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
    classpath = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME).runtimeClasspath
    mainClass.set(target)
}
