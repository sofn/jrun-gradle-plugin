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
version = "0.1.0"

description = "Run Java like uv / cargo / npm scripts: flat src/test layout, generated launchers, named scripts."

repositories {
    mavenCentral()
    gradlePluginPortal()
}

java {
    // 用当前 JDK 编译，但产出 Java 17 字节码，让插件可运行在 Java 17+ 的 Gradle 上
    // （不强制 toolchain，避免要求本机必须安装某个特定 JDK）
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

    // 可选：-PgradleCompatVersion=8.14.3 让 TestKit 用指定 Gradle 版本跑，
    // 用于兼容性矩阵（CI 的 gradle-compat 任务）。不传则用当前 Gradle。
    providers.gradleProperty("gradleCompatVersion").orNull?.let { version ->
        systemProperty("jrun.gradleCompatVersion", version)
    }
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
