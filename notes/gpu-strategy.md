# GPU Driver Strategy

DSH gives the rootfs a GPU for **Vulkan compute** (llama.cpp-class
workloads), never for drawing to the Android screen. There is no
compositor, no Wayland/X11 path and no cross-process buffer sharing —
see TAWC_DSH_DESIGN.md §11 (容器内 GPU 供给层) and §11.5 (display
stack removal), plus [architecture.md](architecture.md).

The driver has to come from the app: an `untrusted_app` process cannot
open the DRM node a distro driver needs, and so cannot install one that
works. See "Why the driver must ship in the app" below.

## Two drivers and a no-driver state

`GraphicsBackend` (`app/src/main/java/me/phie/tawc/Settings.kt`) has
exactly three members — but only two of them are drivers. The third is
the *absence* of one, and is named `NONE` rather than `CPU` for that
reason (see "Why `NONE` is not a CPU backend" below). `RootfsEnv.build`
turns the chosen one into the spawn environment:

| Backend | Driver | In-rootfs env |
|---|---|---|
| `LIBHYBRIS` | The device's vendor GPU blob, loaded into the rootfs through our libhybris fork. | `LD_LIBRARY_PATH=/usr/lib/hybris/gl-shims:/usr/lib/hybris`; `HYBRIS_VULKANPLATFORM=null` (headless) |
| `TURNIP` | Mesa's freedreno Vulkan driver over `/dev/kgsl-3d0`, shipped by the APK. aarch64 only, no WSI. | `LD_LIBRARY_PATH=/usr/lib/turnip`; `VK_ICD_FILENAMES=/usr/lib/turnip/freedreno_icd.json` |
| `NONE` | Nothing is provisioned and no ICD is pinned, so the container's own loader finds whatever the container itself has. | none — this branch deliberately sets nothing |

`RootfsEnv` sets no `WAYLAND_DISPLAY`, `DISPLAY`, `SDL_VIDEODRIVER`,
`GDK_GL` or `HYBRIS_EGLPLATFORM` — nothing in the container renders, so
there is nothing to point them at. `HYBRIS_VULKANPLATFORM=null` stays:
libhybris's `libvulkan.so.1` refuses to run until it has `dlopen`'d a
`vulkanplatform_<name>.so`, and its default (`wayland`) would need glibc
Wayland in the container.

### Why `NONE` is not a CPU backend

It used to be called `CPU` and described as a software fallback, which
was wrong on three counts:

- **It ships nothing.** libhybris and Turnip each have an install
  provider; there is no `NONEInstallProvider`, and nothing to install.
- **The env it set was for GL, and had no consumer.** It forced
  `LIBGL_ALWAYS_SOFTWARE=1` + `GALLIUM_DRIVER=llvmpipe`, which only a
  distro's own Mesa reads. This app installs no Mesa
  (`DEFAULT_BASE_PACKAGES` plus `RUNTIME_PACKAGES` are `inetutils`, `git`,
  `less`, `nano`, `openssh`, `python`, `base-devel`, `nodejs`, `npm`), and
  with no display stack there is nothing for GL to draw to. For Vulkan the
  branch only *declined to pin an ICD* — its own comment admitted the
  payoff was conditional ("if `vulkan-swrast` is installed").
- **For the actual workload it is the wrong shape.** When no GPU driver
  works, the answer is CPU-native inference — llama.cpp's own CPU backend,
  which needs no Vulkan ICD at all. Software Vulkan is a detour, and a
  slower one.

The user-visible side had already agreed with this: the settings screen
has always called this state **"Off"** (`vulkan_driver_off`), and
`VulkanDriver.effective()` maps `NONE → OFF`. Only the code's name and the
prose around it claimed a third backend.

**What `NONE` still earns its place for**: not pinning an ICD is what
keeps the manual diagnostic open. Installing `vulkan-swrast` in a
container by hand lets the distro loader discover lavapipe — which is how
the §11 three-way comparison established that Turnip's `q4_K MUL_MAT`
crash was a driver bug rather than llama.cpp's. That needs the *state*, not
a bundled backend.

