#!/usr/bin/env bash
#
# jrun - run Java like uv / cargo / npm scripts (Linux / macOS / WSL / Git Bash)
#
# 同一份脚本有两种用法：
#   - 项目里的 ./jrun：由 jrun-gradle-plugin 生成，版本跟随项目
#   - 全局命令 ~/.local/bin/jrun（一键安装）：jrun init 新建项目；在项目里执行时委托给项目自己的 ./jrun
#
# 本文件由 jrun-gradle-plugin 生成，请勿手改；配置写在 build.gradle.kts。
# Windows 下用 jrun.bat（PowerShell 也可以直接执行 jrun.bat）。

set -euo pipefail
JRUN_VERSION="@JRUN_VERSION@"
SCRIPT_PATH="${BASH_SOURCE[0]}"
SCRIPT_DIR="$(cd -- "$(dirname -- "$SCRIPT_PATH")" && pwd)"
ORIG_PWD="$PWD"

usage() {
  cat <<'EOF'
jrun - run Java like uv / cargo / npm scripts

  jrun init [dir]                    create a new jrun project (global command)
  ./jrun Hello.java                  single-file source mode
  ./jrun com.example.demo.App        classpath mode (Spring Boot etc.)
  ./jrun <script> [args...]          run a script declared in jrun { }
  ./jrun add [--test|--config <c>] <group:artifact[:version]>...
  ./jrun -m <module> <command>       run a command in a sub-module
  ./jrun build|test|clean|classes    shortcut for the Gradle task
  ./jrun --rebuild <target>          refresh the runtime cache first
  ./jrun init                        (inside a project) refresh the runtime cache
  ./jrun --version | --help

  JVM options: JAVA_OPTS, then JRUN_OPTS (the later one wins)
EOF
  if [[ -f build/jrun/scripts.properties ]]; then
    echo
    echo "Scripts:"
    sed 's/^/  /' build/jrun/scripts.properties
  fi
}

is_project_dir() { [[ -f "$1/build.gradle.kts" || -f "$1/build.gradle" ]]; }

# argfile 是平台相关的（Windows 用 D:\... + ";"，Linux/WSL 用 /mnt/d/... + ":"），
# 按平台分子目录，避免 Windows 与 WSL 共用同一个 build/ 时互相覆盖。
#   MINGW/MSYS/CYGWIN = Windows 上的 Git Bash -> 用 Windows 版 argfile（win）
#   Linux（含 WSL）/Darwin                    -> 用 POSIX 版 argfile（unix）
case "$(uname -s 2>/dev/null || echo unknown)" in
  MINGW*|MSYS*|CYGWIN*) PLATFORM="win" ;;
  *)                    PLATFORM="unix" ;;
esac

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

find_java() {
  local candidate
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
  JAVA_HOME="$(dirname "$(dirname "$JAVA_BIN")")"
  export JAVA_HOME
}

# 多模块时 gradlew 在构建根目录：从项目目录向上找
find_gradlew() {
  local d="$ROOT_DIR"
  while :; do
    if [[ -f "$d/gradlew" ]]; then printf '%s' "$d/gradlew"; return 0; fi
    if [[ "$d" == "$(dirname -- "$d")" ]]; then break; fi
    d="$(dirname -- "$d")"
  done
  printf 'gradle'
}

# gradlew 在 git 里常丢可执行位，统一用 sh 执行
gradle_run() {
  if [[ "$GRADLEW" == "gradle" ]]; then gradle "$@"; else sh "$GRADLEW" "$@"; fi
}

# ---------------------------------------------------------------------------
# 全局命令：jrun init，或者委托给最近项目自己的 ./jrun
# ---------------------------------------------------------------------------
wrapper_template_dir() { printf '%s' "${JRUN_HOME:-${XDG_DATA_HOME:-$HOME/.local/share}/jrun}/wrapper"; }

