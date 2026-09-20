# Android Integration

## Chroot Setup

Install (once, via the dev exec broker; progress streams to your TTY
and the in-app log screen opens automatically):
```bash
scripts/tawc-exec.sh --foreground-app --action install \
    --arg id=arch \
    --arg mirrorProxy=http://127.0.0.1:8080/proxy/
```

Then drive the chroot from the host with:
```bash
scripts/rootfs-run.sh                    # interactive shell
scripts/rootfs-run.sh '<command>'        # run a command and exit
```

`rootfs-run` routes through the dev exec broker's `RUNINSIDE` request
(`tawc-exec --in-rootfs <id>`). The broker reads the install's
recorded method from `metadata.json` and dispatches to the matching
[InstallationMethod.startInside], which builds the bind table and
chroot exec fresh in Kotlin on every call. There is no on-disk
wrapper script and no `adb shell su` in this path — chroot installs
fork `su` from inside the JVM. Generic tawc env vars come
from `RootfsEnv.kt` via a `/usr/bin/env -i KEY=VAL …` wrapper around
the in-rootfs `bash -lc`, so nothing inside the rootfs needs to be
on disk between calls.

### Shell quoting

Commands sent through `tawc-exec --in-rootfs` are framed in the
broker wire protocol (length-prefixed argv), so quoting is not an
issue end-to-end. If you ever bypass the broker and use raw
`adb shell su -c '…'` directly, you'll need to handle the layered
quoting yourself:

**Critical quoting rule for `&&` / `||` in `su -c`:** When running compound
commands via adb, the outer shell (mksh) parses `&&` and `||` BEFORE `su` sees
them. This silently runs the second command as shell (uid 2000), not root:

```bash
# BROKEN: mksh splits at &&. cp runs as root, build runs as shell user.
adb shell su -c "cp /tmp/foo /chroot/tmp/ && /chroot/build.sh"

# CORRECT: inner quotes protect && from mksh.
adb shell "su -c 'cp /tmp/foo /chroot/tmp/ && /chroot/build.sh'"
```

Variable expansion like `$0` or `$KSH_VERSION` at any intermediate layer can
give misleading results. The `su` shell on Android is mksh (`/system/bin/sh`),
easily confused with the chroot's GNU bash.

## Kotlin App Structure

`MainActivity.kt` is the app's entry point (the only
`category.LAUNCHER` Activity) and a single-container state machine:
setup (one-tap default install, the custom form, or import-a-pack),
live install progress inline, failure, a completion receipt, then
straight into `dsh/DshActivity` once the container is READY.

The rest of the app code (`app/src/main/java/me/phie/tawc/`) is split
by feature package — `dsh/` (the agent dock), `terminal/`, `tasks/`,
`install/` (the chroot install / run / destroy logic; the rootfs lives
under the app's private data dir so uninstalling the app reclaims it),
`ops/`, `ando/`, `ui/`, `licenses/`. The host-side counterpart to the
in-app entry points is `scripts/rootfs-run.sh`, which routes through
the dev exec broker to [InstallationMethod.startInside]. See
[architecture.md](architecture.md) for the module layout, and
[installation.md](installation.md) for the install package map, the
broker `--action install/uninstall` CLI, and the Android 14 FGS
rationale.

When adding new app features (settings, task manager, …), put them in
their own packages under `me.phie.tawc.*`.

## Audio

Audio forwarding from the rootfs to Android is not implemented yet. The current
plan is a PipeWire-first rootfs stack bridged through app-owned endpoints under
`/usr/share/tawc/`; see [audio.md](../plans/audio.md).
