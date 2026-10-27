# jrun vs Jbang vs `gradlew run`

[中文](comparison.zh-CN.md) · [README](../README.md)

jrun is a Gradle plugin plus generated launchers. It does not replace Gradle; it makes the everyday loop
("write a file, run it, add a dependency, run it again") feel like uv or cargo, while the build stays a normal
Gradle build that every IDE and CI understands.

## Comparison

| | jrun | Jbang | `gradlew run` (application plugin) | Maven `exec:java` |
| --- | --- | --- | --- | --- |
| Underneath | standard Gradle plugin | its own resolver and cache | Gradle | Maven |
| Where dependencies live | `dependencies {}` in `build.gradle.kts` | `//DEPS` comments in the source | build script | `pom.xml` |
| Source layout | flat `main/`, `test/` | single files anywhere | `src/main/java` | `src/main/java` |
| Run a single file | `./jrun Hello.java` (JDK source launcher, no `.class` files) | `jbang Hello.java` | no | no |
| Named commands | `./jrun dev` (array args, `shell:`, `dependsOn`) | aliases / catalogs | custom tasks | profiles / executions |
| Add a dependency | `./jrun add g:a:v` (resolved, rolled back on failure) | edit `//DEPS` | edit the build script | edit the pom |
| New project | `jrun init` (Gradle wrapper included) | `jbang init` | `gradle init` | `mvn archetype:generate` |
| Re-run without changes | no Gradle start-up: `java @argfile` directly | cached jar | Gradle configuration phase every time | Maven start-up every time |
| Frameworks that scan the classpath (Spring Boot …) | classpath mode | yes | yes | yes |
| IDE | standard Gradle import + generated run configurations | IDE plugin / `jbang edit` | native | native |
| Multi-module | one launcher per module, `-m` from the root | not its goal | native | native |

## When not to use jrun

1. **Large multi-module, multi-team repositories.** The flat layout and the generated launchers are one more
   convention on top of Gradle. At that scale convention plugins with the standard layout scale better; `-m` helps,
   but it is not a monorepo tool.
2. **Teams that need the official, documented paths.** Onboarding material, compliance rules and tools that assume
   `src/main/java` (some static analysis, coverage and archetype tooling) work best with the standard layout.
3. **Libraries published to Maven Central.** It works, but contributors expect the standard layout; plain Gradle is
   the clearer choice.
4. **One-off single-file scripts without any build file**, where you would rather keep the dependencies in the
   source: Jbang is the better fit.
5. **Builds that rely on the configuration cache or isolated projects.** `jrunInit`, `jrunAdd` and `jrunResolve`
   are not compatible with them yet.

If none of these apply — small services, tools, prototypes, Spring Boot or Quarkus apps that one person or a small
team owns — jrun removes most of the ceremony without leaving Gradle.
