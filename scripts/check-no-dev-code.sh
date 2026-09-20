#!/usr/bin/env bash
# Assert that release build output carries none of the dev/test-only
# code. The exec broker and its actions live in `app/src/debug/java`, so
# absence is structural — this check is what keeps it that way when
# someone moves a file back to `src/main`.
#
# Usage: check-no-dev-code.sh <path>...
#   <path>  a directory of .class files (release Kotlin/javac output) or
#           an .apk (checked with apkanalyzer, dex level)
#
# With no arguments, checks the release Kotlin class output if present.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Classes that must never be compiled into a release build. The whole
# `me.phie.tawc.dev` package plus the broker actions that live in
# production packages because they drive production code.
FORBIDDEN_RE='me[./]phie[./]tawc[./]dev[./]|me[./]phie[./]tawc[./]install[./]InstallActions'

fail() { echo "check-no-dev-code: $*" >&2; exit 1; }

check_classes_dir() {
    local dir="$1" hits
    hits="$(find "$dir" -name '*.class' -printf '%P\n' | grep -E "$FORBIDDEN_RE" || true)"
    if [[ -n "$hits" ]]; then
        echo "check-no-dev-code: dev-only classes in release output ($dir):" >&2
        sed 's/^/  /' <<<"$hits" >&2
        exit 1
    fi
}

check_apk() {
    local apk="$1" apkanalyzer sdk_root local_sdk hits
    local_sdk=""
    if [[ -f local.properties ]]; then
        local_sdk="$(
            sed -nE 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*(.*[^[:space:]])[[:space:]]*$/\1/p' local.properties |
                head -n1
        )"
    fi
    sdk_root="${local_sdk:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}}"
    apkanalyzer="$(command -v apkanalyzer || true)"
    if [[ -z "$apkanalyzer" ]]; then
        apkanalyzer="$(ls -1 "$sdk_root"/cmdline-tools/*/bin/apkanalyzer 2>/dev/null | tail -n1 || true)"
    fi
    [[ -n "$apkanalyzer" ]] || fail "apkanalyzer not found (looked on PATH and under $sdk_root/cmdline-tools)"

    # `dex packages` lists defined *and* referenced classes/methods/
    # fields, so a stray reference from a class that stayed in main
    # trips this too, not just a moved-back definition.
    hits="$("$apkanalyzer" dex packages "$apk" | grep -E "$FORBIDDEN_RE" || true)"
    if [[ -n "$hits" ]]; then
        echo "check-no-dev-code: dev-only code in $apk:" >&2
        head -n 20 <<<"$hits" | sed 's/^/  /' >&2
        echo "  ($(wc -l <<<"$hits") lines total)" >&2
        exit 1
    fi
}

targets=("$@")
if [[ ${#targets[@]} -eq 0 ]]; then
    targets=(app/build/tmp/kotlin-classes/release app/build/intermediates/javac/release)
fi

checked=0
for t in "${targets[@]}"; do
    if [[ -d "$t" ]]; then
        check_classes_dir "$t"
        checked=$((checked + 1))
    elif [[ -f "$t" && "$t" == *.apk ]]; then
        check_apk "$t"
        checked=$((checked + 1))
    elif [[ $# -gt 0 ]]; then
        fail "no such directory or .apk: $t"
    fi
done
[[ $checked -gt 0 ]] || fail "nothing to check (no release class output; build it first)"
echo "check-no-dev-code: OK ($checked target(s))"
