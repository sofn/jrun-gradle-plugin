# jrun-gradle-plugin

让 Java 项目像 **uv / cargo / npm** 一样顺手。

- 源码放 `main/`、测试放 `test/`，不用 `src/main/java`
- 自动生成 `./jrun`（POSIX）与 `./jrun.bat`（Windows），与 `gradlew` / `gradlew.bat` 结构一致
- 支持 `package.json` 风格的 scripts：`./jrun dev`
- 单文件即写即跑（像 Jbang），但**依赖不进源码**，统一写在 `dependencies { }` 里
- 自动生成 Gradle 任务与 IDEA 运行配置，编辑器/面板里一键启动

> 仓库：<https://git.lesofn.com/sofn/jrun-gradle-plugin>

---

## 1. 快速开始

**开发期用 composite build**（直接引用插件源码，改完立即生效，无需发布）：

`settings.gradle.kts`

```kotlin
pluginManagement {
    includeBuild("../jrun-gradle-plugin")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
rootProject.name = "your-project"
```

`build.gradle.kts`

```kotlin
plugins {
    id("com.archforge.jrun") version "0.1.0"
    id("org.springframework.boot") version "4.1.2"   // 需要时再加
}

// 不需要写 repositories { } —— 项目没有任何仓库时插件会自动补 mavenCentral()
// （你自己配了仓库的话，插件不会动）

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

dependencies {
    // 依赖写在这里，而不是 Java 源码里（这是与 Jbang //DEPS 的关键区别）
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

jrun {
    mainClass = "com.example.demo.HelloApplication"   // ./jrun 不带参数时运行它
    script("hello", "Hello.java")
    script("dev", "com.example.demo.HelloApplication")
    script("api", "com.example.demo.HelloApplication --server.port=8080")
    script("verify", "gradle:build")
}
```

`sourceSets`、编译编码、JUnit Platform、测试日志都由插件接管，不用重复配置。

**为什么不推荐 `mavenLocal()`**：它的实际路径来自 `~/.m2/settings.xml` 的
`localRepository`。命令行 Gradle 能解析，但 **IntelliJ 的 Gradle 集成常常解析不到**，
表现为 `Plugin [id: 'com.archforge.jrun', version: '0.1.0'] was not found`。
`includeBuild` 绕开仓库解析，两种环境都稳定。

发布到 Plugin Portal 后，删掉 `includeBuild` 一行即可回到标准形态。

## 2. 目录约定

```
your-project/
├── build.gradle.kts
├── jrun                 ← 生成（POSIX）
├── jrun.bat             ← 生成（Windows，唯一入口）
├── main/                ← 主源码根
│   ├── Hello.java              （默认包，直接放根下）
│   └── com/example/demo/       （有包名的按目录结构放）
├── test/                ← 测试源码根
└── resources/           ← 主资源根（可选）
```

`main/` 与 `test/` 直接作为 source root，所以**包名必须与子目录一致**：
`main/com/example/demo/App.java` ⇔ `package com.example.demo;`。

## 3. 声明 script 的三种方式

| 写法 | IDEA 绿色三角 | `./jrun` 入口 | `.run` 配置 | 源码自动推导 | 自定义 JVM 参数 |
| --- | --- | --- | --- | --- | --- |
| ① `script(...)` | ❌ | ✅ | ✅ | 仅 `scripts.put` | ❌ |
| ② `scripts.put(...)` | ❌ | ✅ | ✅ | ✅ | ❌ |
| ③ `tasks.register + jrunMain` | ✅ | ❌ | ❌ | ❌ | ✅ |

**① 一行最简**（块内 / 顶层均可）

```kotlin
jrun {
    script("hello3", "com.example.App")
}
```
```kotlin
import com.archforge.jrun.script
script("hello3", "com.example.App")   // 顶层写法，需要 import
```

**② `scripts.put`**：多支持「源码文件自动推导类名」

```kotlin
jrun {
    scripts.put("hello", "Hello.java")   // 自动推导为 Hello
    scripts.put("app", "App.kt")         // 自动推导为 AppKt
}
```

**③ `tasks.register` + `jrunMain`**：唯一能拿到 gutter 绿色三角的写法

```kotlin
import com.archforge.jrun.jrunMain

tasks.register<JavaExec>("hello") {
    jrunMain("com.example.demo.HelloApplication")   // 自动配 classpath / mainClass
    args("--server.port=8080")
    jvmArgs("-Xmx512m")
}
```

> **为什么只有 ③ 有三角**：IDEA 只给 build script 里**字面写出的 `tasks.register(...)`**
> 加 gutter 运行图标。`script(...)` / `scripts.put(...)` 是扩展方法，IDEA 不认识。
> `tasks.named(...)` 也不行——它是查询 API，不是注册。

三种可以并存，需要时用 `scripts.put("hello", "gradle:hello")` 把 ③ 的任务桥接回 `./jrun`。

### script 值的三种形态

