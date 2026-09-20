# Building tawc-dsh

> **Source of truth for build dependencies and the fresh-system build flow.**
> Keep this file in sync with the `scripts/build-*.sh` scripts and Gradle config.
> Whenever you add or change a build-time dep — host package, vendored
> repo, env var, toolchain version — update this doc in the same change.
> AGENTS.md instructs agents to consult and update this file when building.

This doc describes building on a Linux x86_64 host. macOS/Windows are not
supported as build hosts; nothing is fundamentally portable-hostile, just
untested.

## Quick reference

```bash
# One-time: install host deps (see "Host packages" below)

# Each iteration:
scripts/build-app.sh
```

Vendored git repos listed in `deps/deps.list` are auto-cloned by their
respective build/setup scripts on first invocation - no manual clone step
is needed. Gradle drives those scripts ahead of APK assembly, so a fresh
clone goes straight to `scripts/build-app.sh`. To force a component rebuild by
hand, use the matching script under `scripts/` or `tawcroot/build.sh`.

The result is `app/build/outputs/apk/debug/app-debug.apk`. Install
and launch as documented in AGENTS.md's Common Commands.

## Host packages

### Always required

| Component | Arch (`pacman -S`)                                     | Debian/Ubuntu (`apt install`)                        |
|-----------|--------------------------------------------------------|------------------------------------------------------|
| JDK 21    | `jdk21-openjdk`                                        | `openjdk-21-jdk`                                     |
| Rust      | `rustup` — the version is pinned by `rust-toolchain.toml` at the repo root (currently 1.93.0), so rustup installs it on first build; no `rustup default` needed | `rustup` (a real Debian/Ubuntu package since trixie; rustup.rs works too) |
| Rust Android targets (`error[E0463]: can't find crate for \`core\`` if missing) | Both Android targets are listed in `rust-toolchain.toml`, so rustup adds them automatically. Manually: `rustup target add aarch64-linux-android` (add `x86_64-linux-android` for emulator builds). They cover the `andobridge` JNI library Gradle cross-builds for the APK. | same |
| Cargo NDK (cargo subcommand — `cargo build` will fail with `error: no such command: ndk` if missing) | `cargo install cargo-ndk --version 4.1.2 --locked` (known-good; `cargo install cargo-ndk` for latest) | same |
| Android SDK + NDK | install Android Studio, or use `sdkmanager` directly. Android platform API 36 is required by `compileSdk`; NDK version pinned in `app/build.gradle.kts` (currently 27.2.12479018). The SDK's `cmdline-tools` (for `apkanalyzer`, used by `scripts/check-no-dev-code.sh` on the release APK) and `build-tools` (zipalign/apksigner/aapt2) are both needed for `scripts/build-release-apk.sh`. | same |
| Build basics | `base-devel`                                        | `build-essential pkg-config curl libarchive-tools`   |
| Meson + Ninja (Turnip) | `meson ninja`                            | `meson ninja-build`                                  |
| Wayland host tools (libhybris cross-build) | `wayland wayland-protocols` | `libwayland-dev libwayland-egl-backend-dev wayland-protocols` (Arch's `wayland` carries `wayland-egl-backend.h`; Debian splits it out, and libhybris's wayland EGL platform includes it) |
| Autotools (libhybris cross-build) | `autoconf automake libtool` | `autoconf automake libtool libtool-bin` (Debian ships the `libtool` binary itself in `libtool-bin`, and `build-libhybris.sh` checks for it) |
| Vulkan headers (libhybris cross-build) | `vulkan-headers`        | `libvulkan-dev`                                      |
| X11/xcb headers (libhybris's X11 EGL platform, `eglplatform_x11.so`) | `libx11 libxcb`   | `libx11-dev libx11-xcb-dev libxcb1-dev`              |
| `patchelf` (libhybris GL shims) | `patchelf`                  | `patchelf`                                           |
| `file` (libhybris build verify step) | `file`                 | `file`                                               |
| nginx (dev-time mirror cache, optional) | `nginx`                       | `nginx`                                              |

On a clean Debian trixie the whole set is one line — verified by building
a release APK from scratch in a container with none of it
preinstalled:

```bash
apt install git openjdk-21-jdk rustup build-essential make pkg-config curl \
    libarchive-tools file meson ninja-build bison autoconf automake libtool \
    libtool-bin libltdl-dev perl python3 python3-libxml2 xsltproc libexpat1-dev \
    libwayland-dev libwayland-bin libwayland-egl-backend-dev wayland-protocols \
    libvulkan-dev patchelf libx11-dev libx11-xcb-dev libxcb1-dev \
    gcc-aarch64-linux-gnu g++-aarch64-linux-gnu \
    binutils-aarch64-linux-gnu libc6-arm64-cross linux-libc-dev-arm64-cross
cargo install cargo-ndk --version 4.1.2 --locked
```

Three of those are easy to miss when the packaging differs from Arch's,
and each one fails deep into a cross-build rather than up front:
`libtool-bin` (Arch folds it into `base-devel`),
`libwayland-egl-backend-dev` (Arch keeps it inside `wayland`), and the
`-dev` split for X11/xcb, which Arch does not have at all.

That line covers all three graphics members (`libhybris`, `turnip`,
`none` — the last is the no-driver state, not a backend). `proot` builds
its own talloc.

JDK 26 is **not** supported for running this Gradle build — Gradle 8.12's
embedded Kotlin stack crashes while parsing Java version `26.0.1`. The repo
pins the Gradle daemon to Java 21 in `gradle/gradle-daemon-jvm.properties`, so
direct `./gradlew ...` works when a JDK 21 install is available even if the
shell's default `java` is newer.

`nginx` is only needed for the dev-time install caching proxy
(`scripts/cache-proxy.sh`, see notes/cache-proxy.md). Skip if you
don't iterate on installs.

### aarch64 glibc cross-toolchain (libhybris)

| Distro | Packages |
|--------|----------|
| Arch   | `aarch64-linux-gnu-gcc aarch64-linux-gnu-binutils aarch64-linux-gnu-glibc aarch64-linux-gnu-linux-api-headers` |
| Debian/Ubuntu | `gcc-aarch64-linux-gnu g++-aarch64-linux-gnu binutils-aarch64-linux-gnu libc6-arm64-cross linux-libc-dev-arm64-cross` |
| Fedora | `gcc-aarch64-linux-gnu binutils-aarch64-linux-gnu glibc-aarch64-linux-gnu` |

The toolchain produces aarch64 **glibc** binaries. We do **not** use the
NDK for libhybris because libhybris is glibc-side by design (its
`hooks.c` exports glibc-shaped symbols and is loaded by glibc programs
inside the chroot — see `notes/gpu-strategy.md`). The NDK
targets bionic and is the wrong toolchain.

For the rest of our native build (proot, tawcroot, the ando client, and
the `andobridge` Rust library) the NDK is correct and we keep using it.

## Environment variables

Gradle needs an Android SDK location. Set one of:

- repo-local `local.properties` with `sdk.dir=/path/to/Android/Sdk`
- `ANDROID_HOME=/path/to/Android/Sdk`
- `ANDROID_SDK_ROOT=/path/to/Android/Sdk`

`local.properties` is gitignored because the path is host-specific. On this
dev machine it is:

```properties
sdk.dir=/home/ai/Android/Sdk
```

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018
```

`scripts/build-app.sh` sets `JAVA_HOME` and `ANDROID_HOME` to the defaults
above when they are unset. `ANDROID_NDK_HOME` is auto-detected by
`scripts/build-proot.sh`
(it falls back to `$ANDROID_HOME/ndk/<latest>`). Direct `./gradlew` invocations
use the repo's Gradle daemon JVM pin and require JDK 21 to be installed.

## Vendored repos

All gitignored. The pinned commit + repo URL for every vendored git dep
lives in **[`deps/deps.list`](../deps/deps.list)** — single source
of truth. Build scripts source `scripts/lib/deps.sh` and call
`dep_ensure <name>`, which clones if missing and **errors loudly** if
the existing checkout is at the wrong commit (uncommitted edits are
silently tolerated as long as HEAD matches the pin).

On top of that, any `dep_ensure`/`dep_apply_patches` call first verifies
*every* existing checkout against its pin (missing checkouts are skipped —
they're cloned on demand), so a build that touches one dep fails on drift
in any other. Gradle builds also run `scripts/ensure-deps.sh --verify-all`
via the never-up-to-date root `verifyDeps` task, wired to every module's
`preBuild`, which catches drift even when every dep-consuming task is
cached — including a termux-derived module built alone. The settings-eval
`ensure-deps.sh termux-app` runs through `providers.exec`, so the
configuration cache records its output as an input and re-verifies pins
even on cache-hit builds.

Dep-built artifacts also track checkout *content*: every dep-artifact
Gradle task (`buildLibhybris`, `buildProot`, `buildTurnip`, …) declares
`scripts/ensure-deps.sh --tree-state <dep|dest-prefix/>...` — HEAD plus
a hash of tracked-file edits per consumed dep — as an input property,
so local edits in a dep tree, and their later discard by
`update-deps.sh`, rebuild the artifact instead of letting it ship
stale. A missing checkout fingerprints as "clean at pin" (what a fresh
clone produces), so deleting a drifted checkout also triggers a
rebuild. Untracked files are deliberately not fingerprinted — they
survive `dep_reset`, so they can't be silently discarded; when
iterating on one, run the build script directly. Expect one extra
(script-incremental) task re-run after a build that itself rewrites
tracked dep files (patch-set change, first autotools regen) — the
fingerprint settles on the next build.

`update-deps.sh` additionally `git clean -fdx`s a checkout when its pin
actually moves, so stale in-tree configure state (and untracked WIP)
doesn't leak across commits; a same-pin reset leaves untracked files
alone.

| Path                                  | Used by                                       |
|---------------------------------------|-----------------------------------------------|
| `./deps/libhybris/`                        | `scripts/build-libhybris.sh`              |
| `./deps/android-headers/`                  | `scripts/build-libhybris.sh`              |
| `./deps/proot/` (+ `./deps/proot-deps/talloc-*` tarball) | `scripts/build-proot.sh`                |
| `./deps/cleat/`                            | `tawcroot/build.sh` (host + device test runners) |
| `./deps/termux-app/`                       | Gradle included projects `:terminal-emulator`, `:terminal-view` and `:termux-extrakeys` (in-app terminal; ensured at settings-evaluation time by `settings.gradle.kts`) |
| `./deps/debootstrap/`                      | `scripts/ensure-deps.sh debootstrap`; packed into an asset by Gradle's `packDebootstrap` |
| `./deps/mesa-turnip/`                      | `scripts/build-turnip.sh` (the freedreno Vulkan driver) |

One tarball dep (`talloc`) is *not* in `deps.list` — it ships as a
release tarball, not a git repo, so its pin is a version + sha256 pair
in the build script itself:

| Dep     | Pinned in                    | Variables                              |
|---------|------------------------------|----------------------------------------|
| `talloc`| `scripts/build-proot.sh`     | `TALLOC_VERSION`, `TALLOC_SHA256`      |

It goes through `dep_fetch_tarball <url> <sha256> <dest>` from
`scripts/lib/deps.sh`, which downloads when the file is absent and
verifies the hash every time — so a truncated or tampered cached
download fails as loudly as a fresh bad one. To bump it, change the
version and the hash together; the mismatch error tells you the hash it
actually got.

The *extract* is still not re-verified after unpacking: hand edits or
corruption in a `talloc-*` tree are invisible until the next version
bump (accepted — delete the extract to force a clean re-fetch).

### Bumping a dep

1. `cd <dep>; git checkout <new commit>; iterate; (push if needed)`
2. Edit `deps/deps.list` — bump the commit column.
3. On every checkout that needs to follow: `scripts/update-deps.sh`
   (or `scripts/update-deps.sh <name>` for a subset). This is the only
   command that mutates dep checkouts behind your back.

If you bumped commits but forgot to update `deps.list` (or the other
way round), the next build fails with a clear "dep is at the wrong
commit" error. There is intentionally no auto-update — silent drift
is what this whole system exists to prevent.

Vendoring (rather than fetching at build time) follows the same pattern
across all our cross-builds: deterministic builds, offline-capable,
no surprise tarball changes between runs.

## Per-component build instructions

Gradle invokes every cross-build below automatically before assembling
the APK — `scripts/build-app.sh` from a fresh clone is enough.
Run the standalone scripts only when iterating on the component itself
(faster than a full Gradle round-trip).

### debootstrap (asset tar, no compilation)

`deps/debootstrap` (upstream salsa.debian.org, pinned in
`deps/deps.list`) is packed verbatim — entry script, `functions`,
`scripts/` with symlinks preserved — into
`app/build/generated/tawc-assets/debootstrap/debootstrap/debootstrap.tar`
by the `packDebootstrap` Gradle task. No host deps beyond `tar`.
Consumed at runtime by the Debian *packages* bootstrap flavor
(notes/installation.md "Bootstrap flavors"), which extracts it into the
per-install bootstrap workspace.

The generated dir is registered as an assets srcDir only on the build
types that ship the packages flavor — debug by default, never release
— so a production APK carries no debootstrap, and the pack task (plus
the `deps/debootstrap` checkout it needs) is skipped entirely when no
variant wants it. `-PtawcBootstrapPackages=true|false` overrides both
build types; see [installation.md](installation.md) "Bootstrap
flavors". Bump the pin deliberately via
`scripts/update-deps.sh debootstrap` and re-run the on-device packages
install afterwards — debootstrap is unpatched by design; if a local
patch ever becomes necessary, fork like libhybris rather than sedding
at build time.

### libhybris (shared .so set → ships in APK as asset)

Cross-built once. aarch64-linux-gnu-gcc against glibc.

```bash
scripts/build-libhybris.sh           # incremental
scripts/build-libhybris.sh --clean   # distclean + rebuild
```

Output: `build/libhybris-aarch64/install/usr/lib/hybris/`, the
on-device install layout (`libhybris-common.so{,.1,.1.0.0}`,
`libEGL.so{,.1,.1.0.0}`, `libGLESv2.so{,.2,.2.0.0}`,
`libGLESv1_CM.so{,.1,.1.0.1}`, `libvulkan.so{,.1,.1.2.183}`,
`libsync.so{,.2,.2.0.0}`, plus the `libhybris/` plugin tree and
`libhybris/linker/q.so` for the Android 10+ bionic linker, and the
generated `gl-shims/` directory). `scripts/build-libhybris.sh` configures
`--prefix=/usr/lib/hybris --libdir=/usr/lib/hybris`, so libhybris's
RUNPATH, plugin dirs, and linker plugin dir already match the rootfs
copy location. No `HYBRIS_*_DIR` env overrides are needed.

The build is in-tree (`builddir == sourcedir`) because some libhybris
subdirs reference wayland-scanner-generated headers via `-I`s that
only resolve when builddir == srcdir. `--clean` runs `make distclean`
on the source tree.

Bundled into the APK by the Gradle `packLibhybris` task as
`app/src/main/assets/libhybris/arm64-v8a.tar`. Extracted to
`<filesDir>/libhybris/` by `TawcAssets.ensureLibhybrisExtracted` —
called from `TawcInstaller` during install / APK upgrade and from
`TawcrootMethod` on every spawn — then exposed in each rootfs at
`/usr/lib/hybris/`: bound RO by `TawcrootMethod` under tawcroot, copied
by `LibhybrisInstallProvider` under proot/chroot.
End-to-end automatic — no manual steps after `scripts/build-app.sh`.

#### Why the cross-compile and not the NDK

libhybris is loaded by glibc programs in the chroot. Its
`hooks.c` exports glibc-shaped wrappers (e.g. `__sprintf_chk`,
`pthread_attr_setstackaddr`, `valloc`) for the bionic vendor blobs
it loads via its embedded Android linker (`libhybris/linker/q.so`).
Building with the NDK produces a bionic-linked binary that no glibc
client can `dlopen` — the spike that uncovered this is not worth
redoing; use the glibc cross-toolchain.

#### Why a `-idirafter` hack appears in the build script

`server_wlegl.cpp` includes `<wayland-server.h>` unconditionally
(even when `--disable-wayland_serverside_buffers` is set), so the
wayland include dir has to be on the compiler's search path globally.
We add it via `-idirafter` rather than `-I` because `-I/usr/include`
shadows the cross-glibc's `stdint.h` with the host x86_64 version,
and host `bits/wordsize.h` gates LP64 on `__x86_64__` being defined
— compiling for aarch64 then collapses `uintptr_t` to `unsigned int`
(32-bit) and fails build with cast-precision errors. `-idirafter`
keeps wayland headers findable while letting the cross-glibc's
`stdint.h` win.

#### Why we skip `common/{mm,n,o}` linker subdirs

`common/Makefile.am` builds four bionic-linker plugin variants
(`mm`/`n`/`o`/`q`) corresponding to Android 6/7/8/10. We target
Android 10+ (matches our `minSdk=29`), so libhybris will only ever
load `q` at runtime. The legacy `mm` plugin doesn't compile clean
under gcc 15 (a `format string` mismatch the upstream code never
fixed); skipping it avoids the build break in code we don't ship.
The build script invokes `make` per-subdir to control this.

#### Headless Vulkan platform plugin (`vulkanplatform_null.so`)

The build overwrites libhybris's autotools-built
`libhybris/vulkanplatform_null.so` with one compiled from
`deps/libhybris-shims/vulkanplatform_null.c`.

libhybris's `libvulkan.so.1` won't make a Vulkan call until
`hybris/vulkan/ws.c` has dlopen'd `vulkanplatform_$HYBRIS_VULKANPLATFORM.so`,
and that variable defaults to `wayland`. Upstream's `null` plugin is the
headless answer, but its Makefile links `$(WAYLAND_SERVER_LIBS)`
unconditionally, plus `libgralloc` and `libhybris-vulkanplatformcommon`
(which in turn DT_NEEDEDs wayland-client/server and libsync) — so even
the null plugin needs glibc Wayland in the chroot. Without it `_init_ws()`
assert()s and every Vulkan client dies before logging anything
(TAWC_DSH_DESIGN.md §11.1). `RootfsEnv` sets `HYBRIS_VULKANPLATFORM=null`
for the LIBHYBRIS backend, so this copy is the plugin that actually loads.

Ours is the same pass-through as upstream's with those links dropped:
`DT_NEEDED` is `libc.so.6` alone. The path is compute-only by design (no
WSI, no swapchain), so the three Wayland surface entry points in
`struct ws_module` stay stubs returning `VK_ERROR_OUT_OF_HOST_MEMORY` /
`VK_FALSE`, exactly as upstream leaves them.

Two details are load-bearing:

- `-I"$BUILD_DIR"`: `ws.h`'s `struct ws_module` layout is gated on
  `WANT_WAYLAND` from the libhybris `config.h`. Compile against a
  different config and the member offsets shift, so libhybris calls the
  wrong slot.
- `-idirafter` for the host wayland/vulkan include dirs, same reason the
  libhybris build itself needs it (see above).

The script gates the result: `readelf -d` must show no `libwayland*`,
`libgralloc` or `libhybris*` in `DT_NEEDED`.

### Turnip (freedreno Vulkan ICD + loader → ships in APK as assets)

```bash
scripts/build-turnip.sh                 # incremental
scripts/build-turnip.sh --clean         # wipe the meson + staged trees
```

Output: `build/turnip-aarch64/install/usr/lib/turnip/` holding
`libvulkan_freedreno.so` (Mesa 26.2.2, `-Dfreedreno-kmds=kgsl`),
`freedreno_icd.json` with `library_path` baked to that guest path, and
`libvulkan.so.1` — the Vulkan loader, lifted from Arch Linux ARM's
`vulkan-icd-loader` (resolved out of the mirror's repo database, not by
filename). aarch64 only. Bundled into the APK by Gradle's `packTurnip`
and exposed in every rootfs at `/usr/lib/turnip/` (tawcroot RO bind;
`TurnipInstallProvider` copy under proot/chroot).

This build needs no host sysroot and no pkg-config: every optional Mesa
dependency is deliberately invisible (the cross file's `pkg-config` plus
an exported `PKG_CONFIG_LIBDIR`), so it comes out Vulkan-only and
WSI-less — a compute driver. `deps/mesa-turnip` is the only Mesa pin
left, at 26.2.2: Turnip has to be 26.2.2 rather than 25.3.6 because
25.3.6 SIGSEGVs the container on the q4_K `MUL_MAT` shapes llama.cpp
emits. See TAWC_DSH_DESIGN.md §11.1.

### andobridge (Rust JNI library → bundled in APK by Gradle)

The `andobridge` crate is the JNI shell over `ando-broker`
([ando.md](ando.md)); it is the only Rust library the APK build
cross-compiles now that the compositor crate is gone. NDK clang against
bionic, via `cargo-ndk`. Invoked by Gradle automatically
(`buildAndoBridge<Abi>` + `copyAndoBridge<Abi>`, which stage
`libandobridge.so` under `jniLibs/`); no separate command needed.

`cargo-ndk` is a cargo subcommand that has to be installed once per
user (`cargo install cargo-ndk` — also listed in the Host packages
table). Without it, the Rust build fails with `error: no such command:
ndk`.

```bash
# Manual invocation (Gradle does this for you):
cd andobridge && \
    cargo ndk --target arm64-v8a --platform 29 -- build --release
```

### proot (Termux fork → ships in APK as jniLib)

Cross-built once per ABI. NDK clang against bionic. Output:
`app/src/main/jniLibs/<abi>/libproot.so` + `libproot-loader.so`.
Auto-invoked by Gradle's `buildProot<Abi>` task; standalone:

```bash
scripts/build-proot.sh                # current host's primary ABI
scripts/build-proot.sh --abi=both     # both Android ABIs
scripts/build-proot.sh --clean        # wipe and rebuild
```

See [proot.md](proot.md) for why we use Termux's fork.

### tawcroot (systrap proot replacement → ships in APK as jniLib)

Cross-built once per ABI. NDK clang against bionic, static non-PIE
ET_EXEC, `-nostdlib` freestanding. Output:
`app/src/main/jniLibs/<abi>/libtawcroot.so`. Auto-invoked by
Gradle's `buildTawcroot<Abi>` task; standalone:

```bash
tawcroot/build.sh --abi=aarch64      # explicit Android ABI
tawcroot/build.sh --abi=both         # both Android ABIs
tawcroot/build.sh --abi=host         # native glibc, runs on dev box
tawcroot/build.sh --testhost         # also build testhost twin
tawcroot/build.sh --tests            # also build cleat orchestrator
```

The host test build (`--abi=host`, driven by `tawcroot/Makefile`) is
sanitized: the cleat `tests` orchestrator gets ASan+UBSan, the host
`tawcroot`/`tawcroot-testhost` binaries get trap-mode UBSan. Needs
gcc's `libasan`/`libubsan` runtimes, which ship with the same
distro gcc packages listed above — no extra host package. The NDK
cross-builds (the shipped artifacts) are untouched.

See [tawcroot](tawcroot/README.md) for the design.

### ando client (→ ships in APK as jniLib)

The guest-side client for the ando broker (`ando <cmd>` — run an
Android command from inside the rootfs; see [ando.md](ando.md)).
Single-file C, NDK clang `-static` against bionic's libc.a (static so
the tawcroot loader needs no `/system/bin/linker64` or per-distro
glibc). Output: `app/src/main/jniLibs/<abi>/libando.so`; installed
into each rootfs at `/usr/local/bin/ando` by `AndoInstallProvider`.
Auto-invoked by Gradle's `buildAndo<Abi>` task; standalone:

```bash
tawcroot/ando/build.sh --abi=aarch64
tawcroot/ando/build.sh --abi=x86_64
tawcroot/ando/build.sh --abi=both
```

### APK assembly

```bash
scripts/build-app.sh
```

Builds `arm64-v8a` by default, or `x86_64` when `ANDROID_SERIAL` or
`.tawctarget` points at an emulator. Use `--abi=arm64-v8a`,
`--abi=x86_64`, or `--abi=both` to override.

Invokes the `andobridge` Rust cross-build and copies its output into
`jniLibs/<abi>/`; cross-builds proot (when the method is enabled),
tawcroot, and the `ando` client; builds/packs libhybris for arm64; builds
and packs the Turnip assets for arm64; then produces
`app/build/outputs/apk/debug/app-debug.apk`.
Everything the supported install/runtime paths need ships inside this
APK.

Graphics backend builds are controlled by Gradle's
`-PtawcGraphics=libhybris,turnip,none`. The display-only backends
(`gfxstream`, `libhybris-zink`) are gone with the compositor, so the set
has no other members — and only two of those three are drivers: `none`
is the "no driver provisioned" state, not a backend
([gpu-strategy.md](gpu-strategy.md), "Why `NONE` is not a CPU backend").
`scripts/build-release-apk.sh` defaults to the same set, so a production
APK gets the same members (override with `--graphics=...` or
`TAWC_RELEASE_GRAPHICS`).

`-PtawcAllFilesAccess=false` strips `MANAGE_EXTERNAL_STORAGE` from the
manifest (build-type overlay `app/src/overlays/no-all-files-access/`)
for a build that would rather not ship a broad permission; the app hides
the external-binds UI when the permission is absent. See
[external-binds.md](external-binds.md).

### Third-party license text (checked-in asset)

```bash
scripts/gen-third-party-licenses.sh
```

Regenerates `app/src/main/assets/licenses.json`, the data behind
Settings > About > "Licenses" (`LicensesActivity` and
`LicenseSectionActivity`). The distributed APK is GPLv3 (termux-shared's
extra-keys widget), and the permissive licenses on everything else
require their notices to ship with the binary — this asset is how both
obligations are met. See [licensing.md](licensing.md).

Regular builds never run it: the output is checked in. Re-run it after
changing a Gradle dependency, a `deps/` pin, or a Rust crate, and
commit the result. Inputs, all read from the working tree:

- `LICENSE` / `LICENSE.MIT` — the GPLv3 text and tawc-dsh's own terms
- `deps/**/{LICENSE,COPYING}*` — vendored native and Java sources
- `cargo metadata` for the Rust crates, with per-crate texts read out of
  the local `~/.cargo` registry checkout
- `./gradlew :app:dependencies --configuration releaseRuntimeClasspath`
  for Maven artifacts, mapped to licenses by the `GRADLE_LICENSES`
  table in the script
- `licenses/` — checked-in texts for the few artifacts whose license
  lives only in a POM or on a project website

So it needs populated dep checkouts and a warm cargo registry. It fails
loudly rather than silently omitting a component: an unmapped Maven
coordinate or a vendored checkout with no license file is an error, so
new dependencies have to be classified before the file regenerates.

Output shape matters for the UI. Components are grouped by license
*family* so the index screen is ~14 rows rather than one endless page,
and identical texts are shared (deduplicated on a whitespace-normalized
key, so indentation differences don't scatter one Apache-2.0 into a
dozen entries). Texts are never rewritten for grouping — only the key is
normalized. Hard-wrapped prose is reflowed into single paragraphs so
Android can rewrap it to the screen; blocks that don't look like prose
stay verbatim and render monospace.

### App icon (checked-in generated assets)

**`app/icon.svg` is the source of truth.** Every other form of the mark is
generated from it by `scripts/gen-icon.sh`:

| Generated file | Where it shows up |
|----------------|-------------------|
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | foreground layer of the adaptive launcher icon (`mipmap-anydpi-v26/ic_launcher.xml`) — the home screen and the app switcher |
| `app/src/main/res/drawable/ic_tawc_logo.xml` | the mark at full size, no safe-zone scale |
| `app/src/main/res/values/icon_colors.xml` | `tawc_icon_bg`, the adaptive icon's background layer |

`mipmap-anydpi-v26/ic_launcher.xml` is hand-written — it only wires the two
layers together and has no artwork in it.

To change the icon:

1. Edit `app/icon.svg` in Inkscape.
2. Run `scripts/gen-icon.sh`.
3. Commit the SVG and all four generated files together.

```bash
scripts/gen-icon.sh           # regenerate everything
scripts/gen-icon.sh --check   # verify the checked-in files match the SVG
scripts/gen-icon.sh --size=N  # store icon at another size
```

Nothing runs the generator automatically: the app build must not depend on
an SVG rasteriser, and the icon changes about once a year. `--check` is the
guard against the checked-in files drifting from the source; it needs no
rasteriser for the vector outputs.

Constraints on the SVG, all enforced by the script — it fails naming what
it could not translate rather than quietly dropping it from the icon:

- **Paths only.** The translation to Android's vector format is structural
  (`pathData` is SVG path syntax), so shapes, strokes and text have to
  become paths first: in Inkscape, select all, then Path > Object to Path
  (and Path > Stroke to Path for strokes). Empty text frames left behind by
  that conversion are ignored.
- **Flat fills.** No gradients or patterns.
- **Group transforms** may be translate/scale/rotate, or a matrix with no
  rotation or skew; each becomes a nested Android `<group>`.
- **The background colour is the SVG page colour** (Inkscape: Document
  Properties > Background), not a drawn rectangle — that is what
  `icon_colors.xml` is generated from.

The safe-zone scale (0.60) lives in the script. Android masks the outer
edge of an adaptive icon away, so the foreground has to sit inside the
central safe zone.

## Install and launch

```bash
scripts/app-build-install.sh
```

Picks the device from `.tawctarget` / `TAWC_TARGET` via
`scripts/lib/select-device.sh`, builds through `scripts/build-app.sh`,
installs, force-stops, and launches `MainActivity`. Flags: `--no-build`
to reuse the existing APK; `--no-launch` to install without starting
(used by `run-integration-tests.sh`).

Installing or upgrading the APK causes the next app start to re-extract
bundled runtime assets and re-run `TawcInstaller` against existing
rootfs metadata when the `tawcStamp` changes. Under tawcroot the
libhybris / Turnip trees are RO-bound from the extract, so they track
the APK with no per-rootfs copy; proot/chroot rootfses get real-file
copies under the same `/usr/lib/...` namespaces via the
provider/manifest mechanism. See notes/installation.md "Copy vs bind".

## Device setup

SELinux enforcing mode is supported. `ChrootMounter` applies the needed
SELinux policy rule (`type_transition magisk tmpfs file appdomain_tmpfs`)
via `magiskpolicy --live` on every chroot entry.

## Integration tests

See [testing.md](testing.md) for full details.

```bash
scripts/run-integration-tests.sh           # package setup, deploy, cargo test
```

## App unit tests

Host-side JUnit tests for the Kotlin app live in `app/src/test/` —
install metadata/JSON parsing and the `pkgbootstrap` path, the bootstrap
mirror / HTTP helpers, rootfs cleanup, shell defaults, and the terminal
session / DSH service plumbing:

```bash
./gradlew :app:testDebugUnitTest
```

Deps: `junit:junit` plus the real `org.json:json` artifact, which
shadows the throw-on-use stubs in the mockable android.jar so
`JSONObject`-based code runs off-device.
