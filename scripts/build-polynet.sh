#!/usr/bin/env bash
# Builds app/jni/polynet (the in-process proxy core, Go + gomobile) into the artifacts the app packages:
#   app/libs/polynet.jar                                Java bindings (org.telegram.polynet.Polynet + go.*)
#   app/src/main/jniLibs/arm64-v8a/libgojni.so          Go runtime + core
# Needs Go (version in app/jni/polynet/go.mod; gomobile/gobind pinned there as tools), the Android SDK with
# an NDK under sdk.dir, and any JDK (AGP 9 accepts modern class files).
# Standalone on purpose: setup.sh does not run on Windows/Git Bash.
# Upgrade the core: cd app/jni/polynet && go get github.com/xtls/xray-core@<tag commit> && go mod tidy && go test ./...
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$SCRIPT_DIR/../app/jni/polynet"
PROJ="$(cd "$SCRIPT_DIR/.." && pwd)"
NDK_VERSION="${NDK_VERSION:-30.0.16248370}"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK" ]]; then
  # fall back to sdk.dir from local.properties (POSIX path)
  SDK="$(sed -n 's/^sdk\.dir=//p' "$PROJ/local.properties" | head -n 1)"
fi
[[ -n "$SDK" ]] || { echo "ERROR: set ANDROID_HOME or sdk.dir in local.properties" >&2; exit 1; }
export ANDROID_HOME="$SDK"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$SDK/ndk/$NDK_VERSION}"
[[ -d "$ANDROID_NDK_HOME" ]] || { echo "ERROR: NDK not found: $ANDROID_NDK_HOME" >&2; exit 1; }

cd "$SRC"
go test -count=1 ./...
go install tool  # gomobile + gobind at the versions pinned in go.mod
GOBIN_DIR="$(go env GOPATH)/bin"
if command -v cygpath >/dev/null 2>&1; then GOBIN_DIR="$(cygpath -u "$GOBIN_DIR")"; fi  # Git Bash: C:\... breaks PATH
export PATH="$GOBIN_DIR:$PATH"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
# -checklinkname=0: the core's Android-only dependency github.com/wlynxg/anet links to net internals
gomobile bind -target=android/arm64 -androidapi 23 -javapkg org.telegram -trimpath \
    -ldflags "-s -w -buildid= -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384" -o "$OUT/polynet.aar" .
(cd "$OUT" && unzip -q -o polynet.aar classes.jar jni/arm64-v8a/libgojni.so)

mkdir -p "$PROJ/app/libs" "$PROJ/app/src/main/jniLibs/arm64-v8a"
cp "$OUT/classes.jar" "$PROJ/app/libs/polynet.jar"
cp "$OUT/jni/arm64-v8a/libgojni.so" "$PROJ/app/src/main/jniLibs/arm64-v8a/libgojni.so"

echo "polynet artifacts updated:"
echo "  app/libs/polynet.jar"
echo "  app/src/main/jniLibs/arm64-v8a/libgojni.so"
