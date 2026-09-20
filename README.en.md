# tawc-dsh

DSH's agent, running inside a Linux container on Android.

One app, one container: it downloads a stock Linux distribution into its own
private storage, starts a `dsh web` harness inside it, and shows DSH's own web
client in a WebView. The container gets a GPU for Vulkan compute.

This is a fork of [tawc](https://github.com/wmww/tawc) (Tess's Android Wayland
Compositor), based on upstream [`b3fff97`](https://github.com/wmww/tawc/commit/b3fff97eb7f1db26918acabe5871ff085fef8eaa)
(2026-09-09). The container layer is tawc's — `tawcroot`, the `ando` bridge, the
terminal integration, the libhybris plumbing. What this fork changes is what that
layer is pointed at: one agent harness instead of a Wayland desktop, and no
display stack at all.

> **Unofficial community build.** Not affiliated with, endorsed by, or supported
> by DeepSeek.
>
> **Early software.** This is version 0.1 and its reliability is not yet
> established — see Limitations below.

[中文](README.md)

## What it does

- **A container, not an emulator.** It unpacks Arch Linux ARM or Debian sid into
  app-private storage and runs programs in it through `tawcroot`, a
  single-process alternative to PRoot. No root, no proot, no VM.
- **DSH's real interface.** The WebView loads DSH's own web client from a harness
  on `127.0.0.1`, so sessions, tool calls and the model picker are the genuine
  thing rather than a reimplementation.
- **A GPU the container can actually use.** Two drivers ship: `libhybris` (the
  device's vendor Vulkan driver, reached through our own null platform plugin)
  and Mesa's `turnip`. Both are **compute-only** — for llama.cpp-class
  workloads, never for drawing.
- **A terminal**, using Termux's terminal widget. The Termux app is not required.
- **`ando` and storage binds**, so programs inside the container can reach
  Android-side data when you ask them to — and only then.
- **A floating dock** over the agent view: terminal, task manager, container
  management, interface scale, settings.

## Requirements

- Android 10 (API 29) or newer, `arm64-v8a`.
- **An Android tablet is recommended.** DSH's interface is desktop-shaped and feels
  cramped on a phone — especially a 16:9 one, which runs out of room in both
  directions. The interface-scale control helps, but not all the way. A phone will
  install and run it; it just is not the good experience.
- No root. (The root-requiring `chroot` method exists only in debug builds.)
- Several GB of free space. The Arch Linux ARM bootstrap alone is ~800 MB to
  download, and the unpacked container is around 1.6 GB — both exist on disk at
  once during an install.
- A network that can move that much data. The app measures mirrors before
  choosing one, and can be pointed at a specific one.
- Nothing else. DSH's own credentials live inside the container under `~/.dsh`;
  the app neither reads nor ships them.

## Install

Download `tawc-dsh-v<version>.apk` from the
[releases page](https://github.com/Kaeno-Tori/tawc-dsh-for-android/releases) —
`arm64-v8a`, signed. Version 0.1 is the first release.

It is not on any app store. To build it yourself, see below.

## Using it

On first launch the app sets up its container: pick a distribution, and it
downloads and unpacks it. When that finishes the agent view opens — DSH's own
client, served from the container.

The dock in the corner opens:

| Action | What it does |
|---|---|
| Terminal | A shell inside the container |
| Task manager | Container processes, with the option to kill them |
| Container | Distribution info, storage binds, uninstall |
| Shrink / enlarge interface | Page scale for DSH's own interface, in 5% steps |
| Settings | Appearance, interface scale, GPU backend, about, licenses |

Two settings are worth knowing about:

- **Interface scale** (50–150%). DSH's client is a desktop-shaped web app, and a
  WebView hands it as many CSS pixels as the screen has dp — which a 16:9 phone
  runs out of in both directions. This scales the page itself, not just text.
- **GPU backend.** Three choices: `libhybris` (your device's vendor driver),
  `turnip` (Mesa's), or off. Off provisions nothing — programs in the container
  then use their own CPU path, which for llama.cpp-class work needs no Vulkan at
  all. Switch if a driver misbehaves on your device.

The app is available in English and Simplified Chinese.

## Limitations

Read these before filing a bug — most of them are deliberate.

- **Reliability is not yet established.** This is 0.1, written overwhelmingly by
  agents and verified on few devices and few scenarios. Treat it as something to
  try, not something to depend on — and not for work you cannot redo.
- **DSH runs with `DSH_PERMISSION_MODE=danger-full-access`.** This is forced, not
  a preference. DSH's own confinement chain is `bwrap` → Landlock, and on
  Android both are unavailable (unprivileged user namespaces are disabled, and
  Landlock is not enabled in the kernel), so DSH's sandbox fails closed and its
  default mode would refuse to run any command. **The container is the isolation
  boundary, not DSH's sandbox.**
- **The container is not a security sandbox either.** Isolation rests on Android's
  own app sandbox plus `tawcroot`'s syscall translation, which is a
  single-process design and can be escaped by a determined program. Treat what
  runs inside as you would a program you ran yourself.
- **No graphical Linux apps.** There is no compositor and no display stack, so no
  Wayland or X11 desktop, no desktop GL, no GTK/Qt GUI. This build is for the
  agent and its tools.
- **One container per install.** It is not a multi-distro manager; switching
  distributions means uninstalling.
- **Performance beats the alternatives but is not native** — syscall
  translation costs something on every call.

## Reporting problems

Use [GitHub Issues](https://github.com/Kaeno-Tori/tawc-dsh-for-android/issues);
the bug form asks for the device, version, container and graphics backend,
which is the least it takes to act on a report. **Security issues go through
[SECURITY.md](SECURITY.md)**, privately — not the public tracker.

The in-repo `issues/` directory is this project's own working record (notes
handed between agents), not a feedback channel.

## Building

Requires the Android SDK/NDK, a Rust toolchain, and a Linux host; see
[notes/building.md](notes/building.md) for the full toolchain, the vendored
dependencies and the cross-builds (`libhybris`, `turnip`, `tawcroot`).

```bash
scripts/build-app.sh                # debug APK
scripts/app-build-install.sh        # build, install and launch on a device
scripts/build-release-apk.sh        # release APK: build, zipalign, sign, verify
```

`scripts/build-release-apk.sh` reads the keystore password from
`KEYSTORE_PASS`; see [notes/release.md](notes/release.md).

## Testing

```bash
./gradlew :app:testDebugUnitTest       # host-side unit tests
scripts/run-integration-tests.sh       # on-device integration tests
tawcroot/test.sh                       # tawcroot's own suite
```

Design and implementation notes live in [notes/](notes/README.md) — that is where
the *why* is, including the measurements behind decisions like the mirror probe
and the GPU strategy. [AGENTS.md](AGENTS.md) is the operating manual.

Like upstream tawc, this fork is agent-built; existing prose may be stale, and
the source is what is true.

## Licensing

All code outside `deps/` is MIT ([LICENSE.MIT](LICENSE.MIT)). Some vendored code
is GPLv3 (termux-shared's extra-keys widget), so the project as a whole is GPLv3
([LICENSE](LICENSE)) — which is what a built APK is conveyed under.

Per-component attribution for everything bundled in the app is in-app under
Settings → About → Licenses.

## Credits

- [tawc](https://github.com/wmww/tawc) by Tess — the container runtime
  (`tawcroot`), the `ando` broker, the terminal integration and the libhybris
  plumbing this app is built on.
- [libhybris fork](https://github.com/wmww/libhybris), pinned in
  `deps/deps.list`.
- [Termux](https://github.com/termux/termux-app), for the terminal widget.
- DSH itself is DeepSeek's; this app only hosts it.
