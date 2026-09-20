#!/bin/bash
# Cross-compile Mesa's Turnip (the freedreno Vulkan driver) for aarch64
# glibc and stage the three files the TURNIP graphics backend lays into
# a rootfs at /usr/lib/turnip/:
#
#   libvulkan_freedreno.so   Turnip itself, kgsl-only, stripped
#   freedreno_icd.json       ICD manifest whose library_path is baked to
#                            the guest-side .so path above
#   libvulkan.so.1           the Vulkan loader the ICD is reached through
#
# Why kgsl: on Android the Adreno kernel driver is /dev/kgsl-3d0, labelled
# `gpu_device` (openable by untrusted_app), while the upstream DRM path
# /dev/dri/renderD128 carries `graphics_device`, which an app-side process
# can never open. Turnip is therefore configured with
# -Dfreedreno-kmds=kgsl and nothing else, and the resulting .so contains
# no renderD128 path at all.
#
# Why a bundled loader: an ICD is useless without one, and a container
# with none would otherwise need the user to install `vulkan-icd-loader`
# by hand. We lift Arch Linux ARM's copy — the same package, and
# therefore the same build of libc-side ABI, the container runs. Resolved
# out of the mirror's repo database rather than by filename: ALARM keeps
# only the current version, so a pinned filename would 404 on the next
# upstream bump. Its only DT_NEEDED entries are libc.so.6 and
# ld-linux-aarch64.so.1, so it pulls nothing else in.
#
# Headless Vulkan only: this config sets -Dplatforms= (no WSI), which is
# what the 2026-09 kgsl measurements were taken against — compute
# (llama.cpp et al) works, presenting to a Wayland surface does not.
# Turning WSI on means a host sysroot plus the wayland / wayland-protocols
# pins; out of scope until something needs a swapchain off Turnip.
#
# Mesa version: 26.2.2. Turnip from 25.3.6 SIGSEGVs the container on the
# q4_K MUL_MAT shapes llama.cpp emits; 26.2.2 runs the whole
# test-backend-ops MUL_MAT suite clean. This is the only Mesa pin in
# deps.list (`mesa-turnip`) — see TAWC_DSH_DESIGN.md §11.1.
#
# Output:
#   build/turnip-aarch64/install/usr/lib/turnip/
#     libvulkan_freedreno.so  freedreno_icd.json  libvulkan.so.1
#
# Usage:
#   scripts/build-turnip.sh            # incremental (ninja + staged trees)
#   scripts/build-turnip.sh --clean    # wipe the meson + staged trees
#   scripts/build-turnip.sh --mesa-src=DIR
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=lib/deps.sh
source "$SCRIPT_DIR/lib/deps.sh"

# Guest-side install dir. Kept in sync with
# me.phie.tawc.install.TurnipInstallProvider.GUEST_LIB_DIR by hand — the
# ICD JSON below bakes the absolute .so path into the artifact, so the
# two have to agree for the loader to find the driver.
GUEST_LIB_DIR="/usr/lib/turnip"
# ICD JSON name the runtime looks for; see TurnipInstallProvider.GUEST_ICD_PATH.
ICD_NAME="freedreno_icd.json"

# Arch Linux ARM mirrors for the loader package — the same tree
# build-rootfs-pack.sh's bootstrap came from. Four of them because USTC
# intermittently answers 403 (reproduced from both a CN and a US host),
# and a mirror can serve the .db while 403ing the package file it names.
# Each entry is tried as a whole unit — see the loader block below for why
# splitting the two across mirrors does not work. Override with your own
# space-separated list for a local mirror.
ALARM_MIRRORS="${TAWC_ALARM_MIRRORS:-\
https://mirrors.ustc.edu.cn/archlinuxarm \
https://mirrors.aliyun.com/archlinuxarm \
https://fl.us.mirror.archlinuxarm.org \
https://ca.us.mirror.archlinuxarm.org}"
ALARM_ARCH="aarch64"
# Repo the package lives in, and its name. Verified against the current
# extra.db: core/community/alarm don't carry it.
ALARM_REPO="extra"
LOADER_PKG="vulkan-icd-loader"