# jrun init [目录]：生成最小项目 + Gradle wrapper（来自安装时下载的模板）+ 项目自己的启动器
init_project() {
  local target="${1:-.}" name template
  if [[ "$target" == -* ]]; then echo "用法: jrun init [目录]" >&2; return 1; fi
  mkdir -p -- "$target"
  target="$(cd -- "$target" && pwd)"
  if is_project_dir "$target"; then
    echo "jrun init: $target 已经是 Gradle 项目（存在 build.gradle(.kts)）" >&2
    return 1
  fi
  name="$(basename -- "$target" | tr -c 'A-Za-z0-9._\n-' '-')"
  {
    if [[ -n "${JRUN_PLUGIN_DIR:-}" ]]; then
      # 插件发布前 / 开发期：直接引用插件源码
      printf 'pluginManagement {\n    includeBuild("%s")\n}\n\n' "$(cd -- "$JRUN_PLUGIN_DIR" && pwd)"
    fi
    printf 'rootProject.name = "%s"\n' "$name"
  } > "$target/settings.gradle.kts"
  cat > "$target/build.gradle.kts" <<EOF
plugins {
    id("com.archforge.jrun") version "$JRUN_VERSION"
}

// Dependencies go here, or run: ./jrun add <group:artifact:version>
dependencies {
}

jrun {
    mainClass = "Main"
}
EOF
  mkdir -p -- "$target/main"
  if [[ ! -f "$target/main/Main.java" ]]; then
    cat > "$target/main/Main.java" <<'EOF'
public class Main {
    public static void main(String[] args) {
        System.out.println("Hello from jrun");
    }
}
EOF
  fi
  if [[ ! -f "$target/.gitignore" ]]; then printf 'build/\n.gradle/\n.idea/\n*.iml\n' > "$target/.gitignore"; fi
  template="$(wrapper_template_dir)"
  if [[ -f "$template/gradle/wrapper/gradle-wrapper.jar" && ! -f "$target/gradlew" ]]; then
    mkdir -p -- "$target/gradle/wrapper"
    cp -- "$template/gradlew" "$template/gradlew.bat" "$target/"
    cp -- "$template/gradle/wrapper/gradle-wrapper.jar" "$template/gradle/wrapper/gradle-wrapper.properties" \
      "$target/gradle/wrapper/"
    chmod +x "$target/gradlew"
  fi
  cp -- "$SCRIPT_PATH" "$target/jrun"
  chmod +x "$target/jrun"
  echo "已创建 jrun 项目: $target"
  echo "  cd $(basename -- "$target") && ./jrun                 # 运行 main/Main.java"
  echo "  ./jrun add com.google.code.gson:gson:2.11     # 添加依赖"
  if [[ ! -f "$target/gradlew" ]]; then
    echo "提示: 没有 Gradle wrapper 模板（用一键安装脚本安装 jrun 时会一并装好），首次运行需要本机已安装 Gradle" >&2
  fi
}

