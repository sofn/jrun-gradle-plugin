#!/usr/bin/env bash
#
# jrun - run Java like uv / cargo / npm scripts (Linux / macOS / WSL / Git Bash)
#
#   ./jrun Hello.java                 single-file source mode
#   ./jrun com.example.demo.App        classpath mode (Spring Boot etc.)
#   ./jrun <script-name>               run a script declared in jrun { scripts }
#   ./jrun build|test|clean|classes    shortcut for the matching Gradle task
#   ./jrun init                        regenerated the argfile cache (same as `gradle jrunInit`)
#   ./jrun --help
#
# 本文件由 jrun-gradle-plugin 自动生成，请勿手改；配置写在 build.gradle.kts。
# Windows 下也可以用 jrun.bat (CMD) 或 jrun.ps1 (PowerShell)。

set -euo pipefail
JRUN_VERSION="@JRUN_VERSION@"

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

OUT_DIR="build/jrun"
# argfile 是平台相关的（Windows 用 D:\... + ";"，Linux/WSL 用 /mnt/d/... + ":"），
# 按平台分子目录，避免 Windows 与 WSL 共用同一个 build/ 时互相覆盖。
#
# 注意区分三种情况：
#   MINGW/MSYS/CYGWIN = Windows 上的 Git Bash -> 用 Windows 版 argfile（win）
#   Linux（含 WSL）/Darwin                    -> 用 POSIX 版 argfile（unix）
case "$(uname -s 2>/dev/null || echo unknown)" in
  MINGW*|MSYS*|CYGWIN*) PLATFORM="win" ;;
  *)                    PLATFORM="unix" ;;
esac
ARGS_DIR="$OUT_DIR/$PLATFORM"
GRADLEW="gradle"
if [[ -f ./gradlew ]]; then GRADLEW="./gradlew"; fi
# gradlew 在 git 里常丢可执行位，统一用 sh 执行
gradle_run() {
  if [[ "$GRADLEW" == "gradle" ]]; then gradle "$@"; else sh "$GRADLEW" "$@"; fi
}

usage() {
  sed -n '3,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  if [ -f "$OUT_DIR/scripts.properties" ]; then
    echo
    echo "可用 scripts（build.gradle.kts 的 jrun { scripts } 中声明）:"
    sed 's/^/  /' "$OUT_DIR/scripts.properties"
  fi
}

# ---------------------------------------------------------------------------
# 定位可用 java：PATH 上的 java 可能是坏的 shim，因此逐个候选实测 -version
# ---------------------------------------------------------------------------
normalize_path() { printf '%s' "${1//\\//}"; }
java_works() { [[ -n "$1" && -x "$1" ]] && "$1" -version >/dev/null 2>&1; }

