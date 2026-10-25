# 发布到 Gradle Plugin Portal

本文分三部分：**现有测试覆盖**、**仍建议补充的测试**、**发布前的检查清单**。

---

## 一、现有测试覆盖（28 个用例，已全绿）

`./gradlew test` ≈ 1 分钟（TestKit 每个用例起一次真实 Gradle）。

### 插件行为（`JrunPluginFunctionalTest`，21 个）

| 用例 | 守的是什么 |
| --- | --- |
| `wrapperTaskAlsoRunsJrunInit` | `gradle wrapper` 结束后 `jrunInit` 自动执行，缓存就绪 |
| `flatLayoutIsApplied` | `main`/`test` 成为 source root，`resources` 同理 |
| `defaultLaunchersAreBashAndCmdOnly` | 默认只生成 `jrun` + `jrun.bat`，**永不生成 `jrun.ps1`**（Windows 单入口） |
| `powershellLauncherIsGoneAndIgnoredWithWarning` | 配置里写 `powershell` 只告警不生成；历史遗留的 `jrun.ps1` 会被清理 |
| `launchersCanBeNarrowedToBashOnly` | 收窄 `launchers` 后，被移除平台的**文件会被删除** |
| `launchersAreGeneratedWithCorrectEncodings` | 两份脚本；bash=LF 无 BOM，bat=**CRLF + 纯 ASCII** |
| `launcherGenerationIsIdempotent` | 内容不变就不重写（否则会破坏构建缓存 / 触发无谓重编译） |
| `prepareWritesArgfilesWithEscapedClasspath` | argfile 结构、classpath 加引号、Windows `\` 转义成 `\\`、`--source` ≥ 17 |
| `scriptsAndDefaultTargetAreWritten` | `scripts.properties` 按 key 排序且保留固定参数；`default-target.txt` |
| `scriptTasksRunWithGradleClasspath` | 生成的 Gradle 任务用 Gradle classpath 真实运行 script，固定参数传入 |
| `scriptTasksCoverSimpleClassNames` | `Hello.java` 与默认包类名 `Hello` 都能生成任务并运行；`gradle:` 型不生成 |
| `mavenCentralIsAddedWhenNoRepositoryDeclared` | 项目没配仓库时自动补 `mavenCentral()` |
| `intellijRunsAreGeneratedForClasspathScriptsOnly` | 只为 classpath 型 script 生成 `.run/*.run.xml`；参数进 `PROGRAM_PARAMETERS`；关闭后清理 |
| `specialCharactersAreEscapedInIntellijRuns` | script 值里的 `&` / `"` 在 XML 中正确转义 |
| `groovySourceSetExcludesJavaFiles` | **回归**：groovy 源集必须 `exclude **/*.java`，否则 jar 报 duplicate entry |
| `kotlinPluginFlattensKotlinSources` | Kotlin 源集扁平化到 `main`/`test`，且与 java 源集共存 |
| `springBootPluginCoexists` | 与 Spring Boot 插件同时应用不破坏配置模型 |
| `kotlinDslConfigurationWorks` | KTS 下 `jrun { scripts.put(...) }` 语法可用 |
| `emptyProjectStillPreparesArgfiles` | 无 `main/` 的空项目也能生成 argfile |
| `specialCharactersInScriptsAreEscapedInJson` | 脚本值里的引号/反斜杠在 JSON 中正确转义 |

### 脚本端到端（`JrunScriptFunctionalTest`，4 个）

真实执行项目根生成的 `jrun`（bash）。

| 用例 | 守的是什么 |
| --- | --- |
| `runsSingleJavaFile` | 单文件源码模式能跑，无参数时 `ARGC=0` |
| `passesArgumentsThrough` | 位置参数原样透传 |
| `namedScriptKeepsFixedArguments` | **回归**：命名 script 的固定参数不丢、含 `=` 的参数不被拆开 |
| `missingSourceFailsWithClearMessage` | 找不到源文件时非 0 退出 + 可读错误 |

### 门禁

- `./gradlew validatePlugins` —— Gradle 内置的插件元数据校验（任务标注、扩展注册等），已无警告。
- `./gradlew build` —— 测试 + jar + 插件元数据，全绿才允许发布。

### 测试基础设施的两个约定

1. **用 Groovy DSL 写测试项目**：KTS 每次都要编译脚本，会让每个用例多 1~2s；KTS 语法由 `kotlinDslConfigurationWorks` 单独覆盖。
2. **脚本测试先跑 `jrunInit`**：TestKit 的临时项目没有 `gradlew`，脚本若走「缓存失效 → 调 gradle」路径会失败。先准备好 argfile，脚本就走缓存命中路径直接 `exec java`。

---

## 二、仍建议补充的测试（按优先级）

> 这些还没实现，是公开插件与「本地能跑」之间的差距。

### P0 — 发布前最好补上

| 测试 | 为什么重要 | 实现要点 |
| --- | --- | --- |
| **Gradle 8.14.3 兼容性** | 已手工验证可用，但没有自动化守护；插件若误用新 API 会静默 break | `runner.withGradleVersion("8.14.3")`，用 `@Tag("compat")` 隔离，CI 单独跑（首次要下载分发包） |
| **`.bat` 端到端** | Windows 用户的主要入口；此前修过 `%1` 解析期 `=` 被拆、`findstr /q` 不可用等问题，都靠手工发现 | 用 `cmd /c jrun.bat Hello.java`；只在 Windows runner 上跑 |
| **路径含空格** | Windows 用户目录常带空格（`C:\Users\John Doe\...`），是 classpath 引号处理的高危点 | `@TempDir` 再嵌一层带空格的子目录 |

### P1 — 提高信心

| 测试 | 说明 |
| --- | --- |
| 中文 / 非 ASCII 路径 | Linux CI 上可用 `项目-测试/` 目录名；Windows 上更关键（编码） |
| 超长 classpath | 依赖 100+ jar 时 argfile 是否仍正确（Spring Boot 全家桶已接近） |
| 无 Java 环境 | 脚本应输出可读提示而不是 `command not found`；可用 `env -u JAVA_HOME PATH=/nonexistent` 模拟 |
| `--rebuild` 强制刷新 | 需要项目内有 wrapper，或让脚本支持 `JRUN_GRADLE` 环境变量注入 Gradle 可执行路径 |
| 重复应用插件 | `id 'com.archforge.jrun'` 应用两次不应报错 |
| 并发执行 | 两个 `jrun` 同时启动不应互相写坏 argfile（当前是"后写覆盖"，幂等所以多半安全，但值得验证） |

### P2 — 锦上添花

- 用 `buildSrc` / `includeBuild` 方式引入插件时的行为（composite build）。
- IDE 同步（`./gradlew classes` 之外的 IDEA 场景）不易自动化，保持手工冒烟。
- 插件配置缓存（configuration cache）兼容：跑 `./gradlew help --configuration-cache` 看是否有告警。

---

## 三、发布前检查清单

按顺序执行，全部 ✅ 才能 `publishPlugins`。

### 1. 元数据

- [x] `build.gradle.kts` 里 `gradlePlugin.website` / `vcsUrl` 已指向真实仓库
      （`https://git.lesofn.com/sofn/jrun-gradle-plugin`）