# 全局命令（例如 ~/.local/bin/jrun）：委托给最近项目自己的 ./jrun（版本跟随项目，与 gradlew 同理）
global_main() {
  local dir="$ORIG_PWD"
  case "${1:-}" in
    --version|-V) echo "jrun $JRUN_VERSION"; exit 0 ;;
    -h|--help) usage; exit 0 ;;
    init) shift; init_project "$@"; exit $? ;;
  esac
  while :; do
    if is_project_dir "$dir"; then
      if [[ -f "$dir/jrun" ]]; then exec bash "$dir/jrun" "$@"; fi
      if grep -qs "com.archforge.jrun" "$dir/build.gradle.kts" "$dir/build.gradle"; then
        echo "→ 生成项目启动器 (Gradle jrunInit)..." >&2
        (cd -- "$dir" && ROOT_DIR="$dir" && GRADLEW="$(find_gradlew)" && find_java \
          && gradle_run --quiet --console=plain jrunInit) >&2 || exit 1
        exec bash "$dir/jrun" "$@"
      fi
    fi
    if [[ "$dir" == "$(dirname -- "$dir")" ]]; then break; fi
    dir="$(dirname -- "$dir")"
  done
  if [[ $# -eq 0 ]]; then usage; exit 1; fi
  echo "jrun: 当前目录不在 jrun 项目中。新建项目: jrun init <目录>" >&2
  exit 1
}

if ! is_project_dir "$SCRIPT_DIR"; then global_main "$@"; fi

# ---------------------------------------------------------------------------
# 项目模式
# ---------------------------------------------------------------------------
ROOT_DIR="$SCRIPT_DIR"
cd "$ROOT_DIR"
OUT_DIR="build/jrun"
ARGS_DIR="$OUT_DIR/$PLATFORM"
GRADLEW="$(find_gradlew)"

# -m 的取值：先查根项目写下的 modules.properties，查不到时按约定把 a:b 当作目录 a/b
module_dir() {
  local m="$1" mapped=""
  if [[ -f "$OUT_DIR/modules.properties" ]]; then
    mapped="$(awk -F= -v k="$m" '$1 == k { sub(/^[^=]*=/, ""); print; exit }' "$OUT_DIR/modules.properties")"
  fi
  if [[ -z "$mapped" ]]; then
    mapped="${m#:}"
    mapped="${mapped//://}"
  fi
  printf '%s' "$mapped"
}

case "${1:-}" in
  --version|-V) echo "jrun $JRUN_VERSION"; exit 0 ;;
  -h|--help) usage; exit 0 ;;
  -m|--module)
    if [[ $# -lt 2 ]]; then echo "用法: ./jrun -m <模块> <命令> [参数...]" >&2; exit 1; fi
    MODULE_DIR="$(module_dir "$2")"
    if [[ ! -f "$MODULE_DIR/jrun" ]]; then
      echo "jrun: 模块 '$2' 没有启动器（$MODULE_DIR/jrun）；在该模块应用 jrun 插件后执行一次 gradle jrunInit" >&2
      exit 1
    fi
    exec bash "$MODULE_DIR/jrun" "${@:3}"
    ;;
esac

find_java

# JAVA_OPTS 在前、JRUN_OPTS 在后：同名 JVM 参数以后者为准。按空白拆分，不做通配符展开
JVM_OPTS=()
read -r -a JVM_OPTS <<< "${JAVA_OPTS:-} ${JRUN_OPTS:-}"

# ./jrun add：先改写，再在新一轮构建里解析依赖并刷新缓存；任何一步失败都恢复原文件（对标 uv add）
add_dependencies() {
  local cfg="implementation" build_file backup
  local -a deps=()
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --test) cfg="testImplementation" ;;
      --config) shift; cfg="${1:-}" ;;
      --config=*) cfg="${1#--config=}" ;;
      -*) echo "jrun add: 未知选项 $1" >&2; return 1 ;;
      *) deps+=("--dependency=$1") ;;
    esac
    shift
  done
  if [[ ${#deps[@]} -eq 0 || -z "$cfg" ]]; then
    echo "用法: ./jrun add [--test | --config <配置名>] <group:artifact[:version]>..." >&2
    return 1
  fi
  build_file="build.gradle.kts"
  if [[ ! -f "$build_file" ]]; then build_file="build.gradle"; fi
  mkdir -p -- "$OUT_DIR"
  backup="$OUT_DIR/$build_file.bak"
  cp -- "$build_file" "$backup"
  if gradle_run --quiet --console=plain jrunAdd "--configuration=$cfg" "${deps[@]}" \
     && gradle_run --quiet --console=plain jrunResolve "--configuration=$cfg" jrunInit; then
    rm -f -- "$backup"
    echo "✓ 已添加到 $build_file ($cfg)"
    return 0
  fi
  cp -- "$backup" "$build_file"
  rm -f -- "$backup"
  echo "jrun add: 依赖解析失败，已恢复 $build_file" >&2
  return 1
}

if [[ "${1:-}" == "add" ]]; then shift; add_dependencies "$@"; exit $?; fi

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

# 本项目与构建根目录下现有的构建文件（用来发现 jrunInit 之后才新建的文件）
inputs_candidates() {
  local f root d prefix=""
  for f in "${BUILD_FILES[@]}"; do
    if [[ -f "$f" ]]; then printf '%s\n' "$f"; fi
  done
  root="$(build_root)"
  if [[ "$root" != "$ROOT_DIR" ]]; then
    d="$ROOT_DIR"
    while [[ "$d" != "$root" ]]; do d="$(dirname -- "$d")"; prefix="../$prefix"; done
    for f in "${BUILD_FILES[@]}"; do
      if [[ -f "$prefix$f" ]]; then printf '%s\n' "$prefix$f"; fi
    done
  fi
}

in_list() {
  local x="$1" y
  shift
  for y in "$@"; do if [[ "$x" == "$y" ]]; then return 0; fi; done
  return 1
}

# inputs.sha256 里登记的文件（含 project 依赖模块的构建文件）按原顺序重算哈希；
# 文件被删除或出现了新的构建文件都算变化
inputs_fresh() {
  local manifest="$ARGS_DIR/inputs.sha256" f now
  local -a listed=()
  [[ -f "$manifest" ]] || return 1
  while IFS= read -r f; do
    if [[ -n "$f" ]]; then listed+=("${f:66}"); fi
  done < "$manifest"
  while IFS= read -r f; do
    if [[ -n "$f" ]] && ! in_list "$f" ${listed[@]+"${listed[@]}"}; then return 1; fi
  done < <(inputs_candidates)
  if (( ${#listed[@]} == 0 )); then return 0; fi
  now="$(sha256_files "${listed[@]}" 2>/dev/null)" || return 1
  [[ "$now" == "$(cat -- "$manifest")" ]]
}

# main/、resources/，加上 jrunInit 在 source-roots 里登记的 project 依赖源码目录
sources_manifest() {
  local -a files=() roots=(main resources)
  local f
  if [[ -f "$ARGS_DIR/source-roots" ]]; then
    while IFS= read -r f; do
      if [[ -n "$f" ]]; then roots+=("$f"); fi
    done < "$ARGS_DIR/source-roots"
  fi
  while IFS= read -r f; do
    if [[ -n "$f" ]]; then files+=("$f"); fi
  done < <(find "${roots[@]}" -type f 2>/dev/null | LC_ALL=C sort)
  if (( ${#files[@]} > 0 )); then sha256_files "${files[@]}"; fi
}

cache_fresh() {
  local now
  [[ -f "$ARGS_DIR/source.args" && -f "$ARGS_DIR/class.args" && -f "$ARGS_DIR/sources.sha256" ]] || return 1
  inputs_fresh || return 1
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

# 源文件定位：调用者所在目录 → 项目根 → main/ 同路径 → main/ 下同名文件
# （先按调用目录解析，#!/usr/bin/env -S jrun 和在子目录里执行才都能找到文件）
locate_source() {
  local t="$1" found
  if [[ "$t" != /* && -f "$ORIG_PWD/$t" ]]; then printf '%s' "$ORIG_PWD/$t"; return 0; fi
  if [[ -f "$t" ]]; then printf '%s' "$t"; return 0; fi
  if [[ -f "main/$t" ]]; then printf '%s' "main/$t"; return 0; fi
  found="$(find main -name "$(basename -- "$t")" -print -quit 2>/dev/null || true)"
  if [[ -n "$found" ]]; then printf '%s' "$found"; return 0; fi
  return 1
}

# javac 不认 #!；JDK 只对“非 .java 文件名”跳过首行 shebang，所以复制成无扩展名副本再运行
without_shebang() {
  local src="$1" copy
  if [[ "$(head -c 2 -- "$src" 2>/dev/null)" != "#!" ]]; then printf '%s' "$src"; return 0; fi
  mkdir -p -- "$OUT_DIR/shebang"
  copy="$OUT_DIR/shebang/$(basename -- "$src" .java)"
  cp -- "$src" "$copy"
  printf '%s' "$copy"
}

# argfile 引用规则（与插件的 ArgFiles.quote 一致）
argfile_quote() {
  local s="${1//\\/\\\\}"
  s="${s//\"/\\\"}"
  printf '"%s"\n' "$s"
}

# launch <exec|call> <@运行时argfile> <主类或源文件> <固定参数文件|""> [用户参数...]
#   有固定参数时，把“主类/源文件 + 固定参数”写进 run/<name>.args，交给 java 自己解析（不经 shell 分词）
launch() {
  local mode="$1" runtime="$2" program="$3" fixed="$4" run_file
  shift 4
  local -a cmd=("$JAVA_BIN" ${JVM_OPTS[@]+"${JVM_OPTS[@]}"} "$runtime")
  if [[ -n "$fixed" && -s "$fixed" ]]; then
    mkdir -p -- "$ARGS_DIR/run"
    run_file="$ARGS_DIR/run/$(basename -- "$fixed")"
    { argfile_quote "$program"; cat -- "$fixed"; } > "$run_file"
    cmd+=("@$ROOT_DIR/$run_file")
  else
    cmd+=("$program")
  fi
  if [[ "$mode" == "exec" ]]; then exec "${cmd[@]}" "$@"; fi
  "${cmd[@]}" "$@"
}

# run_target <exec|call> <目标> <固定参数文件|""> [用户参数...]
run_target() {
  local mode="$1" target="$2" fixed="$3" located
  shift 3
  case "$target" in
    *.java)
      # Java 单文件源码模式：即写即跑，不产生 .class
      located="$(locate_source "$target")" || { echo "找不到源文件: $target" >&2; return 1; }
      launch "$mode" "@$ROOT_DIR/$ARGS_DIR/source.args" "$(without_shebang "$located")" "$fixed" "$@"
      ;;
    *.kt|*.groovy|*.scala)
      # 非 Java 语言没有单文件源码模式，先编译再按类名启动
      located="$(locate_source "$target")" || { echo "找不到源文件: $target" >&2; return 1; }
      launch "$mode" "@$ROOT_DIR/$ARGS_DIR/class.args" "$(derive_jvm_main "$located")" "$fixed" "$@"
      ;;
    *)
      launch "$mode" "@$ROOT_DIR/$ARGS_DIR/class.args" "$target" "$fixed" "$@"
      ;;
  esac
}

# 命名 script 的元数据由 jrunInit 写在 build/jrun/scripts/<name>.properties（kind/target/deps）
# 与 <name>.args（Java 目标的固定参数，已按 argfile 规则加引号）
NAME_PATTERN='^[A-Za-z0-9][A-Za-z0-9._-]*$'
SCRIPTS_DIR="$OUT_DIR/scripts"

meta_get() { sed -n "s/^$2=//p" "$1" | head -1; }

# run_script <exec|call> <名字> [用户参数...]：先按声明顺序执行 dependsOn（失败即停），再执行自身
run_script() {
  local mode="$1" name="$2" meta kind target deps dep
  shift 2
  meta="$SCRIPTS_DIR/$name.properties"
  kind="$(meta_get "$meta" kind)"
  target="$(meta_get "$meta" target)"
  deps="$(meta_get "$meta" deps)"
  for dep in $deps; do
    if ! run_script call "$dep"; then
      echo "jrun: script '$dep'（'$name' 的前置依赖）执行失败" >&2
      exit 1
    fi
  done
  case "$kind" in
    gradle)
      local -a task=()
      read -r -a task <<< "$target"
      if [[ "$mode" == "exec" ]]; then gradle_run "${task[@]}" "$@"; exit $?; fi
      gradle_run "${task[@]}" "$@"
      ;;
    shell)
      if [[ "$mode" == "exec" ]]; then exec sh -c "$target" "$name" "$@"; fi
      sh -c "$target" "$name" "$@"
      ;;
    *)
      run_target "$mode" "$target" "$SCRIPTS_DIR/$name.args" "$@"
      ;;
  esac
}

if [[ "$TARGET" =~ $NAME_PATTERN && -f "$SCRIPTS_DIR/$TARGET.properties" ]]; then
  run_script exec "$TARGET" "$@"
fi
run_target exec "$TARGET" "" "$@"