# WSL 里 PATH 常包含 Windows 的 java.exe（interop）。它能执行，但读不了
# /mnt/d/... 这类 Linux 路径 —— 用 @argfile 时会报 "could not open"。
is_foreign_exe() {
  case "$1" in
    *.exe|*.EXE|/mnt/*) return 0 ;;
  esac
  return 1
}

JAVA_BIN=""
for candidate in \
  "${JAVA_HOME:-}/bin/java" \
  "${JAVA_HOME:-}/bin/java.exe" \
  "${JDK_HOME:-}/bin/java" \
  "$(command -v java || true)"; do
  candidate="$(normalize_path "$candidate")"
  # POSIX 平台必须用原生 java；Windows 上的 bash（MSYS）则不限制
  if [[ "$PLATFORM" == "unix" ]] && is_foreign_exe "$candidate"; then
    continue
  fi
  if java_works "$candidate"; then JAVA_BIN="$candidate"; break; fi
done

if [[ -z "$JAVA_BIN" ]]; then
  if [[ "$PLATFORM" == "unix" ]] && command -v java >/dev/null 2>&1; then
    echo "只找到了 Windows 版的 java，它无法读取 Linux 路径 ($(command -v java))。" >&2
    echo "请在当前环境安装原生 JDK，例如: sudo apt install openjdk-21-jdk" >&2
    echo "或者改用 Windows 侧的 jrun.bat。" >&2
  else
    echo "未找到可用的 java，请设置 JAVA_HOME 指向 JDK 11+ 安装目录" >&2
  fi
  exit 1
fi
# Gradle 必须使用同一个 JDK，避免 class file 版本不一致
export JAVA_HOME="$(dirname "$(dirname "$JAVA_BIN")")"

# ---------------------------------------------------------------------------
# 运行缓存是否最新：重新计算 jrunInit 写下的内容哈希清单并逐字比较（不看 mtime）
#   inputs.sha256   构建输入：build / settings / gradle.properties / 版本目录（含构建根目录）
#   sources.sha256  main/ 与 resources/ 下的全部文件（删除、改名、mtime 回退都能发现）
#   classpath 模式运行的是 build/classes 里的 .class，源码一变就必须重新编译，否则会跑到旧代码。
# 规则必须与插件的 InputsFingerprint 一致
# ---------------------------------------------------------------------------
BUILD_FILES=(build.gradle build.gradle.kts gradle.properties gradle/libs.versions.toml settings.gradle settings.gradle.kts)

sha256_files() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum -- "$@"
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 -- "$@"
  else
    return 1
  fi | sed 's/^\([0-9a-f]\{64\}\) [ *]/\1  /'
}

# settings.gradle(.kts) 所在目录就是 Gradle 构建根目录
build_root() {
  local d="$ROOT_DIR"
  while :; do
    if [[ -f "$d/settings.gradle.kts" || -f "$d/settings.gradle" ]]; then printf '%s' "$d"; return 0; fi
    if [[ "$d" == "$(dirname -- "$d")" ]]; then break; fi
    d="$(dirname -- "$d")"
  done
  printf '%s' "$ROOT_DIR"
}

inputs_manifest() {
  local -a files=()
  local f root d prefix=""
  for f in "${BUILD_FILES[@]}"; do
    if [[ -f "$f" ]]; then files+=("$f"); fi
  done
  root="$(build_root)"
  if [[ "$root" != "$ROOT_DIR" ]]; then
    d="$ROOT_DIR"
    while [[ "$d" != "$root" ]]; do d="$(dirname -- "$d")"; prefix="../$prefix"; done
    for f in "${BUILD_FILES[@]}"; do
      if [[ -f "$prefix$f" ]]; then files+=("$prefix$f"); fi
    done
  fi
  if (( ${#files[@]} > 0 )); then sha256_files "${files[@]}"; fi
}

sources_manifest() {
  local -a files=()
  local f
  while IFS= read -r f; do
    if [[ -n "$f" ]]; then files+=("$f"); fi
  done < <(find main resources -type f 2>/dev/null | LC_ALL=C sort)
  if (( ${#files[@]} > 0 )); then sha256_files "${files[@]}"; fi
}

cache_fresh() {
  local now
  [[ -f "$ARGS_DIR/source.args" && -f "$ARGS_DIR/class.args" ]] || return 1
  [[ -f "$ARGS_DIR/inputs.sha256" && -f "$ARGS_DIR/sources.sha256" ]] || return 1
  now="$(inputs_manifest)" || return 1
  [[ "$now" == "$(cat -- "$ARGS_DIR/inputs.sha256")" ]] || return 1
  now="$(sources_manifest)" || return 1
  [[ "$now" == "$(cat -- "$ARGS_DIR/sources.sha256")" ]]
}

FORCE_REBUILD=0
if [[ "${1:-}" == "--rebuild" || "${1:-}" == "-r" ]]; then FORCE_REBUILD=1; shift; fi

if [[ "$FORCE_REBUILD" == "1" ]] || ! cache_fresh; then
  echo "→ 准备运行环境 (Gradle jrunInit)..." >&2
  if ! gradle_run --quiet --console=plain jrunInit >&2; then
    echo "" >&2
    echo "jrunInit 失败。若在 WSL 下运行，请确认：" >&2
    echo "  1. WSL 内已安装 Linux JDK（java -version 可用），而不是只靠 Windows 的 JAVA_HOME；" >&2
    echo "  2. 该 JDK 版本 >= build.gradle.kts 里配置的 toolchain。" >&2
    exit 1
  fi
fi

if [[ ! -f "$ARGS_DIR/source.args" || ! -f "$ARGS_DIR/class.args" ]]; then
  echo "找不到运行参数文件: $ARGS_DIR/*.args" >&2
  echo "请手动执行: ./gradlew jrunInit" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# 解析目标
# ---------------------------------------------------------------------------
TARGET="${1:-}"
if [[ "$TARGET" == "-h" || "$TARGET" == "--help" ]]; then usage; exit 0; fi
shift || true

if [[ -z "$TARGET" && -f "$OUT_DIR/default-target.txt" ]]; then
  TARGET="$(head -1 "$OUT_DIR/default-target.txt" | tr -d '\r')"
fi
if [[ -z "$TARGET" ]]; then usage; exit 1; fi

# 内置 Gradle 任务快捷方式
case "$TARGET" in
  build|test|clean|classes|jar|check) gradle_run "$TARGET" "$@"; exit $? ;;
  init|prepare) gradle_run jrunInit "$@"; exit $? ;;
esac

# 命名 scripts：值可以是 gradle:task、Xxx.java、a.b.C，可带固定参数
if [[ -f "$OUT_DIR/scripts.properties" ]]; then
  CMD="$(awk -F= -v k="$TARGET" '$1==k{sub(/^[^=]*=/,""); print; exit}' \
        "$OUT_DIR/scripts.properties" 2>/dev/null || true)"
  if [[ -n "$CMD" ]]; then
    read -r -a PARTS <<< "$CMD"
    if [[ "${PARTS[0]}" == gradle:* ]]; then
      TASK="${PARTS[0]#gradle:}"
      EXTRA=("${PARTS[@]:1}")
      # 注意：不能写 ${ARR[@]:1:+"${ARR[@]:1}"} —— bash 会把 "1:+" 当成算术切片并报错
      gradle_run "$TASK" ${EXTRA[@]+"${EXTRA[@]}"} "$@"
      exit $?
    fi
    TARGET="${PARTS[0]}"
    if (( ${#PARTS[@]} > 1 )); then
      set -- "${PARTS[@]:1}" "$@"
    fi
  fi
fi

# ---------------------------------------------------------------------------
# 执行：所有 classpath / --source 细节都在 argfile 里，脚本无需拼接
# ---------------------------------------------------------------------------
# 从源码文件推导可启动的主类名（Kotlin / Groovy 用）。
#
# Kotlin 的顶层 main 函数编译后类名是 "文件名 + Kt"（Main.kt -> MainKt），
# 而声明了 class/object Main 的则是 Main。这里用编译产物是否存在来判定，
# 比猜后缀可靠。
derive_jvm_main() {
  local f="$1" pkg base pkgpath prefix candidate outdir classfile
  pkg="$(sed -n 's/^[[:space:]]*package[[:space:]][[:space:]]*\([^[:space:];]*\).*/\1/p' \
         "$f" 2>/dev/null | head -1)"
  base="$(basename -- "$f")"
  base="${base%.*}"
  pkgpath="${pkg//.//}"
  prefix=""
  [[ -n "$pkg" ]] && prefix="$pkg."

  for candidate in "${base}Kt" "${base}"; do
    for outdir in \
      build/classes/kotlin/main \
      build/classes/groovy/main \
      build/classes/java/main; do
      classfile="$outdir/${pkgpath:+$pkgpath/}$candidate.class"
      if [[ -f "$classfile" ]]; then
        printf '%s%s' "$prefix" "$candidate"
        return 0
      fi
    done
  done
  # 都没找到时按最常见的 Kotlin 顶层函数形式兜底
  printf '%s%sKt' "$prefix" "$base"
}

