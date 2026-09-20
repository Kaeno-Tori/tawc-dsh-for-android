#!/bin/sh
# Switch a container from one Vulkan accelerator to another.
#
# Usage: switch.sh <system|turnip>
#
# Runs *inside* the target rootfs as root. Invoked by the app's settings
# screen when the pick changes from one accelerator to the other -- rarely
# needed as a separate file, but it is the only path that gets the ordering
# right, and doing it in one process means a failure can't leave the rootfs
# half-provisioned with no record of what happened.
#
# Always cleans first, even when nothing was installed: enable-*.sh refines
# the backup of /usr/lib/libvulkan.so.1 rather than replacing it, so without
# a clean the *previous* accelerator's loader would be what gets preserved as
# "the distro's own" and cleanup could never get back to the original.
#
# See TAWC_DSH_DESIGN.md §11.4.

set -eu

target="${1:-}"
here=$(dirname "$0")

case "$target" in
    system | turnip) ;;
    *)
        echo "usage: switch.sh <system|turnip>" >&2
        exit 2
        ;;
esac

"$here/cleanup.sh"
"$here/enable-$target.sh"

echo "tawc-vulkan: switched to $target"
