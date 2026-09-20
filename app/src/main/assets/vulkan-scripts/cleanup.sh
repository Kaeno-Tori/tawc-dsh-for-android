#!/bin/sh
# Undo whatever enable-turnip.sh / enable-system.sh installed.
#
# Runs *inside* the target rootfs as root. Invoked by the app's settings
# screen ("Vulkan accelerator" -> Off) and by switch.sh. Idempotent: safe
# to run when nothing was ever enabled.
#
# Restores /usr/lib/libvulkan.so.1 from the backup taken at enable time
# rather than just deleting it, so a distro that shipped its own loader
# gets it back untouched.
#
# See TAWC_DSH_DESIGN.md §11.4.

set -eu

STATE_DIR=/usr/lib/tawc/vulkan
LIB=/usr/lib/libvulkan.so.1
BACKUP=$STATE_DIR/backup/libvulkan.so.1
ICD_DIR=/usr/share/vulkan/icd.d
PROFILE=/etc/profile.d/tawc-vulkan.sh

echo "tawc-vulkan: cleaning up"

if [ -e "$BACKUP" ] || [ -L "$BACKUP" ]; then
    rm -f "$LIB"
    mv "$BACKUP" "$LIB"
    echo "  restored the distro's $LIB from backup"
else
    # No backup means there was no loader here before we started, so the
    # one present is ours to drop. A loader we never touched is left alone.
    if [ -e "$STATE_DIR/state" ]; then
        rm -f "$LIB"
        echo "  removed $LIB (we installed it)"
    fi
fi

# Only our own manifests -- a distro-provided ICD json stays.
for f in "$ICD_DIR"/tawc-*.json; do
    [ -e "$f" ] || continue
    rm -f "$f"
    echo "  removed $f"
done

rm -f "$PROFILE" "$STATE_DIR/state"
rmdir "$STATE_DIR/backup" 2>/dev/null || true

echo "tawc-vulkan: cleanup done"