`EnabledGraphicsBackends` gates each member on a BuildConfig
`GRAPHICS_*_ENABLED` field, set from `-PtawcGraphics` in
`app/build.gradle.kts` (default and release set:
`libhybris,turnip,none`). `none` is in that list so a build can drop even
the no-driver state; it is not gating an artifact. The settings picker
only ever offers shipped backends.

### Default selection

`RootfsEnv.defaultBackend()` derives the spawn backend from
`Settings.vulkanDriver`, the settings screen's only graphics-related
pick (`OFF` → `GraphicsBackend.DEFAULT`, `SYSTEM` → `LIBHYBRIS`,
`TURNIP` → `TURNIP`), falling back to `GraphicsBackend.DEFAULT` when
that backend isn't shipped.

`GraphicsBackend.DEFAULT` is `TURNIP` on aarch64 (physical devices) and
`NONE` on x86_64 — libhybris cannot load against bionic there and there
is no kgsl device — falling back to
`EnabledGraphicsBackends.enabled.first()` when the preferred backend
isn't in the build.

The broker's per-spawn `GRAPHICS <key>` header on the RUNINSIDE form is
the only way to pin a backend by hand
([exec-broker.md](exec-broker.md)).

## Why the driver must ship in the app

Android labels the Adreno kernel driver `/dev/kgsl-3d0` as
`gpu_device` — an `untrusted_app` can open it — while the upstream DRM
node `/dev/dri/renderD128` is `graphics_device`, which no app-domain
process can open. Distro Turnip packages build only the `msm` kernel
backend and so need renderD128; a container that installs one gets a
driver it cannot use. DSH therefore cross-builds the driver and lays it
into the rootfs.

## TURNIP

`scripts/build-turnip.sh` cross-builds Mesa's freedreno Vulkan driver
for aarch64 glibc with `-Dfreedreno-kmds=kgsl -Dvulkan-drivers=freedreno
-Dplatforms=` (kgsl-only, no WSI) and stages three files:

- `libvulkan_freedreno.so` — the driver, stripped;
- `freedreno_icd.json` — the ICD manifest, `library_path` baked to the
  guest path;
- `libvulkan.so.1` — the Vulkan loader (lifted from Arch Linux ARM's
  `vulkan-icd-loader`, so the container need not install one).

The Mesa pin is `mesa-turnip` @ tag `mesa-26.2.2` in
[deps.list](../deps/deps.list) — the only Mesa pin left, since the
gfxstream/Zink Mesa tree went with the compositor. Turnip 25.3.6
SIGSEGVs on the `q4_K MUL_MAT` shapes llama.cpp emits; 26.2.2 runs the
`test-backend-ops` MUL_MAT suite clean (TAWC_DSH_DESIGN.md §11.1).
The build asserts the kgsl-only claim with a `strings | grep renderD128`
gate.

`TawcAssets.ensureTurnipExtracted` extracts the three plain files
(`assets/turnip/arm64-v8a/`, no tar) into `<filesDir>/turnip/`;
`TurnipInstallProvider` binds them read-only (tawcroot) or copies them
(proot/chroot) into each rootfs at `/usr/lib/turnip/`. Turnip is
aarch64-only, so an x86_64-only build prunes the assets and the TURNIP
env is never used.

## LIBHYBRIS

