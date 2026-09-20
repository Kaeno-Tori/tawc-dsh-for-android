#!/bin/bash
# Build the debug APK.
#
# Usage: scripts/build-app.sh [--abi=auto|arm64-v8a|x86_64|both] [--graphics=list|--no-mesa] [--quiet]
#   --graphics=list   comma-separated subset of libhybris,turnip,none
#                     (default: all three; `none` is the "no driver
#                     provisioned" state, not a driver)
#   --no-mesa         shorthand for --graphics=libhybris,none (drop Turnip)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

ABI=auto
GRAPHICS=""
QUIET=0
for arg in "$@"; do
    case "$arg" in
        --abi=auto) ABI=auto ;;
        --abi=arm64-v8a|--abi=arm64|--abi=aarch64) ABI=arm64-v8a ;;
        --abi=x86_64) ABI=x86_64 ;;
        --abi=both) ABI=both ;;
        --graphics=*) GRAPHICS="${arg#--graphics=}" ;;
        --no-mesa) GRAPHICS="libhybris,none" ;;
        --quiet) QUIET=1 ;;
        -h|--help)
            sed -n '2,/^set -/p' "$0" | sed 's/^# \?//;$d'
            exit 0
            ;;
        *) echo "ERROR: unknown arg: $arg" >&2; exit 2 ;;
    esac
done

# The accepted backends are exactly the Gradle `knownGraphics` set
# (app/build.gradle.kts): libhybris, turnip, cpu. Validate here so an
# unknown value is a usage error rather than a Gradle configuration
# failure.
if [ -n "$GRAPHICS" ]; then
    for backend in ${GRAPHICS//,/ }; do
        case "$backend" in
            libhybris|turnip|cpu) ;;
            *)
                echo "ERROR: unknown graphics backend: $backend (allowed: libhybris, turnip, cpu)" >&2
                exit 2
                ;;
        esac
    done
fi

detect_target() {
    if [ -n "${TAWC_TARGET:-}" ]; then
        echo "$TAWC_TARGET"
        return
    fi
    if [ -f "$ROOT_DIR/.tawctarget" ]; then
        head -n1 "$ROOT_DIR/.tawctarget" | tr -d '[:space:]'
        return
    fi
    echo none
}

if [ "$ABI" = "auto" ]; then
    case "${ANDROID_SERIAL:-}" in
        emulator-*) TAWC_ABIS=x86_64 ;;
        ?*)         TAWC_ABIS=arm64-v8a ;;
        *)
            target="$(detect_target)"
            case "$target" in
                emulator) TAWC_ABIS=x86_64 ;;
                physical|none|"") TAWC_ABIS=arm64-v8a ;;
                *)
                    echo "ERROR: unknown target '$target' from TAWC_TARGET or .tawctarget" >&2
                    echo "       (expected 'physical', 'emulator', or 'none')" >&2
                    exit 1
                    ;;
            esac
            ;;
    esac
elif [ "$ABI" = "both" ]; then
    TAWC_ABIS=arm64-v8a,x86_64
else
    TAWC_ABIS="$ABI"
fi

GRADLE_ARGS=("-PtawcAbis=$TAWC_ABIS")
if [ -n "$GRAPHICS" ]; then
    GRADLE_ARGS+=("-PtawcGraphics=$GRAPHICS")
fi
GRADLE_ARGS+=(assembleDebug)
if [ "$QUIET" -eq 1 ]; then
    GRADLE_ARGS+=(--quiet)
fi

echo "=== Building APK ($TAWC_ABIS, graphics=${GRAPHICS:-default}) ==="
( cd "$ROOT_DIR" && ./gradlew "${GRADLE_ARGS[@]}" )
