# jrun-gradle-plugin

让 Java 项目像 **uv / cargo / npm** 一样顺手：

- 源码直接放 `main/`、测试直接放 `test/`（不用 `src/main/java`）
- 自动生成 `./jrun`（POSIX）与 `./jrun.bat`（Windows），与 Gradle 的 `gradlew` / `gradlew.bat` 结构一致
- 支持类似 `package.json` 的 **scripts**：`./jrun dev`
- 单文件即写即跑（像 Jbang），但**依赖不进源码**，统一写在构建脚本里
- 原生支持 Spring Boot（classpath 模式）

---

## 1. 安装插件

**开发期推荐：composite build**（直接引用插件源码，改完立即生效，无需发布）：

`settings.gradle.kts`：

```kotlin
pluginManagement {
    includeBuild("../jrun-gradle-plugin")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

`build.gradle.kts` 里照常写 `id("com.archforge.jrun") version "0.1.0"`（版本号会被本地构建忽略）。

> **为什么不推荐 `mavenLocal()`**：它的实际仓库位置来自 `~/.m2/settings.xml` 的
> `localRepository`（本机是 `D:\java\repos`）。命令行 Gradle 能解析，
> 但 **IntelliJ 的 Gradle 集成常常解析不到**，表现为
> `Plugin [id: 'com.archforge.jrun', version: '0.1.0'] was not found`。
> `includeBuild` 完全绕开仓库解析，两种环境都稳定。

**发布到 Plugin Portal 之后**，把 `includeBuild` 一行删掉，改成标准形态：

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

## 2. 在项目里接入

`settings.gradle.kts`：

```kotlin
pluginManagement {
    // 开发期：引用本地插件源码；发布到 Portal 后删掉这行
    includeBuild("../jrun-gradle-plugin")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
rootProject.name = "your-project"
```

`build.gradle.kts`：

```kotlin
plugins {
    id("com.archforge.jrun") version "0.1.0"
    id("org.springframework.boot") version "4.1.2"   // 需要 Spring Boot 时才加
}

// 不需要再写 repositories { } —— 插件会在项目没有任何仓库时自动补 mavenCentral()
// （如果你自己在 settings 或 project 里配了仓库，插件不会动）

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

dependencies {
    // 依赖写在这里 —— 不写在 Java 源码里（这是与 Jbang //DEPS 的关键区别）
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

jrun {
    mainClass = "com.example.demo.HelloApplication"   // ./jrun 不带参数时运行它

    scripts.put("hello", "Hello.java")
    scripts.put("dev", "com.example.demo.HelloApplication")
    scripts.put("api", "com.example.demo.HelloApplication --server.port=8080")
    scripts.put("verify", "gradle:build")

    // 可选：启动器范围，默认 ["bash", "cmd"]，与 gradlew / gradlew.bat 一致
    //   Windows 上只有 jrun.bat 一个入口；从列表移除的平台，其文件会被删除。
    //   launchers = listOf("bash")   // 只要 POSIX 脚本
    //
    //   注意：不支持 PowerShell 启动器 —— PowerShell 可以直接执行 jrun.bat。

    // 可选：为 scripts 生成 IntelliJ 运行配置（.run/jrun - <name>.run.xml），默认关闭。
    // 生成后 IDEA 会列进运行配置下拉框，可一键启动；只覆盖 classpath 模式（a.b.C）。
    //   generateIntellijRuns = true
}
```

**接入后 `sourceSets`、编译编码、JUnit Platform、测试日志都由插件接管**，
构建脚本里不再需要重复配置。

## 3. 目录约定

```
your-project/
├── build.gradle.kts
├── jrun                 ← 插件生成（POSIX）
├── jrun.bat             ← 插件生成（Windows，唯一入口）
├── main/
│   ├── Hello.java              （默认包，直接放根下）
│   └── com/example/demo/       （有包名的按目录结构放）
│       ├── HelloApplication.java
│       └── GreetingController.java
└── test/
    └── HelloTest.java
```

`main/` 与 `test/` 直接作为 source root，所以**包名必须与子目录一致**：
`main/com/example/demo/App.java` ⇔ `package com.example.demo;`。

## 4. 使用

```bash
./jrun Hello.java                    # 单文件源码模式
./jrun com.example.demo.App          # classpath 模式（Spring Boot）
./jrun hello                         # 执行 scripts 里的 hello
./jrun dev                           # 执行 scripts 里的 dev
./jrun build                         # 内置：build / test / clean / classes / jar / check
./jrun init                          # 重新生成运行缓存 build/jrun/*（等价于 gradle jrunInit）
./jrun --rebuild Hello.java          # 强制刷新缓存后再运行
./jrun --help                        # 帮助 + 列出所有 scripts
```

Windows：

```bat
jrun.bat Hello.java
jrun.bat dev
```

> 在 PowerShell 里直接执行 `.\jrun.bat Hello.java` 即可——不需要单独的 `.ps1`
> （Gradle 同样只提供 `gradlew` / `gradlew.bat`）。

### `jrunInit` 会随 `wrapper` / `init` 自动执行

插件把 `jrunInit` 挂到了这两个标准任务的末尾：

```bash
gradle wrapper      # 生成 wrapper 后，自动把 jrun 运行缓存准备好
gradle init         # 同上（前提：项目已应用 jrun 插件）
```

> **限制**：`gradle init` 在**空目录**里执行时，项目还没有 `build.gradle.kts`，
> jrun 插件不会被应用，因此无法自动挂载——那种场景下需要先添加插件配置。
> `gradle wrapper` 是主要受益场景（clone 项目后重建 wrapper）。

### 在 IDEA 里一键运行 scripts

**先说清楚边界**：编辑器里 `main` 方法旁边那个绿色三角，是 IDEA 通过
`RunConfigurationProducer` 按代码模式识别出来的（Java main、JUnit 注解、
Spring Boot 注解各有自己的 producer）。Gradle 插件**无法注入**这个图标——
要做到 `scripts.put(...)` 旁边也出现绿色三角，必须单独开发一个 IntelliJ 插件
（解析 `build.gradle.kts` 的 PSI、识别 `scripts.put` 调用、注册自定义运行配置类型）。

**可用的替代（推荐）**：插件会为**每个可运行的 script** 注册一个 Gradle 任务
`jrun<Name>`，在 IDEA 右侧 Gradle 工具窗口里双击即可运行，
**classpath 由 Gradle 自己解析**，不受 IDE 同步状态影响。

| script 值 | 生成的任务 | 主类来源 |
| --- | --- | --- |
| `com.example.App` | `jrunDev` | 直接用类名 |
| `Hello`（默认包） | `jrunHello` | 直接用类名 |
| `Hello.java` | `jrunHello` | 读取源码的 `package` 声明推导 |
| `App.kt` | `jrunDev` | 推导为 `<File>Kt` |
| `gradle:build` | 不生成 | 直接跑那个 Gradle 任务即可 |

```bash
./gradlew jrunDev              # 等价于 ./jrun dev
./gradlew tasks --group jrun   # 查看所有生成的运行任务
```

任务名默认带 `jrun` 前缀。想去掉前缀（得到 `hello` 这种与手写 `tasks.register("hello")`
一致的命名）：

```kotlin
jrun {
    taskPrefix = ""
    scripts.put("hello", "Hello.java")   // → 任务名 hello
}
```

### 三种写法，按「简洁 ↔ 可控」取舍

**① 顶层一行（最简洁）** —— 走完整 jrun 机制（任务 + `./jrun` 入口 + `.run` 配置），
但**没有 gutter 三角**：

```kotlin
import com.archforge.jrun.script

plugins { id("com.archforge.jrun") version "0.1.0" }

script("hello3", "com.example.demo.HelloApplication")     // 顶层
```

也可以在配置块里写（两者等价）：

```kotlin
jrun {
    script("hello3", "com.example.demo.HelloApplication")  // 块内，无需 import
}
```

**② `scripts.put`（推荐，声明式）** —— 同上，但支持源码文件自动推导类名：

```kotlin
jrun {
    scripts.put("hello", "Hello.java")      // 自动推导为 Hello
    scripts.put("dev", "com.example.App")
}
```

**③ `tasks.register` + `jrunMain`（唯一有绿色三角）** —— 可叠加任意原生配置：

```kotlin
import com.archforge.jrun.jrunMain

tasks.register<JavaExec>("hello") {
    jrunMain("com.example.demo.HelloApplication")
    args("--server.port=8080")
    jvmArgs("-Xmx512m")
}
```

对比：

| | ① `script(...)` | ② `scripts.put` | ③ `tasks.register` |
| --- | --- | --- | --- |
| 代码量 | 1 行 | 1 行 | 3 行 |
| **IDEA gutter 绿色三角** | ❌ | ❌ | ✅ |
| 源码型自动推导类名 | ❌ | ✅ | ❌ |
| `./jrun <name>` 可用 | ✅ | ✅ | ❌ |
| 自动生成 `.run` 配置 | ✅ | ✅ | ❌ |
| 自定义 JVM 参数/环境变量 | ❌ | ❌ | ✅ |

> 想要编辑器里的三角，只能选 ③ —— IDEA 只认字面写出的 `tasks.register`。
> 需要 `./jrun` 命令行入口就用 ① 或 ②。两者可以并存，用
> `scripts.put("hello", "gradle:hello")` 把 ③ 的任务桥接回 `./jrun`。

**另一种可选**：开启 `generateIntellijRuns` 会生成 `.run/jrun - <name>.run.xml`
（`Application` 类型）。它会出现在运行配置下拉框，但**有个前提**——`
Application` 配置的 classpath 完全来自 IDEA 的 module 模型，如果 IDEA 还没把
Gradle 依赖同步进 module，classpath 里就只有 `build/classes`，运行时会报
`NoClassDefFoundError`。

> **排障**：如果你在 IDEA 里看到命令行是
> `-classpath ...\build\classes\java\test;...\build\classes\java\main`（没有依赖 jar），
> 说明 Gradle 依赖没同步——到 Gradle 面板点 **Reload All Gradle Projects** 即可。
> 或者干脆改用上面的 `jrunDev` Gradle 任务，它没有这个问题。

```kotlin
jrun {
    generateIntellijRuns = true
    scripts.put("dev", "com.example.App")
    scripts.put("api", "com.example.App --server.port=8080")
}
```

产出：

```
.run/
├── jrun - dev.run.xml     # Application 类型，MAIN_CLASS_NAME=com.example.App
└── jrun - api.run.xml     # 额外带上 PROGRAM_PARAMETERS=--server.port=8080
```

**局限**：只覆盖 classpath 模式（值形如 `a.b.C`）的 script。源码模式
（`Xxx.java`）和 `gradle:xxx` 无法用 Application 运行配置表达，因此不会生成。
关掉 `generateIntellijRuns` 后，这些由插件生成的文件会被自动清理。

### scripts 的三种命令形式

| 写法 | 含义 |
| --- | --- |
| `Hello.java` | 单文件源码模式运行该文件（自动在 `main/` 下查找） |
| `a.b.C` | classpath 模式运行主类（Spring Boot 用这个） |
| `gradle:build` | 调用 Gradle 任务 |

值后面可以追加固定参数，例如 `"api", "com.example.demo.App --server.port=8080"`，
运行时 `./jrun api` 等价于 `./jrun com.example.demo.App --server.port=8080`，
也可以再叠加临时参数：`./jrun api --server.port=9090`（后者生效）。

## 5. 两种运行模式的区别

| | 单文件源码模式 | classpath 模式 |
| --- | --- | --- |
| 触发条件 | 目标是 `*.java` | 目标是全限定类名 |
| 底层命令 | `java @source.args Foo.java` | `java @class.args a.b.C` |
| 是否产生 `.class` | 否（内存编译） | 是（先跑 Gradle `classes`） |
| 适用场景 | 单文件脚本、快速验证 | Spring Boot、需要组件扫描的框架 |

**为什么 Spring Boot 必须走 classpath 模式**：`@SpringBootApplication` 的组件扫描
依赖 classpath 上真实存在的 `.class` 资源文件；单文件源码模式只在内存里编译，
没有 `.class` 文件可扫，`@RestController` 会扫不到并返回 404。

## 6. 生成物说明

| 文件 | 位置 | 说明 |
| --- | --- | --- |
| `jrun` | 项目根 | POSIX 启动器（LF），默认生成 |
| `jrun.bat` | 项目根 | Windows CMD 启动器（CRLF + 纯 ASCII），**Windows 上唯一的入口**（PowerShell 也可直接执行它） |
| `jrun.json` | — | **已移除**：旧版本会生成 scripts 总览，运行期从不依赖它，升级后会自动清理 |
| `build/jrun/win/class.args` | build 下 | Windows 版 `java @argfile`：`--class-path "<完整classpath>"` |
| `build/jrun/win/source.args` | build 下 | 同上 + `--source <toolchain版本>` |
| `build/jrun/unix/class.args` | build 下 | Linux / macOS / WSL 版（classpath 用 `:` 分隔） |
| `build/jrun/unix/source.args` | build 下 | 同上 + `--source` |
| `build/jrun/scripts.properties` | build 下 | 运行时消费的 scripts（避免 shell 解析 JSON，与平台无关） |
| `build/jrun/default-target.txt` | build 下 | `mainClass` 的值 |

**为什么 argfile 要按平台分目录**：classpath 内容本身是平台相关的
（Windows 是 `D:\...` + `;`，Linux/WSL 是 `/mnt/d/...` + `:`）。同一台机器上
Windows 侧和 WSL 侧共用同一个 `build/` 目录，如果放在同一位置会互相覆盖，
表现就是另一侧的 java 报 `could not open .../source.args` 或加载不到类。

`build/` 下的文件由 `jrunInit` 任务生成，`clean` 后会消失，
`./jrun` 首次运行会自动重建。

## 7. 实现要点（踩过的坑）

这些是本插件刻意处理掉的跨平台陷阱：

1. **classpath 太长**：Spring Boot 的运行时 classpath 约 6 KB，超过 Windows
   命令行 8191 字符上限。改用 Java 的 `@argfile` 传递。
2. **argfile 里反斜杠是转义符**：Windows 路径 `D:\idea\...` 会被吞成 `D:idea\...`，
   必须在写 argfile 时把 `\` 转义成 `\\`。
3. **CMD 的 `%1` 在解析期展开**：`--server.port=8080` 这类含 `=` 的参数会被拆成
   `--server.port 8080`。改为用 `for /f` 拆词 + 延迟展开的变量中转。
4. **CMD 对 LF 换行的 `.cmd` 解析 goto/label 会出错**，且中文在 GBK 代码页下乱码：
   生成的 `.bat` 一律 CRLF + 纯 ASCII。
5. **（已移除）PowerShell 启动器**：早期版本会生成 `.ps1`，但踩到 PS 5.1 必须带
   UTF-8 BOM 才按 UTF-8 解码、且 `$ErrorActionPreference='Stop'` 会让 `java -version`
   的 stderr 变成终止性错误。最终判定不值得：PowerShell 直接执行 `jrun.bat` 即可，
   多一份脚本只会带来行为漂移。现在配置里写 `powershell` 只会得到一条警告。
6. **PATH 上的 `java` 可能是坏 shim**（如 Oracle javapath 指向已卸载的 JDK）：
   两个脚本都会对每个候选实际执行 `java -version` 校验，并把它回写为 `JAVA_HOME`
   供 Gradle 使用，避免 class file 版本不一致。

## 8. 已知限制

- **CMD 版没有可靠的文件时间比较**：改完 `build.gradle.kts` 里的依赖后，
  请执行一次 `jrun init` 或 `jrun --rebuild <target>`。bash 版会自动按 mtime 判断。
- **scripts 值里带空格**时按空格分词，暂不支持引号包裹的含空格参数。
- 插件本身用 `--release 17` 编译，要求 Gradle 运行在 JDK 17+。
- **`jrunMain(...)` 只接受主类名**。传 `gradle:xxx` 或 `Xxx.java` 会在配置阶段抛
  `InvalidUserDataException`（带正确写法提示），而不是等到运行时才报
  `ClassNotFoundException`。

## 8.1 Gradle / JDK 兼容性

| 组合 | 状态 |
| --- | --- |
| Gradle **9.8.2** + JDK **25** | ✅ 全量实测（27+ 用例 + 12 个示例项目） |
| Gradle **9.8.2** + JDK **21** | ✅ 实测 |
| Gradle **8.14.3** + JDK **21** | ✅ 实测 |
| Gradle < 8.14 | ⚠️ 未实测：插件会在应用时打印一条版本警告，行为不保证 |
| JDK 17 | ⚠️ 插件自身按 17 编译，理论上可用，未单独实测 |

**兼容性上做的三件事**：

1. **不引入运行时依赖**：`kotlin-dsl` 提供的 Kotlin 标准库是 `compileOnly`，
   插件 POM 里没有 `<dependencies>`，不会和目标项目/Gradle 自带的 Kotlin 版本冲突。
2. **旧 API 降级**：`JavaPluginExtension#getToolchain()`（Gradle 7.6+）用
   `catch (NoSuchMethodError | Exception)` 包裹，拿不到就退回当前 JVM 版本。
3. **版本守卫**：低于基线时给明确警告，而不是让用户撞上晦涩的 `NoSuchMethodError`。

CI 里保留了 `gradle-compat` 任务（`workflow_dispatch` 触发），跑
`-PgradleCompatVersion=8.14.3` 之类的组合。

### WSL / 双平台注意事项

如果同一个仓库既在 Windows 下也在 WSL 下使用：

1. **WSL 里必须有原生 Linux JDK**。WSL 的 PATH 常常能通过 interop 找到
   Windows 的 `java.exe`，它能启动但**读不了 `/mnt/d/...` 这类 Linux 路径**，
   表现为 `Error: could not open '/mnt/d/.../source.args'`。
   检查：`command -v java` 不应指向 `/mnt/c/...` 或以 `.exe` 结尾。
   没有的话：`sudo apt install openjdk-21-jdk`（版本需 >= toolchain）。
2. **首次在 WSL 下运行**会下载 Gradle 分发包到 Linux 侧的 `~/.gradle`（约 150MB），
   并重新编译到 `build/classes`（`.class` 本身跨平台，可共用）。
3. 两侧的 argfile 分别在 `build/jrun/win/` 与 `build/jrun/unix/`，互不干扰。
4. 若不想在 WSL 装 JDK，直接在 Windows 侧用 `jrun.bat` 或 Git Bash 的 `./jrun` 即可。

## 9. 开发调试

```bash
./gradlew build              # 编译 + 全部测试 + 校验插件元数据
./gradlew test               # 只跑测试（约 2 分钟，TestKit 会起真实 Gradle）
./gradlew validatePlugins    # 插件元数据门禁，必须无警告
./gradlew publishToMavenLocal
```

改完插件后，业务项目需要**重新生成脚本**才生效：

```bash
cd <业务项目>
./gradlew jrunInit            # 触发配置阶段，重写 ./jrun*
```

## 10. 测试

28 个自动化用例，分两层：

| 测试类 | 覆盖 |
| --- | --- |
| `JrunPluginFunctionalTest`（20） | 目录扁平化、**默认只生成 bash+cmd**、启动器收窄与**旧文件删除**、**ps1 已移除且遗留文件被清理**、**`gradle wrapper` 自动触发 jrunInit**、**自动补 mavenCentral**、**脚本任务覆盖类名/源码/默认包**、两份脚本的编码与行尾、幂等性、argfile 内容与反斜杠转义、scripts 排序、**IDEA 运行配置生成与 XML 转义**、groovy `exclude **/*.java`、Kotlin 源集、Spring Boot 共存、KTS 语法、空项目 |
| `JrunScriptFunctionalTest`（4） | 真实执行 `./jrun`：单文件模式、参数透传、**命名 script 的固定参数**、缺文件报错 |

两个用例是**明确针对已修复 bug 的回归**：

- `groovySourceSetExcludesJavaFiles` —— Groovy joint compilation 导致 jar duplicate entry
- `namedScriptKeepsFixedArguments` —— bash 数组切片语法错误导致固定参数被静默丢弃

测试项目的两个约定：用 Groovy DSL 写（KTS 编译慢，另有专门用例）；脚本测试先跑
`jrunInit` 以走缓存命中路径（TestKit 临时项目没有 `gradlew`）。

兼容性矩阵（可选，CI 用）：

```bash
./gradlew test -PgradleCompatVersion=8.14.3
```

## 11. 发布

```bash
# 1. 配置凭据（一次性，写入 ~/.gradle/gradle.properties）
#    gradle.publish.key=...
#    gradle.publish.secret=...
# 2. 发布
./gradlew publishPlugins
```

发布前的完整检查清单、仍建议补充的测试、版本策略见
[`docs/PUBLISHING.md`](docs/PUBLISHING.md)。

> ⚠️ `build.gradle.kts` 里的 `website` / `vcsUrl` 目前是**占位地址**，
> 首次发布前必须替换成真实地址。