[libhybris](https://github.com/libhybris/libhybris) loads bionic-linked
Android shared libraries into glibc programs. DSH uses
[our fork](https://github.com/wmww/libhybris) (checkout in
`deps/libhybris`; patch-by-patch notes in `deps/libhybris/TAWC_FORK.md`)
with the stock-Android TLS fixes.

`scripts/build-libhybris.sh` cross-builds it and stages
`build/libhybris-aarch64/install/usr/lib/hybris/`;
`TawcAssets.ensureLibhybrisExtracted` extracts the
`assets/libhybris/<abi>.tar` into `<filesDir>/libhybris/`, and
`LibhybrisInstallProvider` binds it read-only (tawcroot) or copies it
(proot/chroot) into each rootfs at `/usr/lib/hybris/`.

The backend is headless. `deps/libhybris-shims/` holds the pieces DSH
builds on top of the fork:

- `vulkanplatform_null.c` — a self-built null Vulkan platform plugin
  (upstream's pass-through minus the `libwayland-server` / libgralloc /
  vulkanplatformcommon links), so it loads with libc alone.
  `build-libhybris.sh` compiles it over the autotools artefact and gates
  the result (`DT_NEEDED` must not mention wayland/gralloc/libhybris);
  `RootfsEnv` selects it with `HYBRIS_VULKANPLATFORM=null`. A command
  that wants to present can prefix
  `HYBRIS_VULKANPLATFORM=wayland`, but nothing in DSH needs a
  swapchain.
- `libgl-shim.c` / `libglesv2-shim.c` / `glx-stubs.c` / `glx-stubs.map` —
  the GL/GLES shims staged at `/usr/lib/hybris/gl-shims/`, which sit
  first on `LD_LIBRARY_PATH` so `dlopen("libGL.so.1")` /
  `dlopen("libGLESv2.so.2")` land on libhybris rather than the distro's
  glvnd/Mesa.

### libhybris on stock (unpatched) Android (SOLVED 2026-03-31)

All prior libhybris deployments required patched Android firmware. We
solved this:

**Problem:** Bionic's `TLS_SLOT_BIONIC_TLS` (slot -1, at `TPIDR_EL0 - 8`)
points to a ~12KB `bionic_tls` struct. The lindroid TLS thunk patcher
redirects `TPIDR_EL0` reads to `tls_hooks[]`, but slot -1 maps to
`tls_hooks[-1]` which is NULL -> SIGSEGV.

**Fix (in our libhybris fork's `hooks.c`):**
1. Changed `tls_hooks[16]` to
   `struct { void *bionic_tls_ptr; void *slots[16]; } tls_area` so that
   `slots[-1]` reads `bionic_tls_ptr` (contiguous in memory).
2. Lazy allocation: `calloc(1, 16384)` on first call per thread, stored
   in `bionic_tls_ptr`.
3. Thread wrapping: `_hybris_hook_pthread_create()` wraps
   `start_routine` to ensure allocation.

**Result:** EGL 1.5 initializes on Pixel 4a (Adreno 618), Android 16,
stock LineageOS. TLS patching is always active (no env var needed).

### libhybris on Pixel 10 Pro Fold (Tensor G5 + PowerVR) — slot 1 fix (SOLVED 2026-05-11)

Same shape as the slot -1 problem above, on a different slot.

**Problem:** Bionic libc reads `TLS_SLOT_THREAD_ID` (slot 1, at
`TPIDR_EL0 + 8`) as a `pthread_internal_t*` in every syscall wrapper's
errno-set path (`__set_errno_internal` does `str w9, [x8, #776]` where
776 is the `errno_value` field). Even with the TLS thunk patcher
correctly redirecting `tpidr_el0` reads to `tls_static_tls`, slot 1 sat
zero-initialised, so any libc-mediated syscall failure wrote through
NULL + 0x308 and SIGSEGV'd.

Hit by Pixel 10 Pro Fold (Tensor G5 + Imagination PowerVR DXT) because
`mapper.pixel.so` (Pixel's gralloc HAL) goes through bionic libc syscall
wrappers on every surface creation. Adreno and Mali gralloc HALs don't
take that path, so this bug went unnoticed on every previously-tested
device. `weston-simple-egl` was the minimal reproducer.

**Fix (in our libhybris fork's `hooks.c`):** alongside the existing
bionic_tls allocation in `_hybris_hook___get_tls_hooks`, calloc an 8 KiB
zero-filled `pthread_internal_t` shadow and write its pointer to
`tls_static_tls + BIONIC_THREAD_ID_PTR_OFFSET`. Reaped by a second
`pthread_key_t` destructor on thread exit.

**Audit of every `mrs tpidr_el0; ldr [..., #N]` pattern in stock apex
libc.so** found three other in-use slots:
- `#40` (slot 5, stack canary) — read-as-value, benign at zero.
- `#0` (slot 0, `__tls_get_addr` DTV + gwp_asan PRNG) — not reached today.
- `#48` (slot 6, scudo thread-local cache) — not reached because libhybris
  funnels every visible malloc symbol through glibc, bypassing scudo.

If any of those start crashing on a future device, the same fix shape
(populate the slot in `_hybris_hook___get_tls_hooks`) applies.

**Result:** `weston-simple-egl` renders correctly on Pixel 10 Pro Fold,
and the existing Pixel 4a / Adreno 618 hybris integration tests stay
green.

### libhybris + libwayland-client compatibility

TLS patching is always active. When linking libhybris-common.so at
compile time alongside libwayland-client, the TLS patcher's constructor
must run before any bionic library is loaded.

**dlopen approach:** Loading libhybris-common.so via `dlopen()` requires
executable stack handling. Fixed by `patchelf --clear-execstack` on
libhybris-common.so, dlopen instead of link-time dependency, and
`personality(READ_IMPLIES_EXEC)` before loading.

### Vulkan dispatch: IFUNC replaced with assembly trampolines (SOLVED)

Upstream libhybris's `vulkan.c` uses `VULKAN_IDLOAD()` which creates GNU
IFUNC symbols for every Vulkan entry point. IFUNC resolvers run during
the dynamic linker's early relocation phase. Each resolver calls
`_init_androidvulkan()` -> `android_dlopen("libvulkan.so")`, which loads
the entire Android runtime (bionic linker, vendor Vulkan driver) while
the process is still in the ELF startup phase. This crashes when loaded
alongside complex library trees like GTK4 (which links `libvulkan.so.1`
at build time via its `vulkan-icd-loader` dependency).

**Fix (in our fork):** Replaced `VULKAN_IDLOAD` with arm64 assembly
trampolines. Each symbol gets a 3-instruction stub (`adrp`+`ldr`+`br
x16`) that tail-calls through a function pointer. A
`__attribute__((constructor))` resolves all pointers via a linker-set
after relocation completes. No IFUNC resolvers, no android_dlopen during
relocation. See `vulkan.c` and `deps/libhybris/TAWC_FORK.md`.

The fork also carries a build fix for `vulkan-headers` 1.4.341+: the Cuda
NV extension block's guard was `#if VK_HEADER_VERSION >= 269`, but the NV
Cuda symbols moved behind their extension macro, so it is now
`#ifdef VK_NV_cuda_kernel_launch`.

## Container-side Vulkan (the settings pick)

Programs started *inside* the container don't get `RootfsEnv`'s env, so
they need a loader and ICD in the standard paths. `VulkanProvisionOp`
runs the matching script from `app/src/main/assets/vulkan-scripts/`
inside the rootfs, writing a loader at `/usr/lib/libvulkan.so.1` and —
for TURNIP — a manifest under `/usr/share/vulkan/icd.d/`, backing up
whatever the distro shipped so the pick can be undone. This is what
`Settings.vulkanDriver` (`OFF` / `SYSTEM` / `TURNIP`) records intent
for; app-spawned processes still get `GraphicsBackend`'s env, which wins
when the two disagree. See TAWC_DSH_DESIGN.md §11.4.

## Removed with the display stack

The compositor crate and its Kotlin side, the gfxstream bridge (its
kumquat server only existed as a thread of the compositor process) and
the libhybris-Zink backend are gone, and with them the whole
buffer-sharing path: `android_wlegl`, AHardwareBuffer handle import
(`compositor/native/wlegl_import.c`), the Vulkan WSI layer, the
Wayland-EGL present path and the Xwayland EGL platform. `deps.list` no
longer pins smithay, libxkbcommon, gfxstream, rutabaga_gfx, the old
`deps/mesa` tree or the Xwayland sources. See TAWC_DSH_DESIGN.md
§11.5.
