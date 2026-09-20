#!/bin/sh
# Make the headless Turnip (Mesa freedreno) Vulkan driver this container's
# default, so programs inside the rootfs get Vulkan without any per-spawn
# environment from the app.
#
# Runs *inside* the target rootfs as root. Invoked by the app's settings
# screen ("Vulkan accelerator" -> Turnip). Idempotent.
#
# Two things land in the distro's standard locations:
#
#   /usr/lib/libvulkan.so.1                  -> the loader Turnip goes with
#   /usr/share/vulkan/icd.d/tawc-turnip.json -> symlink to the driver's manifest
#
# All of /usr/lib/turnip/* come from the APK's Turnip assets, which
# TurnipInstallProvider / TawcrootMethod.assetBinds already place in every
# rootfs (see TAWC_DSH_DESIGN.md §11.2). This script only wires up the
# discovery paths -- it does not install the driver.
#
# The manifest is symlinked rather than regenerated: the one in the asset tree
# already carries the absolute library_path build-turnip.sh rewrites into it
# (/usr/lib/turnip/libvulkan_freedreno.so) and the driver's real api_version,
# so a second copy here would only be a chance to disagree with it.
#
# No env var is needed: a manifest under /usr/share/vulkan/icd.d/ is found by
# any ICD loader. The distro needs one of those; if it has none, install
# `vulkan-icd-loader` in the distro first.
#
# See TAWC_DSH_DESIGN.md §11.4.

set -eu

SRC_DIR=/usr/lib/turnip
SRC_LOADER=$SRC_DIR/libvulkan.so.1
SRC_ICD=$SRC_DIR/libvulkan_freedreno.so
SRC_MANIFEST=$SRC_DIR/freedreno_icd.json
STATE_DIR=/usr/lib/tawc/vulkan
LIB=/usr/lib/libvulkan.so.1
ICD_DIR=/usr/share/vulkan/icd.d
MANIFEST=$ICD_DIR/tawc-turnip.json

echo "tawc-vulkan: enabling Turnip"

for f in "$SRC_ICD" "$SRC_LOADER" "$SRC_MANIFEST"; do
    [ -f "$f" ] || {
        echo "  ERROR: $f is missing." >&2
        echo "  Turnip ships as a three-file set. The APK has no Turnip asset for" >&2
        echo "  this device/ABI, or it was built without -PtawcGraphics=turnip." >&2
        exit 1
    }
done

mkdir -p "$STATE_DIR/backup" "$ICD_DIR"

# Move the distro's own loader aside, once, so cleanup.sh can put it back
# exactly as it was. `mv` rather than `cp`: it keeps ownership and
# timestamps, and it is the same rename whether it is a real file, a
# symlink, or another symlink to somewhere else in the rootfs.
if [ -e "$LIB" ] || [ -L "$LIB" ]; then
    if [ ! -e "$STATE_DIR/backup/libvulkan.so.1" ] && [ ! -L "$STATE_DIR/backup/libvulkan.so.1" ]; then
        mv "$LIB" "$STATE_DIR/backup/libvulkan.so.1"
        echo "  moved the existing $LIB aside"
    else
        # A backup is already held, so this one is left over from a previous
        # enable (or the state file was lost) — ours to drop.
        rm -f "$LIB"
    fi
fi

# Symlink rather than copy: /usr/lib/turnip is a read-only bind under
# tawcroot, so the 15 MB pays once and the link cannot go stale against it.
rm -f "$LIB"
ln -s "$SRC_LOADER" "$LIB"
echo "  $LIB -> $SRC_LOADER"

rm -f "$MANIFEST"
ln -s "$SRC_MANIFEST" "$MANIFEST"
echo "  $MANIFEST -> $SRC_MANIFEST"

printf 'turnip\n' > "$STATE_DIR/state"
echo "tawc-vulkan: Turnip enabled"
