#!/bin/bash
# Pack a device's already-provisioned container rootfs into a distributable
# "rootfs pack" — a self-contained, DSH-included rootfs tarball that an
# install can extract instead of downloading an upstream bootstrap and
# then provisioning node/npm/DSH on-device.
#
# The rootfs is streamed straight from the device through `tar` into the
# host's `zstd`; nothing is staged on the device or on disk in between.
#
# Usage: scripts/build-rootfs-pack.sh [--install-id=arch] [--out=DIR] [--level=N]
#
# Requires a container that already has DSH in it — a pack without DSH is
# just a slower way to ship the upstream bootstrap.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

PKG="io.github.kaeno_tori.tawc_dsh"
INSTALL_ID="arch"
OUT_DIR="$ROOT_DIR/build/packs"
LEVEL=19

for arg in "$@"; do
    case "$arg" in
        --install-id=*) INSTALL_ID="${arg#--install-id=}" ;;
        --out=*) OUT_DIR="${arg#--out=}" ;;
        --level=*) LEVEL="${arg#--level=}" ;;
        -h|--help)
            sed -n '2,/^set -/p' "$0" | sed 's/^# \?//;$d'
            exit 0
            ;;
        *) echo "ERROR: unknown arg: $arg" >&2; exit 2 ;;
    esac
done

# shellcheck source=lib/select-device.sh
. "$SCRIPT_DIR/lib/select-device.sh"

for tool in adb zstd tar sha256sum; do
    command -v "$tool" >/dev/null || { echo "ERROR: $tool not found" >&2; exit 1; }
done

DISTROS="distros/$INSTALL_ID"
ROOTFS="$DISTROS/rootfs"

dev() { adb shell run-as "$PKG" "$@"; }

# ---------------------------------------------------------------- excludes
#
# Every entry here was found the hard way; the comments are the point of
# this list. Anything added must say what breaks without it.
EXCLUDES=(
    # Transient /run state. Mode 0000 and empty; unreadable by the app
    # even though it owns it, so tar dies on it.
    "./run/systemd/dissect-root"

    # Four LIVE unix sockets. `pacman-key` spawns gpg-agent during the
    # install and nothing reaps it, so they outlive the install. This is
    # the dangerous one: toybox tar reports `unknown file type '140000'`
    # and then **stops**, producing a truncated archive — and `zstd -t`
    # still passes, because the truncation is inside the tar stream.
    # Verify catches it only because GNU tar rejects the cut header.
    "./etc/pacman.d/gnupg/S.gpg-agent*"

    # npm's content-addressed package cache — 189 MiB in the container
    # this was measured on. Pure duplication: it holds the tarballs whose
    # extracted trees are already unpacked in `_npx` next to it. Refilled
    # on demand.
    "./root/.npm/_cacache"

    # npx's logs, and the timestamp of its last update check.
    "./root/.npm/_logs"
    "./root/.npm/_update-notifier-last-checked"

    # MUST NOT be distributed: contains .anonymous-user-id (so every
    # install would share one identity) and .credentials.yaml. DSH
    # recreates it on first run.
    "./root/.dsh"

    # Stale package databases. Nothing reads them — initPackageManager
    # runs `-Sy` regardless — so shipping them only invites confusion.
    "./var/lib/pacman/sync"
)

# What the pack is for. If these are missing, the pack is not a pack.
REQUIRED=(
    "./usr/bin/node"
    "./usr/bin/npm"
)

# DSH itself, in whichever of two places the container was provisioned:
#   - `npm install -g @deepseek-ai/dsh` → ./usr/bin/dsh, tree under
#     ./usr/lib/node_modules/@deepseek-ai/
#   - `npx @deepseek-ai/dsh` → ./root/.npm/_npx/<hash>/node_modules/.bin/dsh
# Both are live paths, not a legacy/new split: DshService resolves
# `command -v dsh` first and falls back to globbing `_npx`, so either one
# makes a working pack. The npx route is what the reference container
# ended up using, and it is why `./root/.npm` is no longer excluded
# wholesale — `_npx` *is* DSH now. Only the cache beside it is dropped.
DSH_PROBE="$ROOTFS/usr/bin/dsh $ROOTFS/root/.npm/_npx/*/node_modules/.bin/dsh"