MESA_SRC_OVERRIDE=""
CLEAN=0
for arg in "$@"; do
    case "$arg" in
        --clean) CLEAN=1 ;;
        --mesa-src=*) MESA_SRC_OVERRIDE="${arg#--mesa-src=}" ;;
        -h|--help) sed -n '2,/^set -/p' "$0" | sed 's/^# \?//;$d'; exit 0 ;;
        *) echo "ERROR: unknown arg: $arg" >&2; exit 1 ;;
    esac
done

VENV="$REPO_DIR/build/mesa-venv"
BUILD_ROOT="$REPO_DIR/build/turnip-aarch64"
MESON_DIR="$BUILD_ROOT/meson"
CROSS_FILE="$BUILD_ROOT/cross.txt"
INSTALL_DIR="$BUILD_ROOT/install$GUEST_LIB_DIR"
DRIVER_SO="$INSTALL_DIR/libvulkan_freedreno.so"
ICD_JSON="$INSTALL_DIR/$ICD_NAME"
LOADER_SO="$INSTALL_DIR/libvulkan.so.1"
MESA_BUILD_SO="src/freedreno/vulkan/libvulkan_freedreno.so"
MESA_BUILD_ICD="src/freedreno/vulkan/freedreno_icd.aarch64.json"

need_tool() {
    command -v "$1" >/dev/null || {
        echo "ERROR: '$1' not on PATH. See notes/building.md." >&2
        exit 1
    }
}

for tool in meson ninja curl bsdtar strings aarch64-linux-gnu-gcc aarch64-linux-gnu-strip python3; do
    need_tool "$tool"
done

# ── Mesa source ──
# Pinned checkout from deps.list; `dep_ensure` clones it on demand and
# fails loudly if HEAD has drifted off the pin.
if [ -n "$MESA_SRC_OVERRIDE" ]; then
    MESA_DIR="$MESA_SRC_OVERRIDE"
else
    dep_ensure mesa-turnip
    MESA_DIR="$(dep_dir mesa-turnip)"
fi
[ -d "$MESA_DIR/src/freedreno/vulkan" ] || {
    echo "ERROR: $MESA_DIR does not look like a Mesa tree (no src/freedreno/vulkan)" >&2
    exit 1
}

# ── Python for Mesa's build scripts ──
# Mesa's meson needs mako (and pyyaml) at *build* time to generate the
# freedreno register headers. Distro python deliberately isn't depended
# on for that: a dedicated venv keeps the host's site-packages out of a
# cross build that must not pick up host libraries by accident.
if [ ! -x "$VENV/bin/python" ]; then
    echo "==> creating $VENV"
    python3 -m venv "$VENV"
    "$VENV/bin/pip" install --quiet --upgrade pip
    "$VENV/bin/pip" install --quiet mako pyyaml packaging
fi
# meson resolves `import('python').find_installation()` through PATH, so
# the venv has to come first for mako to be visible.
export PATH="$VENV/bin:$PATH"

if [ "$CLEAN" = "1" ]; then
    echo "==> wiping $MESON_DIR and $INSTALL_DIR"
    rm -rf "$MESON_DIR" "$INSTALL_DIR"
fi

# ── Cross file ──
# pkg-config points at a directory that does not exist on purpose: every
# optional dependency Mesa probes for then reports "not found", which is
# what a Vulkan-only, no-WSI build wants. Without this the host's
# pkg-config would happily hand back *x86_64* libraries and leak them
# into the aarch64 link.
#
# Exported, not just passed to `meson setup`: ninja re-runs the whole
# configure step whenever a meson.build input moves (REGENERATE_BUILD),
# and that regeneration inherits *ninja's* environment. Isolating only
# the setup line leaves the second configure free to pick up the host's
# zlib — which resolves (1.3.2, x86_64) but has no header on the cross
# compiler's search path, so the build then dies on `zlib.h: No such
# file` deep inside Mesa's util library.
export PKG_CONFIG_LIBDIR=/nonexistent
export PKG_CONFIG_PATH=/nonexistent