| 形态 | 含义 |
| --- | --- |
| `Xxx.java` / `Xxx.kt` | 单文件源码模式（`./jrun` 走内存编译；Gradle 任务则先编译再按类名启动） |
| `a.b.C` 或 `C` | classpath 模式（默认包也支持） |
| `gradle:task` | 调用 Gradle 任务，不注册 JavaExec |

值后面可以带固定参数（空格分隔），例如
`script("api", "com.example.App --server.port=8080")`。

## 4. 运行模式

| | 单文件源码模式 | classpath 模式 |
| --- | --- | --- |
| 触发条件 | 目标是 `*.java` | 目标是类名 |
| 底层命令 | `java @source.args Foo.java` | `java @class.args a.b.C` |
| 是否产生 `.class` | 否（内存编译） | 是（先跑 `classes`） |
| 适用场景 | 单文件脚本、快速验证 | Spring Boot、Quarkus、Micronaut 等需要组件扫描的框架 |

**为什么 Spring Boot 必须走 classpath 模式**：`@SpringBootApplication` 的组件扫描
依赖 classpath 上真实的 `.class` 资源。单文件源码模式只在内存里编译，没有 `.class`
可扫，`@RestController` 会扫不到、接口返回 404。

## 5. 生成物

| 文件 | 位置 | 说明 |
| --- | --- | --- |
| `jrun` | 项目根 | POSIX 启动器（LF），默认生成 |
| `jrun.bat` | 项目根 | Windows CMD 启动器（CRLF + 纯 ASCII），**Windows 唯一入口** |
| `build/jrun/{win,unix}/class.args` | build 下 | `java @argfile`：`--class-path "<完整classpath>"` |
| `build/jrun/{win,unix}/source.args` | build 下 | 同上 + `--source <toolchain版本>` |
| `build/jrun/scripts.properties` | build 下 | 运行期消费的 scripts（避免 shell 解析 JSON） |
| `build/jrun/default-target.txt` | build 下 | `mainClass` 的值 |
| `.run/jrun - <name>.run.xml` | 项目根 | IntelliJ 运行配置（Gradle 类型，指向生成的 `jrun<Name>` 任务） |

`build/` 下的文件由 `jrunInit` 生成，`clean` 后消失，`./jrun` 首次运行会自动重建。

**argfile 按平台分目录**：classpath 内容本身平台相关（Windows `D:\...` + `;`，
Linux/WSL `/mnt/d/...` + `:`）。同一台机器上两侧共用 `build/`，不分目录会互相覆盖。

## 6. 配置项

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `mainClass` | 无 | `./jrun` 不带参数时运行它 |
| `scripts`（`MapProperty`） | 空 | script 表；也可用 `script(name, target)` 简写 |
| `launchers`（`ListProperty`） | `["bash", "cmd"]` | 生成哪些启动器；移除的平台其文件会被删除。**不支持 PowerShell** |
| `generateIntellijRuns` | `true` | 生成 `.run/*.run.xml`（Gradle 类型，classpath 由 Gradle 保证） |
| `taskPrefix` | `"jrun"` | 生成任务名前缀；置空则任务名等于 script 名 |

`launchers` 接受别名：`bash`/`sh`、`cmd`/`bat`/`windows`。

## 7. IDEA 集成

- **Gradle 工具窗口**：`Jrun tasks` 分组下有 `jrunInit` 和每个 script 的 `jrun<Name>`，
  双击即可运行，classpath 由 Gradle 解析，不受 IDE 模块同步状态影响。
- **运行配置下拉框**：`.run/jrun - <name>.run.xml` 会自动加载，直接选。
- **编辑器 gutter 绿色三角**：只有第 3 节 ③ 那种 `tasks.register` 写法才有。

> 若运行时看到 `NoClassDefFoundError`，且命令行里 `-classpath` 只有
> `build/classes/...` 而没有依赖 jar，说明 IDEA 没同步 Gradle 依赖——
> 到 Gradle 面板点 **Reload All Gradle Projects**。
> 或者改用 `./gradlew jrun<Name>`，它不依赖 IDE 同步。

### `jrunInit` 会随 `wrapper` / `init` 自动执行

```bash
gradle wrapper      # 生成 wrapper 后自动初始化 jrun 运行缓存
gradle init         # 同上（前提：项目已应用 jrun 插件）
```

> `gradle init` 在**空目录**执行时还没有 `build.gradle.kts`，插件不会被应用，
> 因此无法自动挂载。`gradle wrapper` 是主要受益场景。

## 8. 兼容性

| 组合 | 状态 |
| --- | --- |
| Gradle **9.8.2** + JDK **25** | ✅ 全量实测（28 个用例 + 12 个示例项目） |
| Gradle **9.8.2** + JDK **21** | ✅ 实测 |
| Gradle **8.14.3** + JDK **21** | ✅ 实测 |
| Gradle < 8.14 | ⚠️ 未实测，插件会打印版本警告 |
| JDK 17 | ⚠️ 插件按 17 编译，未单独实测 |

