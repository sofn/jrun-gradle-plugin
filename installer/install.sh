#!/bin/sh
# jrun installer for Linux and macOS (x86_64 / arm64):
#
#   curl -fsSL https://github.com/sofn/jrun-gradle-plugin/releases/latest/download/install.sh | sh
#
# Installs the jrun command to ~/.local/bin/jrun and the Gradle wrapper template used by
# `jrun init` to ~/.local/share/jrun/wrapper. Every download is checked against SHA256SUMS.
#
# Environment:
#   JRUN_VERSION        release to install, e.g. 0.2.0 (default: latest)
#   JRUN_INSTALL_DIR    where the jrun command goes (default: ~/.local/bin)
#   JRUN_HOME           data directory (default: $XDG_DATA_HOME/jrun or ~/.local/share/jrun)
#   JRUN_DOWNLOAD_BASE  base URL of the release assets (mirrors, testing)
set -eu

REPO="sofn/jrun-gradle-plugin"
VERSION="${JRUN_VERSION:-latest}"
if [ -n "${JRUN_DOWNLOAD_BASE:-}" ]; then
  BASE="$JRUN_DOWNLOAD_BASE"
elif [ "$VERSION" = "latest" ]; then
  BASE="https://github.com/$REPO/releases/latest/download"
else
  BASE="https://github.com/$REPO/releases/download/v${VERSION#v}"
fi
BIN_DIR="${JRUN_INSTALL_DIR:-$HOME/.local/bin}"
DATA_DIR="${JRUN_HOME:-${XDG_DATA_HOME:-$HOME/.local/share}/jrun}"

fail() {
  echo "jrun install: $*" >&2
  exit 1
}

case "$(uname -s)" in
  Linux|Darwin) ;;
  MINGW*|MSYS*|CYGWIN*) fail "on Windows use install.ps1 (see the README)" ;;
  *) fail "unsupported OS: $(uname -s)" ;;
esac
case "$(uname -m)" in
  x86_64|amd64|arm64|aarch64) ;;
  *) fail "unsupported architecture: $(uname -m)" ;;
esac

download() {
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$1" -o "$2" || fail "download failed: $1"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$1" -O "$2" || fail "download failed: $1"
  else
    fail "curl or wget is required"
  fi
}

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d ' ' -f 1
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | cut -d ' ' -f 1
  else
    fail "sha256sum or shasum is required to verify the download"
  fi
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT INT TERM

download "$BASE/SHA256SUMS" "$TMP/SHA256SUMS"
for asset in jrun gradlew gradlew.bat gradle-wrapper.jar gradle-wrapper.properties; do
  download "$BASE/$asset" "$TMP/$asset"
  expected="$(awk -v name="$asset" '$2 == name || $2 == "*" name { print $1 }' "$TMP/SHA256SUMS")"
  [ -n "$expected" ] || fail "$asset is missing from SHA256SUMS"
  [ "$(sha256 "$TMP/$asset")" = "$expected" ] || fail "checksum mismatch for $asset"
done

mkdir -p "$BIN_DIR" "$DATA_DIR/wrapper/gradle/wrapper"
cp "$TMP/jrun" "$BIN_DIR/jrun"
chmod 755 "$BIN_DIR/jrun"
cp "$TMP/gradlew" "$TMP/gradlew.bat" "$DATA_DIR/wrapper/"
chmod 755 "$DATA_DIR/wrapper/gradlew"
cp "$TMP/gradle-wrapper.jar" "$TMP/gradle-wrapper.properties" "$DATA_DIR/wrapper/gradle/wrapper/"

echo "Installed $("$BIN_DIR/jrun" --version) to $BIN_DIR/jrun"
case ":${PATH:-}:" in
  *":$BIN_DIR:"*) ;;
  *)
    echo "Add it to your PATH, e.g. in ~/.bashrc or ~/.zshrc:"
    echo "  export PATH=\"$BIN_DIR:\$PATH\""
    ;;
esac
echo "Get started: jrun init my-app && cd my-app && ./jrun"