# ---------------------------------------------------------------- preflight

echo "==> target: ${ANDROID_SERIAL:-<default>}"
echo "==> container: $ROOTFS"

if ! dev test -d "$ROOTFS" 2>/dev/null; then
    echo "ERROR: no rootfs at $ROOTFS on the device." >&2
    echo "       Install a container first (the app, or scripts/tawc-exec.sh)." >&2
    exit 1
fi

missing=()
for p in "${REQUIRED[@]}"; do
    # `test -e` alone is wrong here: a rootfs legitimately contains
    # absolute symlinks (e.g. /usr/bin/npm -> /usr/lib/node_modules/npm/…)
    # that only resolve *inside* the container, so from out here they
    # dangle and -e says "missing". Presence of the entry is what matters.
    if ! dev test -e "$ROOTFS/${p#./}" && ! dev test -L "$ROOTFS/${p#./}"; then
        missing+=("$p")
    fi
done
if [ ${#missing[@]} -gt 0 ]; then
    echo "ERROR: this container is not provisioned; missing:" >&2
    printf '         %s\n' "${missing[@]}" >&2
    echo "       Install DSH into it first (see TAWC_DSH_DESIGN.md §9.7)." >&2
    exit 1
fi

# The `_npx` half of DSH_PROBE has to be expanded by the *device* shell:
# `run-as` execs its argv directly, so nothing out here would expand it.
dsh_path="$(dev sh -c "ls -d $DSH_PROBE 2>/dev/null | head -n1" | tr -d '\r' || true)"
if [ -z "$dsh_path" ]; then
    echo "ERROR: no DSH in this container — neither $ROOTFS/usr/bin/dsh" >&2
    echo "       nor an _npx install under it." >&2
    echo "       A pack without DSH is just a slower way to ship the upstream" >&2
    echo "       bootstrap. Provision it first (see TAWC_DSH_DESIGN.md §9.7)." >&2
    exit 1
fi
dsh_rel="${dsh_path#"$ROOTFS/"}"
echo "==> dsh: $dsh_rel"

# Version for the manifest, from whichever tree that path belongs to.
dsh_pkg_json="$ROOTFS/usr/lib/node_modules/@deepseek-ai/dsh/package.json"
if [ "$dsh_rel" != "usr/bin/dsh" ]; then
    dsh_pkg_json="$(dev sh -c "ls -d $ROOTFS/root/.npm/_npx/*/node_modules/@deepseek-ai/dsh/package.json 2>/dev/null | head -n1" | tr -d '\r' || true)"
fi

# Anything the app can't read is silently dropped by tar, which would
# produce a pack that looks fine and is missing files. Refuse instead of
# shipping a hole. (tawcroot fakes ownership, so "unreadable" here means
# the owner read bit is clear; see §9.8 for the mode-mangling bug that
# put one file in this state.)
unreadable="$(dev find "$ROOTFS" -type f ! -perm -u+r 2>/dev/null || true)"
if [ -n "$unreadable" ]; then
    echo "ERROR: files the app cannot read — these would be dropped from the pack:" >&2
    printf '         %s\n' $unreadable >&2
    echo "       Fix their mode in the container, then re-run." >&2
    exit 1
fi

# A socket anywhere else would truncate the archive just as the gpg-agent
# ones did. Fail loudly rather than emit a short pack.
sockets="$(dev find "$ROOTFS" -type s 2>/dev/null || true)"
unexpected=""
for s in $sockets; do
    case "$s" in
        "$ROOTFS"/etc/pacman.d/gnupg/S.gpg-agent*) ;;
        *) unexpected="$unexpected $s" ;;
    esac
done
if [ -n "$unexpected" ]; then
    echo "ERROR: unix sockets that no exclude covers — tar will truncate on these:" >&2
    printf '         %s\n' $unexpected >&2
    echo "       Add an --exclude for them above, or remove them from the container." >&2
    exit 1
fi

# ---------------------------------------------------------------- pack

mkdir -p "$OUT_DIR"
PACK="$OUT_DIR/dsh-rootfs-${INSTALL_ID}-aarch64.tar.zst"
echo "==> packing (level $LEVEL) -> $PACK"

exclude_args=()
for e in "${EXCLUDES[@]}"; do
    exclude_args+=("--exclude=$e")
done

# Streamed in the foreground so the caller sees progress and failures;
# a half-written pack is worse than none, since nothing downstream
# notices (see the zstd -t caveat below).
started=$(date +%s)
rm -f "$PACK"
adb exec-out run-as "$PKG" tar -cf - -C "$ROOTFS" "${exclude_args[@]}" . \
    | zstd -T0 "-$LEVEL" -q -f -o "$PACK"
echo "==> packed in $(( $(date +%s) - started ))s"

# ---------------------------------------------------------------- verify

echo "==> verifying"

# 1. The compressed frame is intact.
zstd -t "$PACK" >/dev/null || { echo "ERROR: zstd frame is corrupt" >&2; exit 1; }

# 2. The tar stream inside it is intact. This is the check that matters:
#    `zstd -t` passes on a truncated tar, and the truncation we hit was
#    exactly that. GNU tar exits non-zero on a cut header.
if ! zstd -dc "$PACK" | tar -tf - > "$OUT_DIR/.members.txt" 2>/dev/null; then
    echo "ERROR: tar stream is truncated or malformed — do not ship this pack." >&2
    echo "       Usually an unexcluded socket or special file; see §9.8." >&2
    exit 1
fi
members=$(wc -l < "$OUT_DIR/.members.txt")

for p in "${REQUIRED[@]}"; do
    grep -qxF "$p" "$OUT_DIR/.members.txt" || {
        echo "ERROR: $p missing from the pack" >&2; exit 1; }
done
for e in "${EXCLUDES[@]}"; do
    # globs in the exclude list are literal here; just check the exact ones
    case "$e" in *'*'*) continue ;; esac
    grep -qxF "$e" "$OUT_DIR/.members.txt" && {
        echo "ERROR: $e should have been excluded but is in the pack" >&2; exit 1; }
done

# 3. /sdcard or another app's view of the tree would change what got
#    packed; asserting the module count catches a container that was
#    half-provisioned in a way the REQUIRED check alone wouldn't. Both
#    trees are counted: DSH's dependency closure is what's being proven
#    present, and it lives under one of them depending on the route
#    (see DSH_PROBE) — npm's own bundled tree is the `usr/lib` half.
modules=$(grep -cE '^\./(usr/lib/node_modules/|root/\.npm/_npx/)' "$OUT_DIR/.members.txt")
[ "$modules" -gt 1000 ] || {
    echo "ERROR: only $modules entries under the module trees — DSH looks incomplete" >&2
    exit 1; }

rm -f "$OUT_DIR/.members.txt"

size=$(stat -c %s "$PACK")
digest=$(sha256sum "$PACK" | cut -d' ' -f1)
uncompressed=$(zstd -dc "$PACK" | wc -c)

cat > "$PACK.manifest.txt" <<EOF
# Source-of-truth for $(basename "$PACK").
#
# This pack is NOT reproducible from source — it is a snapshot of a
# container that was bootstrapped from upstream and then provisioned in
# place. Rebuilding it means rebuilding that container (§9.7), not
# re-running this script against a different device. Treat the digest
# below as the identity of the artifact.
built-at: $(date -Iseconds)
built-on: ${ANDROID_SERIAL:-<default>}
install-id: $INSTALL_ID
dsh-version: $(dev cat "$dsh_pkg_json" 2>/dev/null \
    | tr -d ' \n' | sed -n 's/.*"version":"\([^"]*\)".*/\1/p')
dsh-path: $dsh_rel
size-bytes: $size
size-uncompressed-bytes: ${uncompressed:-unknown}
sha256: $digest
members: $members
node-modules-entries: $modules
excludes:
$(printf '  - %s\n' "${EXCLUDES[@]}")
EOF

echo
echo "==> $PACK"
echo "    $size bytes ($(( size / 1024 / 1024 )) MiB), $members members"
echo "    sha256 $digest"
echo "    manifest ${PACK}.manifest.txt"