兼容性上做的三件事：

1. **不引入运行时依赖**：Kotlin 标准库是 `compileOnly`，插件 POM 里没有
   `<dependencies>`，不会和目标项目的 Kotlin 版本冲突。
2. **旧 API 降级**：`JavaPluginExtension#getToolchain()`（Gradle 7.6+）用
   `catch (NoSuchMethodError | Exception)` 包裹——它抛的是 **Error 不是 Exception**。
3. **版本守卫**：低于基线时打警告，而不是让用户撞上晦涩的 `NoSuchMethodError`。

## 9. 实现要点（踩过的坑）

1. **classpath 太长**：Spring Boot 运行时 classpath 约 6 KB，超过 Windows 命令行
   8191 字符上限。改用 Java 的 `@argfile`。
2. **argfile 里反斜杠是转义符**：`D:\idea\...` 会被吞成 `D:idea\...`，
   必须写成 `\\`。诡异之处：单文件模式不依赖 classpath，所以直到跑 Spring Boot
   才暴露成 `ClassNotFoundException`。
3. **CMD 的 `%1` 在解析期展开**：`--server.port=8080` 会被拆成
   `--server.port 8080`，参数静默丢失。改用 `for /f` 拆词 + 延迟展开变量中转。
4. **CMD 对 LF 换行的 `.bat` 解析 goto/label 会出错**，且中文在 GBK 代码页下乱码：
   生成的 `.bat` 一律 CRLF + 纯 ASCII（插件里加了非 ASCII 告警）。
5. **Groovy joint compilation 会重复编译 `.java`**：与 java 源集产物重复，
   jar 打包报 duplicate entry。groovy 源集已 `exclude("**/*.java")`。
6. **Kotlin 顶层 main 编译成 `<File>Kt`**：`Main.kt` → `MainKt`，
   但 `object Main` → `Main`。脚本用「编译产物是否存在」判定，比猜后缀可靠。
7. **PATH 上的 `java` 可能是坏 shim**（如 Oracle javapath 指向已卸载的 JDK）：
   脚本会对每个候选实际执行 `java -version` 校验，并回写 `JAVA_HOME` 给 Gradle。
8. **PowerShell 启动器已移除**：早期维护过 `.ps1`，踩到 PS 5.1 必须带 UTF-8 BOM、
   且 `$ErrorActionPreference='Stop'` 会让 `java -version` 的 stderr 变成终止性错误。
   最终判定不值得——PowerShell 直接执行 `jrun.bat` 即可。配置里写 `powershell`
   只会得到一条警告。

### WSL / 双平台

1. WSL 里必须有**原生 Linux JDK**。若 `command -v java` 指向 `/mnt/c/...` 的
   `java.exe`，它能启动但读不了 `/mnt/d/...`，报 `could not open ...source.args`。
2. 两侧 argfile 分别在 `build/jrun/win/` 与 `build/jrun/unix/`，互不干扰。
3. 不想装 JDK 就直接在 Windows 侧用 `jrun.bat` 或 Git Bash 的 `./jrun`。

## 10. 已知限制

- **CMD 版没有可靠的文件时间比较**：改完依赖后执行一次 `jrun init` 或
  `jrun --rebuild <target>`。bash 版会自动按 mtime 判断。
- **script 值里带空格**时按空格分词，暂不支持引号包裹的含空格参数。
- **`jrunMain(...)` 只接受主类名**：传 `gradle:xxx` 或 `Xxx.java` 会在配置阶段抛
  `InvalidUserDataException`（带正确写法提示），而不是等到运行时才 `ClassNotFoundException`。
- 插件用 `--release 17` 编译，要求 Gradle 运行在 JDK 17+。
- **没有编辑器里的 gutter 绿色三角**（除非用第 3 节 ③ 的写法）——那是 IDEA 插件的能力。

## 11. 开发与发布

```bash
./gradlew build              # 编译 + 全部测试 + 校验插件元数据
./gradlew test               # 只跑测试（约 1 分钟，TestKit 起真实 Gradle）
./gradlew validatePlugins    # 插件元数据门禁，必须无警告
./gradlew publishToMavenLocal
```

28 个自动化用例，两层：

| 测试类 | 覆盖 |
| --- | --- |
| `JrunPluginFunctionalTest`（24） | 目录扁平化、启动器编码与收窄、任务注册（类名/源码/默认包）、`script` DSL、`jrunMain` 误用校验、argfile 转义、自动 mavenCentral、`.run` 配置、groovy exclude、Kotlin 源集、Spring Boot 共存、KTS 语法、空项目 |
| `JrunScriptFunctionalTest`（4） | 真实执行 `./jrun`：单文件模式、参数透传、命名 script 固定参数、缺文件报错 |

发布到 Plugin Portal：配置 `gradle.publish.key` / `gradle.publish.secret` 后
执行 `./gradlew publishPlugins`。完整检查清单见 [`docs/PUBLISHING.md`](docs/PUBLISHING.md)。
