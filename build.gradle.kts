import java.security.MessageDigest

plugins {
    `java-gradle-plugin`
    // 提供 Kotlin DSL 扩展函数（如 JavaExec.jrunMain(...)），
    // 让用户在 build.gradle.kts 里写 tasks.register 时不用手抄 classpath/mainClass
    `kotlin-dsl`
    `maven-publish`
    // 发布到 Gradle Plugin Portal：./gradlew publishPlugins
    id("com.gradle.plugin-publish") version "2.2.1"
}

group = "com.archforge.jrun"
version = "0.2.0"

description = "Run Java like uv / cargo / npm scripts: flat src/test layout, generated launchers, named scripts."

repositories {
    mavenCentral()
    gradlePluginPortal()
}

java {
    // 用当前 JDK 编译，但产出 Java 17 字节码，让插件可运行在 Java 17+ 的 Gradle 上
    // （不强制 toolchain，避免要求本机必须安装某个特定 JDK）
}

// 插件版本写进资源文件：生成的 jrun 启动器、jrun --version、jrun init 都从这里读
tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filesMatching("jrun/version.properties") {
        expand("version" to pluginVersion)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 17
}

// 与 compileJava 的 release 17 保持一致，避免 "Inconsistent JVM Target" 警告
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// javadoc 会随 publication 一起发布，这里关掉 doclint 的缺注释告警，
// 避免把「使用不提供注释的默认构造器」这类噪音带到发布日志里
tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Gradle TestKit：在临时目录里真实跑一次 Gradle 构建，验证插件行为
    testImplementation(gradleTestKit())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // TestKit 会再起 Gradle 进程，堆内存放宽一点，避免 OOM 导致误报
    maxHeapSize = "1g"
    systemProperty("jrun.expectedVersion", project.version.toString())

    // TestKit 默认把自己的 Gradle 用户目录放在 build/tmp 下：clean 后就没了，CI 也缓存不到，
    // 每个 job 都要重新下载 Kotlin / Spring Boot 插件（约 190MB），这是 CI 慢的主因。
    // 改到固定目录后，daemon 和依赖缓存可以在用例、fork 和多次构建之间复用（CI 用 actions/cache 缓存）。
    val testKitDir = providers.gradleProperty("jrunTestKitDir")
        .orElse(gradle.gradleUserHomeDir.resolve("jrun-testkit").path)
    systemProperty("jrun.testkit.dir", testKitDir.get())

    // 用例都是独立的临时项目，可以用 -PjrunTestParallelism=N 按方法并发执行（按类分 fork 没有效果：
    // 大部分用例集中在一个测试类里）。默认串行：每个并发用例都要拉起自己的冷 TestKit daemon，
    // 实测并发 3 个反而更慢（热缓存 36~39s，串行 30s）；提速主要靠上面的持久化 TestKit 目录
    val parallelism = providers.gradleProperty("jrunTestParallelism").map(String::toInt).orElse(1).get()
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.config.strategy", "fixed")
    systemProperty("junit.jupiter.execution.parallel.config.fixed.parallelism", parallelism.toString())
    // TestKit 的 build() 是阻塞调用，ForkJoinPool 会为阻塞线程补位，不封顶就会把所有用例同时跑起来
    systemProperty("junit.jupiter.execution.parallel.config.fixed.max-pool-size", parallelism.toString())

    // 可选：-PgradleCompatVersion=8.14.3 让 TestKit 用指定 Gradle 版本跑，
    // 用于兼容性矩阵（CI 的 gradle-compat 任务）。不传则用当前 Gradle。
    providers.gradleProperty("gradleCompatVersion").orNull?.let { version ->
        systemProperty("jrun.gradleCompatVersion", version)
    }
}

// GitHub Release 资产：全局启动器、安装脚本，以及 jrun init 用的 Gradle wrapper 模板
val jrunDist = tasks.register<Sync>("jrunDist") {
    group = "distribution"
    description = "Assembles the GitHub release assets into build/dist"
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    into(layout.buildDirectory.dir("dist"))
    from("src/main/resources/jrun") {
        include("jrun.sh", "jrun.bat")
        rename("jrun.sh", "jrun")
        filter { line: String -> line.replace("@JRUN_VERSION@", pluginVersion) }
    }
    from("installer")
    from("gradle/wrapper")
    from(".") { include("gradlew", "gradlew.bat") }
    doLast {
        val dist = destinationDir
        // CMD 的 goto/label 解析要求 CRLF（仓库里的模板是 LF）
        val bat = dist.resolve("jrun.bat")
        bat.writeText(bat.readText().replace("\r\n", "\n").replace("\n", "\r\n"))
        val sha256 = { file: File ->
            MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        }
        dist.resolve("SHA256SUMS").writeText(
            dist.listFiles()!!.filter { it.isFile && it.name != "SHA256SUMS" }.sortedBy { it.name }
                .joinToString("") { "${sha256(it)}  ${it.name}\n" }
        )
    }
}

tasks.test {
    dependsOn(jrunDist)
    systemProperty("jrun.dist.dir", layout.buildDirectory.dir("dist").get().asFile.path)
}

gradlePlugin {
    // 真实仓库地址（发布到 Plugin Portal 时这两项会被校验）
    website = "https://git.lesofn.com/sofn/jrun-gradle-plugin"
    vcsUrl = "https://git.lesofn.com/sofn/jrun-gradle-plugin.git"
    plugins {
        create("jrun") {
            id = "com.archforge.jrun"
            implementationClass = "com.archforge.jrun.JrunPlugin"
            displayName = "jrun"
            description = "Flat main/test layout, generated ./jrun launchers and package.json-like scripts"
            tags.set(
                listOf(
                    "java", "kotlin", "groovy", "runner", "scripts",
                    "developer-experience", "spring-boot", "cli"
                )
            )
        }
    }
}

// 协议信息会进入 POM，Plugin Portal 页面也会展示
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/licenses/MIT")
                }
            }
        }
    }
}
