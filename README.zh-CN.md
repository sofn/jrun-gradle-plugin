# jrun

**让 Java 项目像 uv / cargo / npm 一样顺手**：源码放扁平的 `main/`、`test/`，自动生成 `./jrun` 启动器，
支持 npm 风格的 scripts，底层就是普通的 Gradle。

```kotlin
plugins { id("com.archforge.jrun") version "0.2.0" }
```

[English](README.md) · [与 Jbang / gradlew run 对比](docs/comparison.zh-CN.md)

### 安装 `jrun` 命令

```bash
# Linux / macOS（x86_64 与 arm64）
curl -fsSL https://github.com/sofn/jrun-gradle-plugin/releases/latest/download/install.sh | sh
```

```powershell
# Windows（PowerShell）
powershell -ExecutionPolicy ByPass -c "irm https://github.com/sofn/jrun-gradle-plugin/releases/latest/download/install.ps1 | iex"
```

```bash
jrun init my-app && cd my-app && ./jrun          # Hello from jrun
./jrun add com.google.code.gson:gson:2.11        # 改写 build.gradle.kts、解析校验，失败自动回滚
```

> 插件还没有发布到 Gradle Plugin Portal。在那之前，`jrun init` 生成的项目和上面的一行接入都需要
> 用 composite build 引用插件源码，见[发布前的用法](#发布前的用法)。

---

## 特点

- 源码放 `main/`、测试放 `test/`，不用 `src/main/java`。
- 自动生成 `./jrun`（POSIX）和 `jrun.bat`（Windows），与 `gradlew` / `gradlew.bat` 一一对应。
- `package.json` 风格的 scripts：`./jrun dev`，支持数组参数、`shell:` 命令和 `dependsOn`。
- 单文件即写即跑（像 Jbang，`./jrun Hello.java`），但**依赖不进源码**，统一写在 `dependencies { }`。
- 没有改动时 `./jrun` 完全不启动 Gradle，直接执行 `java @argfile`。
- 每个 script 自动生成 Gradle 任务和 IntelliJ 运行配置。

## 快速开始

`build.gradle.kts`

```kotlin
plugins {
    id("com.archforge.jrun") version "0.2.0"
    id("org.springframework.boot") version "4.1.2"   // 需要时再加
}

// 不需要写 repositories { }：项目一个仓库都没配时，插件会自动补 mavenCentral()（你自己配了就不动）

dependencies {
    // 依赖写在这里，而不是 Java 源码里（这是与 Jbang //DEPS 的关键区别）
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    implementation("org.springframework.boot:spring-boot-starter-web")
}

jrun {
    mainClass = "com.example.demo.HelloApplication"   // ./jrun 不带参数时运行它
    script("hello", "Hello.java")
    script("dev", "com.example.demo.HelloApplication")
    script("api", "com.example.demo.HelloApplication") {
        args("--server.port=8080", "--greeting=hello world")   // 一个元素就是一个参数
        dependsOn("codegen")
    }
    script("codegen", "shell:./tools/gen.sh")
    script("verify", "gradle:build")
}
```

`sourceSets`、UTF-8 编译、JUnit Platform、测试日志都由插件接管，不用重复配置。

## 目录约定

```
your-project/
├── build.gradle.kts
├── jrun                 ← 生成（POSIX）
├── jrun.bat             ← 生成（Windows）
├── main/                ← 主源码根
│   ├── Hello.java              （默认包，直接放根下）
│   └── com/example/demo/       （有包名的按目录结构放）
├── test/                ← 测试源码根
└── resources/           ← 主资源根（可选）
```

`main/` 与 `test/` 直接作为 source root，所以**包名必须与子目录一致**：
`main/com/example/demo/App.java` ⇔ `package com.example.demo;`。

## scripts

目标有四种形态：

| 目标 | 含义 |
| --- | --- |
| `Xxx.java` / `Xxx.kt` / `Xxx.groovy` | 源文件（Java 走单文件源码模式，其他语言先编译） |
| `a.b.C` 或 `C` | 运行时 classpath 上的主类（默认包也支持） |
| `gradle:任务 [参数]` | 调用 Gradle 任务 |
| `shell:命令` | 在项目根目录执行命令（`sh -c`；Windows 上是 `cmd /c`），额外参数变成 `$1…` |

三种声明方式：

| 写法 | `./jrun` | Gradle 任务 + `.run` 配置 | 数组参数 / `dependsOn` | IDEA 绿色三角 |
| --- | --- | --- | --- | --- |
| `scripts.put("api", "com.example.App --x=1")` / `script("api", "…")` | ✅ | ✅ | ❌（按空格分词） | ❌ |
| `script("api", "com.example.App") { args(…); dependsOn(…) }` | ✅ | ✅ | ✅ | ❌ |
| `tasks.register<JavaExec>("api") { jrunMain("com.example.App") }` | ❌ | ✅ | 用 `args()` | ✅ |

- 块写法在 Kotlin 构建脚本顶层也能用（`import com.archforge.jrun.script`），Groovy 里用闭包。
- `dependsOn` 的 script 按声明顺序先执行，任何一个失败就中止。引用不存在的 script 或者成环（`a -> b -> a`）
  在配置阶段直接报错。`shell:` 型 script 会生成 `Exec` 任务，所以 `./gradlew jrunApi` 也会先跑依赖。
- `args(...)` 只用于 Java 目标；`shell:` / `gradle:` 的参数直接写在命令里。
- IDEA 只给字面写出的 `tasks.register(...)` 加 gutter 图标，`jrunMain` 就是为这种写法准备的。

## 命令行

| 命令 | 作用 |
| --- | --- |
| `jrun init [目录]` | 新建项目：`settings.gradle.kts`、`build.gradle.kts`、`main/Main.java`、`.gitignore`、Gradle wrapper 和 `./jrun` |
| `./jrun` | 运行 `jrun { mainClass }` |
| `./jrun Hello.java [参数]` | 单文件源码模式（依次在当前目录、项目根、`main/` 下查找） |
| `./jrun a.b.C [参数]` | classpath 模式（Spring Boot、Quarkus、Micronaut 等） |
| `./jrun <script> [参数]` | 运行 script，额外参数追加在最后 |
| `./jrun add [--test \| --config <配置名>] <group:artifact[:version]>...` | 添加依赖、解析校验，失败回滚；已有同一个 `group:artifact` 时原地升级版本 |
| `./jrun -m <模块> <命令>` | 在子模块里执行命令（多模块构建） |
| `./jrun build \| test \| clean \| classes \| jar \| check` | 对应的 Gradle 任务 |
| `./jrun --rebuild <目标>` | 先刷新运行缓存再运行 |
| `./jrun init` | 在项目里执行时：刷新运行缓存（`gradle jrunInit`） |
| `./jrun --version` | 打印启动器版本 |

全局的 `jrun`（安装在 `~/.local/bin`）自己处理 `init`，其他命令交给最近项目自己的 `./jrun` 执行。
这样每个项目都固定自己的启动器版本，思路和 `gradlew` 一样。

## `./jrun` 为什么快

`jrunInit` 把启动器需要的东西都写到 `build/jrun/`：

- `<平台>/class.args`、`source.args`：带完整 classpath 的 `java @argfile`（不受命令行长度限制，也没有引号问题）。
- `<平台>/inputs.sha256`：`build.gradle(.kts)`、`settings.gradle(.kts)`、`gradle.properties`、
  `gradle/libs.versions.toml` 的内容哈希；子模块还包含构建根目录的这些文件。
- `<平台>/sources.sha256`：`main/`、`resources/` 下全部文件的内容哈希。

启动器重新计算哈希，只有内容变了才调用 Gradle。内容哈希能发现 mtime 发现不了的情况：删除或改名的文件、
时间戳被回退的文件（解压、`cp -p`）、版本目录的修改。

## shebang 脚本与 JVM 参数

```java
#!/usr/bin/env -S ./jrun
public class Hello {
    public static void main(String[] args) { System.out.println("hi " + String.join(" ", args)); }
}
```

`chmod +x hello.java && ./hello.java world`。安装了全局命令后，写成 `#!/usr/bin/env -S jrun` 可以在任意
目录执行。shebang 文件要放在 **`main/` 之外**：javac 不认 `#!`，Gradle 编译不了它（启动器会把它复制成
无扩展名的副本交给 JDK，JDK 对这种文件会跳过首行）。

JVM 参数依次取 `JAVA_OPTS`、`JRUN_OPTS`（后者优先）：
`JRUN_OPTS="-Xmx512m -Dspring.profiles.active=dev" ./jrun dev`。

## 多模块

根项目和需要运行的模块都应用插件：

```kotlin
// settings.gradle.kts
include("app", "lib")
```

```bash
./jrun -m app dev        # 在根目录
cd app && ./jrun dev     # 或者进入模块
```

每个模块有自己的启动器，会向上找到根目录的 `gradlew`。根项目写出 `build/jrun/modules.properties`
（项目路径与名字 → 目录）；查不到时 `-m a:b` 按约定对应目录 `a/b`。

## IDEA 集成

- **Gradle 工具窗口**：`jrun` 分组下有 `jrunInit` 和每个 script 对应的 `jrun<Name>` 任务。
- **运行配置下拉框**：`.run/jrun - <name>.run.xml`（Gradle 类型，classpath 总是由 Gradle 解析）。
  如果 IDEA 的 *Application* 配置报 `NoClassDefFoundError`，说明 IDE 没同步 Gradle 依赖，
  点 **Reload All Gradle Projects**，或者改用生成的配置。

## 生成物

| 文件 | 位置 | 说明 |
| --- | --- | --- |
| `jrun`、`jrun.bat` | 项目根 | 启动器（bash 为 LF；cmd 为 CRLF + 纯 ASCII） |
| `.run/jrun - <name>.run.xml` | 项目根 | IntelliJ 运行配置 |
| `build/jrun/{win,unix}/class.args`、`source.args` | build 下 | 带 classpath 的 `java @argfile`（按平台分目录：Windows 与 WSL 可能共用 `build/`） |
| `build/jrun/{win,unix}/inputs.sha256`、`sources.sha256` | build 下 | 运行缓存的内容指纹 |
| `build/jrun/scripts/<name>.properties`、`<name>.args` | build 下 | 每个 script 的元数据（kind / target / deps、Java 固定参数） |
| `build/jrun/scripts.properties` | build 下 | `./jrun --help` 展示的 script 一览 |
| `build/jrun/modules.properties` | 根项目 build 下 | `-m` 用的模块映射 |

`build/` 下的文件都由 `jrunInit` 生成，`clean` 之后下一次 `./jrun` 会自动重建。

## 配置项

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `mainClass` | 无 | `./jrun` 不带参数时运行它 |
| `scripts`（`MapProperty`）/ `script(name, target)` | 空 | 字符串写法的 script |
| `script(name, target) { args(); dependsOn() }` | – | 块写法的 script |
| `launchers` | `["bash", "cmd"]` | 生成哪些启动器，移除的会被删除。PowerShell 可以直接执行 `jrun.bat`，所以没有 `.ps1` 启动器 |
| `generateIntellijRuns` | `true` | 生成 `.run/*.run.xml` |
| `taskPrefix` | `"jrun"` | 生成任务名的前缀；置空则任务名等于 script 名 |

## 兼容性

| 组合 | 状态 |
| --- | --- |
| Gradle 9.8.2 + JDK 25 / 21 | ✅ 实测 |
| Gradle 8.14.3 + JDK 21 | ✅ 实测（CI 每周跑兼容性矩阵） |
| Gradle < 8.14 | ⚠️ 未实测，插件会打印警告 |
| Linux、macOS（x86_64 / arm64）、Windows（CMD、PowerShell、Git Bash）、WSL | ✅ |

插件没有运行时依赖（Kotlin 标准库是 `compileOnly`），按 Java 17 编译。

## 实现要点（踩过的坑）

1. **classpath 太长**：Spring Boot 运行时 classpath 约 6 KB，超过 Windows 命令行
   8191 字符上限。改用 Java 的 `@argfile`。
2. **argfile 里反斜杠是转义符**：`D:\idea\...` 会被吞成 `D:idea\...`，
   必须写成 `\\`。诡异之处：单文件模式不依赖 classpath，所以直到跑 Spring Boot
   才暴露成 `ClassNotFoundException`。
3. **CMD 的 `%1` 在解析期展开**：`--server.port=8080` 会被拆成
   `--server.port 8080`，参数静默丢失。改用 `for /f` 拆词 + 延迟展开变量中转；
   script 的固定参数则整体写进 `@argfile`，交给 java 自己解析。
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
9. **`find -newer` 的覆盖盲区**：删除文件、mtime 回退、版本目录变化都发现不了。
   改为内容哈希清单（见「`./jrun` 为什么快」）。
10. **gradlew 在 git 里会丢可执行位**：启动器统一用 `sh gradlew` 调用，多模块时向上查找根目录的 gradlew。

### WSL / 双平台

1. WSL 里必须有**原生 Linux JDK**。若 `command -v java` 指向 `/mnt/c/...` 的
   `java.exe`，它能启动但读不了 `/mnt/d/...`，报 `could not open ...source.args`。
2. 两侧 argfile 分别在 `build/jrun/win/` 与 `build/jrun/unix/`，互不干扰。
3. 不想装 JDK 就直接在 Windows 侧用 `jrun.bat` 或 Git Bash 的 `./jrun`。

## 已知限制

- `args(...)` 只用于 Java 目标。
- Windows 下，`shell:` 命令里的 `!` 会被 CMD 的延迟展开吞掉。
- `jrun.bat` 能发现已登记的构建输入的内容变化，但发现不了新增的构建文件，新增后执行一次 `jrun --rebuild`；
  源码由它每次调用的 `gradlew classes` 保持最新。
- shebang 脚本不能放在 `main/` 里（见上文）。
- `jrunInit`、`jrunAdd`、`jrunResolve` 暂不兼容 configuration cache。
- 用 `scripts.put` 写的字符串 script 按空格分词；参数里有空格时用块写法。

## 发布前的用法

用 composite build 引用本仓库的源码：

```kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("../jrun-gradle-plugin")
}
```

`JRUN_PLUGIN_DIR=../jrun-gradle-plugin jrun init demo` 会自动写好这一段。不推荐用 `mavenLocal()`：
它的实际路径来自 `~/.m2/settings.xml`，IntelliJ 的 Gradle 集成常常解析不到
（`Plugin [id: 'com.archforge.jrun'] was not found`）。

## 开发

```bash
./gradlew build              # 编译 + 全部测试 + validatePlugins
./gradlew test --tests '*JrunLauncherFunctionalTest*'
./gradlew jrunDist           # GitHub Release 资产，输出到 build/dist
```

| 测试类 | 类型 | 覆盖 |
| --- | --- | --- |
| `ScriptResolverTest`（11） | 单元 | script 类型、字符串分词、数组参数、dependsOn 校验与成环、名称 |
| `BuildFileEditorTest`（10） | 单元 | `jrun add` 的改写：KTS / Groovy、嵌套块、字符串与注释、升级、CRLF、防注入 |
| `InputsFingerprintTest`（3） | 单元 | 指纹格式、顺序、构建根目录文件 |
| `JrunVersionTest`（1） | 单元 | 版本资源 |
| `JrunPluginFunctionalTest`（25） | TestKit | 目录、启动器、argfile、任务、IDEA 配置、与 Kotlin / Groovy / Spring Boot 共存 |
| `JrunScriptsDslFunctionalTest`（4） | TestKit | 块写法元数据、Gradle 任务依赖、成环报错 |
| `JrunCliFunctionalTest`（3） | TestKit | `jrunAdd`、`jrunResolve`、模块映射 |
| `JrunScriptFunctionalTest`（4） | bash | 单文件模式、参数透传 |
| `JrunLauncherFunctionalTest`（19） | bash | 缓存失效、JVM 参数、shebang、scripts、`add`、`init`、委托、`-m` |
| `JrunInstallerTest`（3） | bash | 用打包产物跑 `install.sh`、篡改资产被拒绝 |
| `JrunBatFunctionalTest`（6） | cmd | 同样的路径走 `jrun.bat`（只在 Windows CI 上运行） |

测试共用一个持久化的 TestKit 目录（`~/.gradle/jrun-testkit`，CI 上会缓存）。发版：改 `version`，然后
`git tag v<version> && git push origin v<version>`，`release` 工作流会完成构建、测试，并发布带全部安装资产的
GitHub Release。详见 [`docs/PUBLISHING.md`](docs/PUBLISHING.md)。

## 许可

[MIT](LICENSE) © 2026 sofn
