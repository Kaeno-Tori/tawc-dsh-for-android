#!/bin/sh
# Make the device's vendor Vulkan driver (loaded through libhybris) this
# container's default, so programs inside the rootfs get Vulkan without any
# per-spawn environment from the app.
#
# Runs *inside* the target rootfs as root. Invoked by the app's settings
# screen ("Vulkan accelerator" -> System). Idempotent.
#
# One file is symlinked and one is written:
#
#   /usr/lib/libvulkan.so.1        -> /usr/lib/hybris/libvulkan.so.1
#   /etc/profile.d/tawc-vulkan.sh  -> exports HYBRIS_VULKANPLATFORM=null
#
# Note what is deliberately *absent*: an ICD manifest. Unlike Turnip,
# libhybris's libvulkan.so.1 is a loader *replacement* -- it implements the
# vk* entry points itself and forwards to the vendor driver through
# android_dlopen() -- not an ICD, so no manifest can point at it. The vendor
# driver likewise has no manifest on this device (/vendor/etc/vulkan/icd.d/
# does not exist), which is why there is nothing to reference there either.
#
# HYBRIS_VULKANPLATFORM=null is load-bearing: before its first Vulkan call
# libhybris dlopens vulkanplatform_$HYBRIS_VULKANPLATFORM.so, the default
# being "wayland", which needs glibc Wayland in this container. `null` is the
# headless plugin the APK ships (TAWC_DSH_DESIGN.md §11.3). The app's
# RootfsEnv also sets this for processes it spawns; the profile snippet is
# what covers login shells and anything else started inside the rootfs.
#
# See TAWC_DSH_DESIGN.md §11.4.

set -eu

SRC=/usr/lib/hybris/libvulkan.so.1
STATE_DIR=/usr/lib/tawc/vulkan
LIB=/usr/lib/libvulkan.so.1
PROFILE=/etc/profile.d/tawc-vulkan.sh

echo "tawc-vulkan: enabling the system (vendor) driver"

[ -f "$SRC" ] || {
    echo "  ERROR: $SRC is missing." >&2
    echo "  The APK shipped no libhybris asset for this device/ABI. Rebuild with" >&2
    echo "  -PtawcGraphics=libhybris." >&2
    exit 1
}

mkdir -p "$STATE_DIR/backup" /etc/profile.d

# Move the distro's own loader aside, once, so cleanup.sh can put it back
# exactly as it was. `mv` rather than `cp`: it keeps ownership and
# timestamps. See enable-turnip.sh for the already-held-backup branch.
if [ -e "$LIB" ] || [ -L "$LIB" ]; then
    if [ ! -e "$STATE_DIR/backup/libvulkan.so.1" ] && [ ! -L "$STATE_DIR/backup/libvulkan.so.1" ]; then
        mv "$LIB" "$STATE_DIR/backup/libvulkan.so.1"
        echo "  moved the existing $LIB aside"
    else
        rm -f "$LIB"
    fi
fi

# libhybris's libvulkan carries RUNPATH=/usr/lib/hybris, so its own
# DT_NEEDED (libhybris-common) resolves through the symlink.
rm -f "$LIB"
ln -s "$SRC" "$LIB"
echo "  $LIB -> $SRC"

cat > "$PROFILE" <<'TAWC_EOF'
# Written by tawc (settings -> Vulkan accelerator -> System).
# libhybris's libvulkan.so.1 dlopens a platform plugin before its first
# Vulkan call; `null` is the headless one this container ships, so no glibc
# Wayland is needed. Remove together with /usr/lib/libvulkan.so.1.
export HYBRIS_VULKANPLATFORM=null
TAWC_EOF
echo "  wrote $PROFILE"

printf 'system\n' > "$STATE_DIR/state"
echo "tawc-vulkan: system driver enabled"
