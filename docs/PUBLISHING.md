# 发布到 Gradle Plugin Portal

本文分四部分：**现有测试覆盖**、**仍建议补充的测试**、**发布前的检查清单**、**发布 GitHub Release**。

---

## 一、现有测试覆盖（92 个用例，其中 6 个只在 Windows 上运行）

`./gradlew test` 热缓存下约 40 秒。TestKit 使用持久化目录 `~/.gradle/jrun-testkit`，
`clean` 之后不再重新下载插件（本地实测：`clean test` 从 6 分 41 秒降到 44 秒）。

| 测试类 | 类型 | 守的是什么 |
| --- | --- | --- |
| `ScriptResolverTest`（11） | 单元 | script 四种目标、字符串分词、数组参数、dependsOn 不存在 / 成环、名称与单行校验 |
| `BuildFileEditorTest`（10） | 单元 | `jrun add`：KTS / Groovy、跳过嵌套块与字符串注释、原地升级、CRLF、坐标与配置名防注入 |
| `InputsFingerprintTest`（5） | 单元 | 内容指纹的格式、顺序、构建根目录文件与 project 依赖（插件与启动器之间的协议） |
| `JrunVersionTest`（1） | 单元 | 版本资源与构建版本一致 |
| `JrunPluginFunctionalTest`（25） | TestKit | 目录扁平化、启动器编码与收窄、版本占位符、argfile、任务注册、IDEA 配置、Kotlin / Groovy / Spring Boot 共存 |
| `JrunScriptsDslFunctionalTest`（4） | TestKit | 块写法元数据、Gradle 任务依赖顺序、配置期成环报错 |
| `JrunCliFunctionalTest`（3） | TestKit | `jrunAdd`、`jrunResolve`（本地仓库，离线稳定）、多模块映射 |
| `JrunScriptFunctionalTest`（4） | bash | 单文件模式、参数透传、含 `=` 的固定参数 |
| `JrunLauncherFunctionalTest`（20） | bash | 内容哈希失效（删除 / mtime 回退 / 版本目录）、JVM 参数、shebang、数组参数、`shell:`、dependsOn、`add` 回滚、`init`、全局委托、`-m`、依赖模块的构建与失效 |
| `JrunInstallerTest`（3） | bash | 用 `jrunDist` 产物跑 `install.sh`、被篡改的资产被拒绝 |
| `JrunBatFunctionalTest`（6） | cmd | 同样的核心路径走 `jrun.bat`（`@EnabledOnOs(WINDOWS)`，CI 的 windows-latest 上运行） |

### 门禁

- `./gradlew validatePlugins`：Gradle 内置的插件元数据校验，必须无警告。
- `./gradlew build`：测试 + jar + 插件元数据 + Release 资产（`jrunDist`），全绿才允许发布。
- CI：`test`（3 个系统 × JDK 17/21/25）、`installer`（install.sh / install.ps1 真实安装冒烟）、
  `gradle-compat`（Gradle 8.14.3 / 9.0.0 / 9.8.2，每周一定时运行，也可手动触发）。

### 测试基础设施的约定

1. **用 Groovy DSL 写测试项目**：KTS 每次都要编译脚本，会让每个用例多 1~2s；KTS 语法另有用例覆盖。
2. **启动器测试先跑 `jrunInit`**：TestKit 的临时项目没有 gradlew。先准备好缓存，再放一个只记录参数的
   假 gradlew，用来断言启动器有没有、怎样调用 Gradle。

---

## 二、仍建议补充的测试（按优先级）

> 这些还没实现，是公开插件与「本地能跑」之间的差距。

### P0 — 发布前最好补上

| 测试 | 为什么重要 | 实现要点 |
| --- | --- | --- |
| **Gradle 8.14.3 兼容性** | 已手工验证可用，但没有自动化守护；插件若误用新 API 会静默 break | `runner.withGradleVersion("8.14.3")`，用 `@Tag("compat")` 隔离，CI 单独跑（首次要下载分发包） |
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
- [x] `LICENSE` 存在（MIT）
- [ ] `README.md` 含「安装 / 快速开始 / 已知限制」

### 2. 构建与测试

```bash
./gradlew clean build            # 测试 + 校验
./gradlew validatePlugins        # 元数据门禁，必须无警告
./gradlew publishToMavenLocal    # 确认 POM/模块元数据可发布
```

- [ ] 全部测试通过（Windows 专属用例以 CI 结果为准）
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
plugins { id("com.archforge.jrun") version "0.2.0" }
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
`plugins { id("com.archforge.jrun") version "0.2.0" }` 直接解析。

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

---

## 五、发布 GitHub Release（一键安装依赖它）

README 里的一键安装命令从 `releases/latest/download/` 下载资产，所以每个版本都要发 Release：

```bash
# 1. 改 build.gradle.kts 里的 version，提交并 push
# 2. 打标签并推送：release 工作流会校验标签与 version 一致，然后构建、测试、打包、发布
git tag v0.2.0
git push origin v0.2.0
# 3. 核对资产（9 个）
gh release view v0.2.0
```

资产：`jrun`、`jrun.bat`、`install.sh`、`install.ps1`、`gradlew`、`gradlew.bat`、`gradle-wrapper.jar`、
`gradle-wrapper.properties`、`SHA256SUMS`。本地预览：`./gradlew jrunDist && (cd build/dist && sha256sum -c SHA256SUMS)`。