- [ ] `version` 是正式版（不要 `SNAPSHOT`）
- [ ] `group` 与插件 id 前缀一致（`com.archforge.jrun` ↔ id `com.archforge.jrun`）
- [ ] `tags` 不超过 20 个、都是小写短词（Portal 有格式要求）
- [ ] `LICENSE` 存在（Apache-2.0）
- [ ] `README.md` 含「安装 / 快速开始 / 已知限制」

### 2. 构建与测试

```bash
./gradlew clean build            # 测试 + 校验
./gradlew validatePlugins        # 元数据门禁，必须无警告
./gradlew publishToMavenLocal    # 确认 POM/模块元数据可发布
```

- [ ] 15 个测试全绿
- [ ] `validatePlugins` 无警告
- [ ] `publishToMavenLocal` 产物包含 `.jar` / `.pom` / `.module` / plugin marker

### 3. 真实项目冒烟（不要只信测试）

在至少一个真实项目里用**发布前的版本**跑一遍：

```kotlin
// settings.gradle.kts
pluginManagement { repositories { mavenLocal(); gradlePluginPortal() } }
```
```kotlin
// build.gradle.kts
plugins { id("com.archforge.jrun") version "0.1.0" }
```

- [ ] `./jrun init` 正常
- [ ] `./jrun <某文件>.java` 正常
- [ ] `./jrun <script>` 正常
- [ ] Windows 上用 `jrun.bat` 再跑一次

### 4. 凭据（一次性配置）

1. 注册 <https://plugins.gradle.org/> 账号
2. 在 **Profile → API Keys** 里获取 key / secret
3. 写入 `~/.gradle/gradle.properties`（**不要提交到仓库**）：

```properties
gradle.publish.key=xxxxxxxx
gradle.publish.secret=yyyyyyyy
```

> 注意：本机 `~/.gradle/gradle.properties` 里已有 `gradle.user.home` 等配置，追加即可，不要覆盖。

### 5. 发布

```bash
./gradlew publishPlugins
```

首次发布会处理 namespace 归属。发布后几分钟内可在
<https://plugins.gradle.org/plugin/com.archforge.jrun> 看到，并可通过
`plugins { id("com.archforge.jrun") version "0.1.0" }` 直接解析。

### 6. 发布后验证

在**干净环境**（清掉 `mavenLocal` 里该插件）验证从 Portal 解析：

```bash
# 临时把 mavenLocal 从 pluginManagement 里去掉，只留 gradlePluginPortal
./gradlew build
```

---

## 四、版本策略建议

| 变更类型 | 版本 |
| --- | --- |
| 生成的脚本文件格式变化、`jrun { }` DSL 破坏性改动 | major |
| 新增 scripts 能力、新增语言支持 | minor |
| 修 bug（如参数解析、编码） | patch |

**兼容性承诺**（建议写进 README）：
- 插件运行在 Gradle 8.14+ / JDK 17+
- 生成物（`jrun`、`jrun.bat`、`.run/*.run.xml`）属于实现细节，但文件名与命令行用法保持稳定