# 统一的源文件定位：给出文件名时先看当前目录，再到 main/ 下找
locate_source() {
  local t="$1"
  if [[ -f "$t" ]]; then printf '%s' "$t"; return 0; fi
  if [[ -f "main/$t" ]]; then printf '%s' "main/$t"; return 0; fi
  local found
  found="$(find main -name "$(basename -- "$t")" -print -quit 2>/dev/null || true)"
  if [[ -n "$found" ]]; then printf '%s' "$found"; return 0; fi
  return 1
}

case "$TARGET" in
  *.java)
    LOCATED="$(locate_source "$TARGET")" || { echo "找不到源文件: $TARGET" >&2; exit 1; }
    # Java 单文件源码模式：即写即跑，不产生 .class
    exec "$JAVA_BIN" "@$ROOT_DIR/$ARGS_DIR/source.args" "$LOCATED" "$@"
    ;;
  *.kt|*.groovy|*.scala)
    # 非 Java 语言没有单文件源码模式，先编译再按类名启动
    LOCATED="$(locate_source "$TARGET")" || { echo "找不到源文件: $TARGET" >&2; exit 1; }
    MAIN="$(derive_jvm_main "$LOCATED")"
    exec "$JAVA_BIN" "@$ROOT_DIR/$ARGS_DIR/class.args" "$MAIN" "$@"
    ;;
  *)
    exec "$JAVA_BIN" "@$ROOT_DIR/$ARGS_DIR/class.args" "$TARGET" "$@"
    ;;
esac