mkdir -p "$BUILD_ROOT"
cat >"$CROSS_FILE" <<'EOF'
[binaries]
c = 'aarch64-linux-gnu-gcc'
cpp = 'aarch64-linux-gnu-g++'
ar = 'aarch64-linux-gnu-ar'
strip = 'aarch64-linux-gnu-strip'
pkgconfig = 'pkg-config'

[host_machine]
system = 'linux'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF

if [ -f "$MESON_DIR/build.ninja" ] && ! grep -q "aarch64-linux-gnu-gcc" "$MESON_DIR/build.ninja"; then
    echo "==> builddir was not configured as an aarch64 cross build; reconfiguring"
    rm -rf "$MESON_DIR"
fi
if [ -d "$MESON_DIR" ] && [ ! -f "$MESON_DIR/build.ninja" ]; then
    echo "==> incomplete meson builddir detected; reconfiguring"
    rm -rf "$MESON_DIR"
fi

if [ ! -f "$MESON_DIR/build.ninja" ]; then
    echo "==> configuring Turnip (mesa $(cat "$MESA_DIR/VERSION" 2>/dev/null || echo '?'))"
    meson setup "$MESON_DIR" "$MESA_DIR" \
        --cross-file "$CROSS_FILE" \
        --buildtype=release \
        --prefix=/usr --libdir=lib \
        -Dvulkan-drivers=freedreno \
        -Dfreedreno-kmds=kgsl \
        -Dgallium-drivers= \
        -Dplatforms= \
        -Dglx=disabled -Degl=disabled -Dgles1=disabled -Dgles2=disabled -Dopengl=false \
        -Dvideo-codecs= \
        -Dllvm=disabled -Dlmsensors=disabled -Dvalgrind=disabled -Dlibunwind=disabled \
        -Dshared-glapi=disabled -Dxmlconfig=disabled -Ddisplay-info=disabled \
        -Dzstd=disabled -Dexpat=disabled \
        -Ddefault_library=shared
fi

echo "==> building libvulkan_freedreno.so"
# The ICD manifest is a separate ninja target, not a by-product of the
# .so; naming only the .so leaves it ungenerated.
ninja -C "$MESON_DIR" "$MESA_BUILD_SO" "$MESA_BUILD_ICD"

mkdir -p "$INSTALL_DIR"
aarch64-linux-gnu-strip -o "$DRIVER_SO" "$MESON_DIR/$MESA_BUILD_SO"
chmod 755 "$DRIVER_SO"

# Guard the kgsl claim in the header. A build that had somehow picked up
# the DRM backend would still link and still look right in every log
# line, and only fail on-device — this is the one property that decides
# whether the driver can open anything at all.
if strings "$DRIVER_SO" | grep -q renderD128; then
    echo "ERROR: $DRIVER_SO references renderD128 — not a kgsl-only build" >&2
    exit 1
fi

# ── ICD manifest ──
# Rewrite Mesa's generated JSON rather than writing one from scratch: the
# api_version has to track the driver, and taking Mesa's own file is the
# only way that stays true without a second pin to maintain. Only
# library_path is ours — Mesa emits it as the build prefix's path
# (/usr/lib/...), while the backend installs the .so under
# $GUEST_LIB_DIR.
python3 - "$MESON_DIR/$MESA_BUILD_ICD" "$GUEST_LIB_DIR/libvulkan_freedreno.so" "$ICD_JSON" <<'PY'
import json, sys
src, library_path, dst = sys.argv[1], sys.argv[2], sys.argv[3]
with open(src) as fh:
    doc = json.load(fh)
doc["ICD"]["library_path"] = library_path
with open(dst, "w") as fh:
    json.dump(doc, fh, indent=4, sort_keys=True)
    fh.write("\n")
PY
chmod 644 "$ICD_JSON"

