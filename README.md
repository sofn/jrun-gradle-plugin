# jrun

**Run Java like uv / cargo / npm** — flat `main/` + `test/` sources, generated `./jrun` launchers and
npm-style scripts, on top of plain Gradle.

```kotlin
plugins { id("com.archforge.jrun") version "0.2.0" }
```

[中文文档](README.zh-CN.md) · [jrun vs Jbang vs gradlew run](docs/comparison.md)

### Install the `jrun` command

```bash
# Linux / macOS (x86_64 and arm64)
curl -fsSL https://github.com/sofn/jrun-gradle-plugin/releases/latest/download/install.sh | sh
```

```powershell
# Windows (PowerShell)
powershell -ExecutionPolicy ByPass -c "irm https://github.com/sofn/jrun-gradle-plugin/releases/latest/download/install.ps1 | iex"
```

```bash
jrun init my-app && cd my-app && ./jrun          # Hello from jrun
./jrun add com.google.code.gson:gson:2.11        # edits build.gradle.kts, resolves, rolls back on failure
```

> The plugin is not on the Gradle Plugin Portal yet. Until it is, `jrun init` and the snippet above need the
> composite build described in [Using it before the Portal release](#using-it-before-the-portal-release).

---

## Why

- Sources live in `main/` and `test/` — no `src/main/java`.
- `./jrun` (POSIX) and `jrun.bat` (Windows) are generated next to `gradlew` / `gradlew.bat`.
- `package.json`-style scripts: `./jrun dev`, with array arguments, `shell:` commands and `dependsOn`.
- Single files run instantly like Jbang (`./jrun Hello.java`), but **dependencies stay in `dependencies { }`**,
  not in the source.
- When nothing changed, `./jrun` does not start Gradle at all: it runs `java @argfile` directly.
- Gradle tasks and IntelliJ run configurations are generated for every script.

## Quick start

`build.gradle.kts`

```kotlin
plugins {
    id("com.archforge.jrun") version "0.2.0"
    id("org.springframework.boot") version "4.1.2"   // only if you need it
}

// No repositories { } needed: when the project declares none, the plugin adds mavenCentral()
// (it never touches repositories you configured yourself).

dependencies {
    // Dependencies live here, not in the Java source (the key difference to Jbang's //DEPS)
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    implementation("org.springframework.boot:spring-boot-starter-web")
}

jrun {
    mainClass = "com.example.demo.HelloApplication"   // what ./jrun runs without arguments
    script("hello", "Hello.java")
    script("dev", "com.example.demo.HelloApplication")
    script("api", "com.example.demo.HelloApplication") {
        args("--server.port=8080", "--greeting=hello world")   // one element = one argument
        dependsOn("codegen")
    }
    script("codegen", "shell:./tools/gen.sh")
    script("verify", "gradle:build")
}
```

`sourceSets`, UTF-8 compilation, the JUnit Platform and test logging are configured by the plugin.

## Project layout

```
your-project/
├── build.gradle.kts
├── jrun                 ← generated (POSIX)
├── jrun.bat             ← generated (Windows)
├── main/                ← main sources
│   ├── Hello.java              (default package at the root)
│   └── com/example/demo/       (packages as directories)
├── test/                ← test sources
└── resources/           ← main resources (optional)
```

`main/` and `test/` are source roots, so the **package must match the directory**:
`main/com/example/demo/App.java` ⇔ `package com.example.demo;`.

## Scripts

Targets come in four shapes:

| Target | Meaning |
| --- | --- |
| `Xxx.java` / `Xxx.kt` / `Xxx.groovy` | source file (Java runs in single-file source mode, the others are compiled first) |
| `a.b.C` or `C` | main class on the runtime classpath (default package works) |
| `gradle:task [args]` | run a Gradle task |
| `shell:command` | run a command in the project root (`sh -c`; `cmd /c` on Windows), extra arguments become `$1…` |

Three ways to declare them:

| Style | `./jrun` | Gradle task + `.run` config | Array args / `dependsOn` | IDEA gutter icon |
| --- | --- | --- | --- | --- |
| `scripts.put("api", "com.example.App --x=1")` / `script("api", "…")` | ✅ | ✅ | ❌ (split on whitespace) | ❌ |
| `script("api", "com.example.App") { args(…); dependsOn(…) }` | ✅ | ✅ | ✅ | ❌ |
| `tasks.register<JavaExec>("api") { jrunMain("com.example.App") }` | ❌ | ✅ | via `args()` | ✅ |

- `script(name, target) { }` also works at the top level of a Kotlin build script after
  `import com.archforge.jrun.script`, and as a Groovy closure.
- `dependsOn` scripts run first, in declaration order; the first failure stops the chain. Unknown scripts and
  cycles (`a -> b -> a`) fail at configuration time. `shell:` scripts get an `Exec` task, so `dependsOn`
  also works through Gradle (`./gradlew jrunApi`).
- `args(...)` is only for Java targets; put the arguments of `shell:` / `gradle:` targets into the command.
- IDEA only shows gutter icons for literal `tasks.register(...)` calls — that is what `jrunMain` is for.

## Command line

| Command | What it does |
| --- | --- |
| `jrun init [dir]` | create a project: `settings.gradle.kts`, `build.gradle.kts`, `main/Main.java`, `.gitignore`, the Gradle wrapper and `./jrun` |
| `./jrun` | run `jrun { mainClass }` |
| `./jrun Hello.java [args]` | single-file source mode (resolved from the current directory, the project root or `main/`) |
| `./jrun a.b.C [args]` | classpath mode — Spring Boot, Quarkus, Micronaut … |
| `./jrun <script> [args]` | run a script; extra arguments are appended |
| `./jrun add [--test \| --config <name>] <group:artifact[:version]>...` | add dependencies, resolve them, roll back on failure; an existing `group:artifact` is upgraded in place |
| `./jrun -m <module> <command>` | run a command in a sub-module (multi-module builds) |
| `./jrun build \| test \| clean \| classes \| jar \| check` | the matching Gradle task |
| `./jrun --rebuild <target>` | refresh the runtime cache first |
| `./jrun init` | inside a project: refresh the runtime cache (`gradle jrunInit`) |
| `./jrun --version` | print the launcher version |

The global `jrun` (installed into `~/.local/bin`) runs `init` itself and otherwise hands over to the nearest
project's own `./jrun`, so each project keeps its own launcher version — the same idea as `gradlew`.

## How `./jrun` stays fast

`jrunInit` writes everything the launcher needs into `build/jrun/`:

- `<platform>/class.args` and `source.args` — `java @argfile`s with the full classpath (no command-line length
  limits, no quoting problems).
- `<platform>/inputs.sha256` — content hashes of `build.gradle(.kts)`, `settings.gradle(.kts)`,
  `gradle.properties` and `gradle/libs.versions.toml`, including the build root's files for sub-modules.
- `<platform>/sources.sha256` — content hashes of everything in `main/` and `resources/`.
- In multi-module builds both manifests also cover the projects on the runtime classpath (their build files and
  source directories), and `jrunInit` builds their jars first.

The launcher recomputes the hashes and only calls Gradle when something changed. Content hashes catch what
mtimes miss: deleted or renamed files, files restored with an older timestamp, version catalog edits.

## Shebang scripts and JVM options

```java
#!/usr/bin/env -S ./jrun
public class Hello {
    public static void main(String[] args) { System.out.println("hi " + String.join(" ", args)); }
}
```

`chmod +x hello.java && ./hello.java world` — with the global command installed, `#!/usr/bin/env -S jrun`
works from any directory. Keep shebang files **outside `main/`**: javac rejects `#!`, so Gradle cannot compile
them (the launcher hands the JDK an extensionless copy, for which the JDK skips the first line).

JVM options come from `JAVA_OPTS`, then `JRUN_OPTS` (the later one wins):
`JRUN_OPTS="-Xmx512m -Dspring.profiles.active=dev" ./jrun dev`.

## Multi-module builds

Apply the plugin to the root and to every module you want to run:

```kotlin
// settings.gradle.kts
include("app", "lib")
```

```bash
./jrun -m app dev        # from the root
cd app && ./jrun dev     # or inside the module
```

Every module gets its own launcher, which finds the root `gradlew`. The root writes `build/jrun/modules.properties`
(project path and name → directory); `-m a:b` falls back to the directory `a/b`. Modules may depend on plain
`java-library` projects (`implementation(project(":lib"))`): an edit in `lib` triggers a rebuild on the next run.

## IDE integration

- **Gradle tool window**: the `jrun` group lists `jrunInit` and one `jrun<Name>` task per script.
- **Run configuration drop-down**: `.run/jrun - <name>.run.xml` (Gradle type, so the classpath always comes from
  Gradle). If you see `NoClassDefFoundError` from an IDEA *Application* configuration, the IDE has not synced the
  Gradle dependencies — use **Reload All Gradle Projects** or the generated configuration.

## Generated files

| File | Where | Notes |
| --- | --- | --- |
| `jrun`, `jrun.bat` | project root | launchers (bash: LF; cmd: CRLF + ASCII only) |
| `.run/jrun - <name>.run.xml` | project root | IntelliJ run configurations |
| `build/jrun/{win,unix}/class.args`, `source.args` | build | `java @argfile` with the classpath (per platform: Windows and WSL can share `build/`) |
| `build/jrun/{win,unix}/inputs.sha256`, `sources.sha256` | build | content fingerprints of the runtime cache |
| `build/jrun/scripts/<name>.properties`, `<name>.args` | build | per-script metadata (kind/target/deps, fixed Java arguments) |
| `build/jrun/scripts.properties` | build | script overview for `./jrun --help` |
| `build/jrun/modules.properties` | root build | module map for `-m` |

Everything in `build/` is recreated by `jrunInit`; after `clean` the next `./jrun` rebuilds it.

## Configuration

| Setting | Default | Meaning |
| --- | --- | --- |
| `mainClass` | – | what `./jrun` runs without arguments |
| `scripts` (`MapProperty`) / `script(name, target)` | empty | string scripts |
| `script(name, target) { args(); dependsOn() }` | – | block scripts |
| `launchers` | `["bash", "cmd"]` | which launchers to generate; removed ones are deleted. PowerShell can run `jrun.bat`, so there is no `.ps1` launcher |
| `generateIntellijRuns` | `true` | generate `.run/*.run.xml` |
| `taskPrefix` | `"jrun"` | prefix of the generated tasks; empty means the task is named like the script |

## Compatibility

| Combination | Status |
| --- | --- |
| Gradle 9.8.2 + JDK 25 / 21 | ✅ tested |
| Gradle 8.14.3 + JDK 21 | ✅ tested (weekly CI matrix) |
| Gradle < 8.14 | ⚠️ untested, the plugin prints a warning |
| Linux, macOS (x86_64 / arm64), Windows (CMD, PowerShell, Git Bash), WSL | ✅ |

The plugin has no runtime dependencies (the Kotlin stdlib is `compileOnly`) and is compiled for Java 17.

## Known limitations

- `args(...)` applies to Java targets only.
- On Windows, `!` inside `shell:` commands is swallowed by CMD's delayed expansion.
- `jrun.bat` notices content changes of the recorded build inputs, but not newly created build files — use
  `jrun --rebuild` after adding one. In class mode it calls `gradlew jrunInit` before every run (incremental),
  which keeps the sources fresh.
- Shebang scripts cannot live in `main/` (see above).
- `jrunInit`, `jrunAdd` and `jrunResolve` are not configuration-cache compatible yet.
- Direct `./jrun <target>` arguments are passed as given; script arguments declared with `scripts.put`
  are split on whitespace — use the block form for arguments with spaces.

## Using it before the Portal release

Use a composite build that points at a checkout of this repository:

```kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("../jrun-gradle-plugin")
}
```

`JRUN_PLUGIN_DIR=../jrun-gradle-plugin jrun init demo` writes that block for you. Avoid `mavenLocal()` for this:
its location comes from `~/.m2/settings.xml` and IntelliJ's Gradle sync often cannot resolve it
(`Plugin [id: 'com.archforge.jrun'] was not found`).

## Development

```bash
./gradlew build              # compile, all tests, validatePlugins
./gradlew test --tests '*JrunLauncherFunctionalTest*'
./gradlew jrunDist           # GitHub release assets in build/dist
```

| Test class | Kind | Covers |
| --- | --- | --- |
| `ScriptResolverTest` (11) | unit | script kinds, legacy splitting, array args, dependsOn validation and cycles, names |
| `BuildFileEditorTest` (10) | unit | `jrun add` edits: KTS / Groovy, nested blocks, strings and comments, upgrades, CRLF, injection guard |
| `InputsFingerprintTest` (5) | unit | fingerprint format, order, build-root files, project dependencies |
| `JrunVersionTest` (1) | unit | version resource |
| `JrunPluginFunctionalTest` (25) | TestKit | layout, launchers, argfiles, tasks, IDEA configs, Kotlin / Groovy / Spring Boot coexistence |
| `JrunScriptsDslFunctionalTest` (4) | TestKit | block DSL metadata, Gradle task dependencies, cycle errors |
| `JrunCliFunctionalTest` (3) | TestKit | `jrunAdd`, `jrunResolve`, module map |
| `JrunScriptFunctionalTest` (4) | bash | single-file mode, argument passing |
| `JrunLauncherFunctionalTest` (20) | bash | cache invalidation, JVM options, shebang, scripts, `add`, `init`, delegation, `-m`, module dependencies |
| `JrunInstallerTest` (3) | bash | `install.sh` against the built assets, checksum tampering |
| `JrunBatFunctionalTest` (6) | cmd | the same paths through `jrun.bat` (Windows CI only) |

Tests share a persistent TestKit home (`~/.gradle/jrun-testkit`, cached on CI). Releases: bump `version`,
then `git tag v<version> && git push origin v<version>` — the `release` workflow builds, tests and publishes the
GitHub release with all installer assets. See [`docs/PUBLISHING.md`](docs/PUBLISHING.md).

## License

[MIT](LICENSE) © 2026 sofn
