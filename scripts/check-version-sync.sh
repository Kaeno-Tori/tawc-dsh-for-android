#!/bin/bash
# Report the app version, and validate its shape.
#
# `versionName` in app/build.gradle.kts is the single source of truth for the
# version and `versionCode` is derived from it there, so there is nothing left
# to keep in sync — this used to also check an F-Droid changelog file and a
# recipe, both of which are gone (the app is not published to any store).
#
# What is still worth checking is the format, because the derivation is
# arithmetic on the parts: a `versionName` that is not `major.minor[.patch]`
# fails the Gradle build at configuration time, and this says so in a second
# rather than after a build has started.
#
# It only reports; it never edits.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

GRADLE="$ROOT_DIR/app/build.gradle.kts"
# Read the declaration, not the `versionName =` line inside defaultConfig —
# that one assigns the val, so it holds no literal to read. The val *is* the
# single source (see the comment next to it in build.gradle.kts).
VERSION="$(sed -n 's/^ *val *tawcVersionName *= *"\([^"]*\)".*/\1/p' "$GRADLE" | head -1)"
[ -n "$VERSION" ] || { echo "ERROR: no tawcVersionName in $GRADLE" >&2; exit 1; }

if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+(\.[0-9]+)?$ ]]; then
    echo "ERROR: versionName '$VERSION' is not major.minor[.patch]" >&2
    exit 1
fi

# Mirrors the arithmetic in build.gradle.kts. It is three numbers, and the
# copy that matters is the one Gradle runs; this one only reports, so a
# disagreement cannot change what an APK is stamped with.
CODE="$(awk -F. '{ printf "%d", $1*10000 + $2*100 + ($3 ? $3 : 0) }' <<<"$VERSION")"
echo "app version: $VERSION (from app/build.gradle.kts)"
echo "app versionCode: $CODE (derived from versionName)"
