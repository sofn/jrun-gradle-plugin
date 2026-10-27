# jrun-gradle-plugin

Gradle plugin + generated launchers (`src/main/resources/jrun/jrun.sh`, `jrun.bat`) that make Java feel like uv / cargo / npm.

## Commands

- `bash gradlew build` — compile, all tests (unit + TestKit + real bash launcher runs), `validatePlugins`
- `bash gradlew test --tests '*Name*'` — a single test class
- `bash gradlew jrunDist` — GitHub release assets into `build/dist` (also a dependency of `test`)
- Tests share a persistent TestKit home (`~/.gradle/jrun-testkit`); override with `-PjrunTestKitDir`.
  Concurrent test methods are opt-in with `-PjrunTestParallelism=N` (slower locally: each needs its own cold TestKit daemon)
- Gradle compat run: `bash gradlew test -PgradleCompatVersion=8.14.3`

## Rules

- The launchers and the plugin share protocols: content manifests (`InputsFingerprint`) and per-script metadata
  (`build/jrun/scripts/<name>.properties|.args`). Change both sides together; the launcher tests lock the format.
- `jrun.bat` must stay ASCII-only; the plugin writes it with CRLF. Delayed expansion is on: avoid literal `!`.
- The bash launcher runs under `set -euo pipefail` on bash 3.2+: never end a function with a failing `[[ ]] && …`
  (the function returns 1 and `set -e` exits the script); no `mapfile`.
- Windows-only behaviour is tested in `JrunBatFunctionalTest` (`@EnabledOnOs(WINDOWS)`), which runs on CI only.
- Commit messages: conventional commits, no tool attribution trailers.