# ── Vulkan loader ──
# Resolve the package filename out of the mirror's repo database; see the
# header for why the name isn't hardcoded.
#
# Mirrors are tried as whole units: the .db that names the file and the
# file itself have to come from the same host. Repo databases point at
# mutable filenames, so a .db from a mirror that is a sync ahead of the
# one serving the archive names a file that does not exist there yet.
# `--retry-all-errors` because the 403s these mirrors emit are transient
# and plain `--retry` does not cover 4xx.
CACHE_DIR="$REPO_DIR/build/downloads"
PKG_CACHE="$CACHE_DIR/alarm-$ALARM_ARCH-$ALARM_REPO.db"
mkdir -p "$CACHE_DIR"
# The whole block is skipped once a loader is staged; TAWC_REFRESH_ALARM_DB
# forces a fresh resolve.
if [ ! -f "$LOADER_SO" ] || [ "${TAWC_REFRESH_ALARM_DB:-0}" = "1" ]; then
    staged=0
    for mirror in $ALARM_MIRRORS; do
        base="$mirror/$ALARM_ARCH/$ALARM_REPO"
        echo "==> resolving $LOADER_PKG from $base"
        if ! curl -fsSL --retry 3 --retry-all-errors -o "$PKG_CACHE" \
                "$base/$ALARM_REPO.db"; then
            echo "    no repo database from this mirror, trying the next" >&2
            continue
        fi

        DB_EXTRACT="$(mktemp -d)"
        bsdtar -xf "$PKG_CACHE" -C "$DB_EXTRACT"
        PKG_FILENAME=""
        PKG_VERSION=""
        for desc in "$DB_EXTRACT"/*/desc; do
            [ -f "$desc" ] || continue
            [ "$(awk '/^%NAME%$/{getline; print; exit}' "$desc")" = "$LOADER_PKG" ] || continue
            PKG_FILENAME="$(awk '/^%FILENAME%$/{getline; print; exit}' "$desc")"
            PKG_VERSION="$(awk '/^%VERSION%$/{getline; print; exit}' "$desc")"
            break
        done
        rm -rf "$DB_EXTRACT"
        if [ -z "$PKG_FILENAME" ]; then
            echo "    $LOADER_PKG not in this mirror's $ALARM_REPO.db, trying the next" >&2
            continue
        fi

        PKG_CACHE_FILE="$CACHE_DIR/$PKG_FILENAME"
        if [ ! -f "$PKG_CACHE_FILE" ]; then
            echo "==> downloading $LOADER_PKG $PKG_VERSION"
            if ! curl -fsSL --retry 3 --retry-all-errors -o "$PKG_CACHE_FILE.part" \
                    "$base/$PKG_FILENAME"; then
                rm -f "$PKG_CACHE_FILE.part"
                echo "    download failed, trying the next" >&2
                continue
            fi
            mv "$PKG_CACHE_FILE.part" "$PKG_CACHE_FILE"
        fi

        PKG_EXTRACT="$(mktemp -d)"
        if ! bsdtar -xf "$PKG_CACHE_FILE" -C "$PKG_EXTRACT"; then
            rm -rf "$PKG_EXTRACT"
            echo "    unreadable archive, trying the next" >&2
            continue
        fi
        # The package ships libvulkan.so.1 as a symlink to libvulkan.so.1.4.357;
        # -L so the rootfs gets a real file (a dangling intra-package symlink
        # would be worse than no loader at all).
        cp -L "$PKG_EXTRACT/usr/lib/libvulkan.so.1" "$LOADER_SO"
        rm -rf "$PKG_EXTRACT"
        chmod 755 "$LOADER_SO"
        echo "==> loader $PKG_VERSION: $(sha256sum "$LOADER_SO" | cut -c1-16)…"
        staged=1
        break
    done
    [ "$staged" = "1" ] || {
        echo "ERROR: no mirror served $LOADER_PKG. Tried:" >&2
        for mirror in $ALARM_MIRRORS; do echo "         $mirror" >&2; done
        exit 1
    }
fi

echo "==> staged $INSTALL_DIR"
ls -l "$INSTALL_DIR"
