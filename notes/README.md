# Notes Index

Start here when looking for durable project context. `AGENTS.md` keeps only always-needed operating rules; detailed design/build/test information belongs in these notes. Future-work plans live in [`../plans/`](../plans/).

## Build, Install, Test

- [building.md](building.md) - host packages, env vars, vendored deps, component builds, APK assembly.
- [installation.md](installation.md) - in-app installer, distro state machine, install methods, shipped rootfs files, integrity policy.
- [external-binds.md](external-binds.md) - per-install binds of host dirs (e.g. shared storage) into the rootfs; all-files-access gating.
- [testing.md](testing.md) - integration-test layout, backend pins, test deps.
- [cache-proxy.md](cache-proxy.md) - dev mirror cache behavior and safety rules.
- [emulator.md](emulator.md) - AVD setup, rooted/rootless workflows, x86_64 limitations.
- [release.md](release.md) - versioning scheme, release prep/publish steps, keystore rules.
- [licensing.md](licensing.md) - MIT sources vs GPLv3 binary, what makes it so, and how the notice/attribution obligations are met.

## Runtime Architecture

- [architecture.md](architecture.md) - module layout.
- [android.md](android.md) - Android platform integration, chroot entry, app-private state.
- [exec-broker.md](exec-broker.md) - debug broker protocol, host helper, action model, security.
- [ando.md](ando.md) - production `ando <cmd>` broker: run Android commands from inside the rootfs.
- [rootfs-sessions.md](rootfs-sessions.md) - session invariant for rootfs entry paths.
- [log-screen.md](log-screen.md) - shared operation/log-screen UI abstraction.
- [terminal.md](terminal.md) - in-app per-distro terminal (vendored termux terminal modules, tawcroot pty spawn path).

## Graphics

- [gpu-strategy.md](gpu-strategy.md) - overall GPU strategy. Three backends: libhybris (vendor blob, headless Vulkan through our null platform plugin), Turnip (Mesa's freedreno driver over `/dev/kgsl-3d0`, the aarch64 default and the reason for the separate `mesa-turnip` pin) and CPU (software). See TAWC_DSH_DESIGN.md §11.

There are no display-stack notes any more. The Wayland compositor, its
renderer/input/clipboard/IME bridges, Xwayland, the gfxstream and
libhybris-Zink backends and the desktop launcher are all gone — see
TAWC_DSH_DESIGN.md §11.5 for what was removed and why.

## Rootfs Methods And Distros

- [tawcroot/](tawcroot/README.md) - systrap-based rootless runtime architecture and implementation notes (split into topic files).
- [proot.md](proot.md) - debug-only Termux/proot install method.
- [chroot.md](chroot.md) - debug-only root `chroot(2)` install method.
- [distro-abstraction.md](distro-abstraction.md) - distro definition/refactor notes.
- [distro-options.md](distro-options.md) - survey of viable glibc distros.

## Maintenance Rules

- Keep notes factual and current. Prefer correcting stale prose over adding warnings around it.
- Move long historical logs out of current-state sections when they start obscuring the present design.
- Link issues only for active unresolved work. When an issue is solved, delete it and move still-useful context here.
- Keep future-work plans in [`../plans/`](../plans/) instead of mixing them into current-state notes.
