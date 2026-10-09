#!/usr/bin/env bash
# Builds a signed release APK inside Docker (same approach as WhisperBench).
#
#   tools/build-apk.sh            # build image if missing, build + sign APK
#   tools/build-apk.sh --clean    # also wipe the cached Gradle/CMake build tree
#
# Inputs/outputs live OUTSIDE the repo in $TN_BUILD_DIR (default C:\Users\vyvrn\Transcribro-build):
#   release.jks, keystore.properties   generated on first run (random passwords), never committed
#   TranscribroNPU.apk                 the result
# The base image whisperbench-build:1 comes from WhisperBench (tools/docker there).
set -euo pipefail
export MSYS_NO_PATHCONV=1

IMAGE=transcribronpu-build:1
REPO="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="${TN_BUILD_DIR:-/c/Users/vyvrn/Transcribro-build}"
mkdir -p "$BUILD_DIR"

to_host() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }
REPO_H="$(to_host "$REPO")"
BUILD_H="$(to_host "$BUILD_DIR")"

if [[ "${1:-}" == "--clean" ]]; then
    docker volume rm -f transcribronpu-work >/dev/null
fi

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
    echo ">> building image $IMAGE"
    docker build -t "$IMAGE" "$REPO_H/tools/docker"
fi

# --- signing key (once) ----------------------------------------------------------------
if [[ ! -f "$BUILD_DIR/release.jks" ]]; then
    echo ">> generating release keystore in $BUILD_DIR"
    docker run --rm -v "$BUILD_H:/keys" "$IMAGE" bash -c '
        set -e
        pw=$(head -c 32 /dev/urandom | base64 | tr -dc A-Za-z0-9 | head -c 28)
        keytool -genkeypair -v -keystore /keys/release.jks -storetype PKCS12 \
            -alias transcribronpu -keyalg RSA -keysize 4096 -validity 10000 \
            -storepass "$pw" -keypass "$pw" \
            -dname "CN=Transcribro NPU, O=vyvrnc, C=CZ" >/dev/null 2>&1
        printf "storeFile=/keys/release.jks\nstorePassword=%s\nkeyAlias=transcribronpu\nkeyPassword=%s\n" "$pw" "$pw" \
            > /keys/keystore.properties
        chmod 600 /keys/keystore.properties /keys/release.jks'
fi

# --- build ---------------------------------------------------------------------------
echo ">> building release APK"
docker run --rm \
    -v "$REPO_H:/src:ro" \
    -v "$BUILD_H:/keys" \
    -v transcribronpu-work:/work \
    -v transcribronpu-gradle:/root/.gradle \
    -e TN_SIGNING_PROPS=/keys/keystore.properties \
    "$IMAGE" bash -c '
        set -euo pipefail
        mkdir -p /work/src
        cd /src
        tar --exclude=./.git --exclude=./app/build --exclude=./app/.cxx --exclude=./.gradle \
            --exclude=./build -cf - . | tar -xf - -C /work/src
        cd /work/src
        bash tools/fetch-qnn-headers.sh /work/qnn-include
        export QNN_INCLUDE_DIR=/work/qnn-include
        # A Windows checkout may carry CRLF line ends, which break the shebang.
        sed -i "s/\r\$//" gradlew
        chmod +x gradlew
        ./gradlew --no-daemon --console=plain assembleRelease
        APK=app/build/outputs/apk/release/app-release.apk
        test -f "$APK" || { echo "APK not produced (missing signing config?)"; exit 1; }
        apksigner verify --print-certs "$APK" | head -3
        cp "$APK" /keys/TranscribroNPU.apk
        echo ">> native libraries in APK:"
        unzip -l "$APK" | grep "lib/arm64-v8a/" || true
        aapt dump badging "$APK" | grep -E "^(package|sdkVersion|targetSdkVersion|native-code|application-label:)" || true
    '
ls -l "$BUILD_DIR/TranscribroNPU.apk"
