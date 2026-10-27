# jrun、Jbang 与 `gradlew run` 对比

[English](comparison.md) · [README](../README.zh-CN.md)

jrun 是一个 Gradle 插件，加上它生成的启动器。它不替代 Gradle，而是让日常的循环（写一个文件、运行、加依赖、
再运行）用起来像 uv 或 cargo，同时构建仍然是普通的 Gradle 构建，IDE 和 CI 都能直接识别。

## 对比

| | jrun | Jbang | `gradlew run`（application 插件） | Maven `exec:java` |
| --- | --- | --- | --- | --- |
| 底层 | 标准 Gradle 插件 | 自带的解析与缓存 | Gradle | Maven |
| 依赖写在哪 | `build.gradle.kts` 的 `dependencies {}` | 源码里的 `//DEPS` 注释 | 构建脚本 | `pom.xml` |
| 目录 | 扁平 `main/`、`test/` | 任意位置的单文件 | `src/main/java` | `src/main/java` |
| 单文件即跑 | `./jrun Hello.java`（JDK 源码模式，不产生 `.class`） | `jbang Hello.java` | 不支持 | 不支持 |
| 命名命令 | `./jrun dev`（数组参数、`shell:`、dependsOn） | alias / catalog | 自定义任务 | profile / execution |
| 添加依赖 | `./jrun add g:a:v`（解析校验，失败自动回滚） | 编辑 `//DEPS` | 编辑构建脚本 | 编辑 pom |
| 新建项目 | `jrun init`（自带 Gradle wrapper） | `jbang init` | `gradle init` | `mvn archetype:generate` |
| 无改动时再次运行 | 不启动 Gradle，直接 `java @argfile` | 使用缓存的 jar | 每次都要经过 Gradle 配置阶段 | 每次都要启动 Maven |
| 需要扫描 classpath 的框架（Spring Boot 等） | classpath 模式 | 支持 | 支持 | 支持 |
| IDE | 标准 Gradle 导入 + 生成的运行配置 | IDE 插件 / `jbang edit` | 原生 | 原生 |
| 多模块 | 每个模块一个启动器，根目录用 `-m` | 不是它的目标 | 原生 | 原生 |

## 什么时候不要用 jrun

1. **大型多模块、多团队协作的仓库。** 扁平目录和生成的启动器是在 Gradle 之上多加的一层约定。到了这种规模，
   convention plugin 加标准目录更容易扩展；`-m` 能缓解，但 jrun 不是 monorepo 工具。
2. **团队需要官方文档里的标准路径。** 新人上手资料、合规要求，以及默认 `src/main/java` 的工具
   （部分静态分析、覆盖率、archetype 工具）配合标准目录最省心。
3. **要发布到 Maven Central 的库。** 能做到，但贡献者默认的是标准目录，这时原生 Gradle 更直观。
4. **不需要构建脚本的一次性单文件脚本**，并且你更希望把依赖写在源码里：Jbang 更合适。
5. **依赖 configuration cache 或 isolated projects 的构建。** `jrunInit`、`jrunAdd`、`jrunResolve` 目前还不兼容。

如果以上都不是你的情况——小型服务、工具、原型、一个人或小团队维护的 Spring Boot / Quarkus 应用——
jrun 能省掉大部分样板，同时不离开 Gradle。
