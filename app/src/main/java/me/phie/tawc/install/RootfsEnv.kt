package me.phie.tawc.install

import me.phie.tawc.GraphicsBackend
import me.phie.tawc.Settings
import me.phie.tawc.VulkanDriver

/**
 * Environment variables passed to the in-rootfs login shell.
 *
 * Each install method's [InstallationMethod.startInside] spawns the
 * shell under `/usr/bin/env -i` so nothing Android (or proot/tawcroot)
 * leaks through; this map is the entire env the in-rootfs world sees
 * before `/etc/profile` runs. Distro `/etc/profile` files set their own
 * `PATH` unconditionally, so the in-rootfs PATH ultimately comes from
 * the distro — we set it here as well only to give scripts that read
 * `$PATH` before profile.d completes a sane fallback. `/usr/games` is
 * included because Debian installs graphical games such as SuperTuxKart
 * there while Arch puts them under the usual sbin/bin paths.
 *
 * Per-method tweak: `MOZ_DISABLE_*_SANDBOX` is set under proot only.
 * Firefox's per-subprocess sandboxes SIGSEGV under proot's ptrace
 * tracer; tawcroot/chroot have no tracer and let the sandbox come up
 * cleanly, so applying these vars there would weaken security for no
 * gain.
 *
 * Environment-wide Gecko tweak: `MOZ_SHM_NO_SEALS` is set for every
 * method — Firefox ≥ 155's memfd probe fails under Android SELinux
 * (`untrusted_app`), which makes parent and content processes disagree
 * about sealing and kills every tab. See the inline comment at the
 * `put` for the full reasoning.
 *
 * Per-backend tweak: env diverges by [GraphicsBackend] (read fresh from
 * [Settings] on every spawn). Libhybris keeps the libhybris dirs on
 * `LD_LIBRARY_PATH` and reaches the vendor driver headlessly through the
 * null Vulkan platform plugin. NONE hands over nothing at all — no
 * `LD_LIBRARY_PATH` entry and no pinned ICD — which is the state to use
 * when no GPU driver works, and the one that lets a hand-installed
 * lavapipe be discovered for diagnostics. Turnip puts only its own
 * dir on `LD_LIBRARY_PATH` (it ships both the ICD and the loader) and
 * pins `VK_ICD_FILENAMES` at the driver; keeping libhybris out of that
 * path is load-bearing there, not hygiene — see the branch's own
 * comment.
 *
 * There is no Wayland or X11 in this environment any more: the
 * compositor is gone (TAWC_DSH_DESIGN.md §11), so `WAYLAND_DISPLAY`,
 * `DISPLAY` and the toolkit-preference vars that used to steer clients
 * at one or the other are gone too.
 */
internal object RootfsEnv {
    enum class Method { TAWCROOT, PROOT, CHROOT }

    /** Guest home dir: the guest runs as fake root. Also what a typed
     * `~` expands to in the manage-binds guest-path field. */
    const val GUEST_HOME = "/root"

    fun build(method: Method): Map<String, String> =
        build(method, defaultBackend())

    /**
     * The backend to use when the caller didn't pin one.
     *
     * Derived from [Settings.vulkanDriver], which is the settings screen's
     * only graphics-related pick, so it has to be what decides the env.
     * The two used to be independent settings, and that is what made the
     * Vulkan option lie — with the backend on `libhybris` a spawn would
     * get the vendor driver no matter which accelerator the user had
     * chosen, because `LD_LIBRARY_PATH` beats the rootfs's
     * `/usr/lib/libvulkan.so.1`.
     *
     * `OFF` maps to [GraphicsBackend.DEFAULT] rather than to
     * [GraphicsBackend.NONE]: "off" means "don't manage the container's
     * Vulkan", not "give spawns no driver" — an install that never touched
     * the setting has to keep the GPU env it had before this option
     * existed.
     *
     * A pick this build can't serve (flag change, downgrade) falls back
     * to [GraphicsBackend.DEFAULT], so a stale pref can't point the env
     * at a rootfs tree that isn't there.
     */
    fun defaultBackend(): GraphicsBackend {
        val fromVulkan = when (Settings.vulkanDriver) {
            // Both mean "the app didn't pick one": [VulkanDriver.OFF] only
            // says "don't write into my rootfs", and an unanswered pref means
            // the same as far as the *env* is concerned.
            null, VulkanDriver.OFF -> GraphicsBackend.DEFAULT
            VulkanDriver.TURNIP -> GraphicsBackend.TURNIP
            VulkanDriver.SYSTEM -> GraphicsBackend.LIBHYBRIS
        }
        return if (EnabledGraphicsBackends.isEnabled(fromVulkan)) {
            fromVulkan
        } else {
            GraphicsBackend.DEFAULT
        }
    }

    /** [shell] is root's login shell ([RootShell.resolve]); it only
     *  becomes `SHELL` in the guest env — the argv every spawn execs
     *  is chosen by the caller. */
    fun build(
        method: Method,
        backend: GraphicsBackend,
        shell: String = RootShell.DEFAULT,
    ): Map<String, String> = buildMap {
        put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games")
        put("HOME", GUEST_HOME)
        // login(1) normally sets USER/LOGNAME; we exec bash directly,
        // and bash doesn't, so scripts (and distro title-setting
        // PROMPT_COMMANDs) would otherwise see them empty.
        put("USER", "root")
        put("LOGNAME", "root")
        // Also normally login(1)'s job. Bash would fill it in from
        // passwd on its own, but non-bash shells and plain `-c`
        // spawns wouldn't, and anything in the container that consults
        // `$SHELL` to pick an interactive shell would see it empty.
        put("SHELL", shell)
        put("TMPDIR", "/tmp")
        // Locale and timezone, carried in the env for every spawn
        // because the files that normally hold them ([Localization]
        // writes `/etc/locale.conf` and `/etc/localtime`) are only read
        // by the distro's *login* path. Measured on a fresh container:
        // under `env -i bash -c` — what the DSH bash tool and every
        // non-interactive spawn look like — `LANG` is unset entirely,
        // not merely wrong.
        //
        // `TZ` is also the only half that is genuinely automatic: the
        // `/etc/localtime` link is fixed at install time, while this is
        // re-read from Android on every spawn, so a device that changes
        // timezone is followed without touching the container.
        put("LANG", Localization.LANG)
        Localization.zoneFor(java.util.TimeZone.getDefault().id)?.let { put("TZ", it) }
        put("XDG_RUNTIME_DIR", "/tmp")
        when (backend) {
            GraphicsBackend.LIBHYBRIS -> {
                // libhybris is laid down by [TawcInstaller] /
                // [LibhybrisInstallProvider] as real files under /usr/lib/hybris/
                // (a tawc-owned namespace — /usr/local/lib/ stays free for the
                // user's own installs). gl-shims first so the libGL/libGLESv2
                // wrappers shadow any distro-shipped libs.
                put("LD_LIBRARY_PATH",
                    "${LibhybrisInstallProvider.GUEST_GL_SHIMS_DIR}:${LibhybrisInstallProvider.GUEST_LIB_DIR}")
                // No HYBRIS_*_DIR overrides needed — `scripts/build-libhybris.sh`
                // configures libhybris with `--prefix=/usr/lib/hybris
                // --libdir=/usr/lib/hybris`, so the PKGLIBDIR + LINKER_PLUGIN_DIR
                // macros baked into the .so files by the autotools build (see
                // deps/libhybris/hybris/{egl/ws.c, vulkan/ws.c, common/hooks.c})
                // already point at where [LibhybrisInstallProvider] copies the
                // plugin tree. Build-time bake > per-entry env override.
                // Headless Vulkan. libhybris's libvulkan.so.1 won't run
                // until it dlopens some `vulkanplatform_*.so`, and its
                // default is "wayland" — which needs glibc Wayland in the
                // container and assert()s without it
                // (TAWC_DSH_DESIGN.md §11.1). "null" is the plugin we
                // build ourselves (scripts/build-libhybris.sh, source in
                // deps/libhybris-shims/vulkanplatform_null.c): upstream's
                // pass-through minus the libwayland-server / libgralloc /
                // vulkanplatformcommon links, so it loads with libc alone.
                // Selected by name — PKGLIBDIR already points at
                // [LibhybrisInstallProvider.GUEST_PLUGIN_DIR], so no
                // HYBRIS_VULKANPLATFORM_DIR override. A Vulkan app that
                // *presents* (rather than computing) would want
                // `HYBRIS_VULKANPLATFORM=wayland` instead, plus glibc
                // Wayland in the container.
                put("HYBRIS_VULKANPLATFORM", "null")
            }
            GraphicsBackend.NONE -> {
                // Deliberately sets nothing. This is the state for "no
                // driver works here": no libhybris on LD_LIBRARY_PATH (so
                // a distro Mesa would load), no pinned Vulkan ICD (so the
                // distro loader discovers whatever the container itself
                // has — which is nothing, unless someone installed
                // vulkan-swrast by hand, and that is the diagnostic case
                // worth preserving).
                //
                // It used to also force the distro's Mesa onto software
                // with LIBGL_ALWAYS_SOFTWARE + GALLIUM_DRIVER=llvmpipe.
                // Those are GL variables, nothing in this app installs
                // Mesa, and there is no display stack for GL to draw to —
                // dead settings that advertised a backend that did not
                // exist. CPU-native work needs no Vulkan ICD at all, so
                // there is nothing here to fall back to.
            }
            GraphicsBackend.TURNIP -> {
                // Only our own dir — deliberately not libhybris's, and
                // not the distro's either. Two reasons:
                //
                //   1. libhybris ships a libvulkan.so.1 that is found by
                //      *soname* ahead of the loader we bundle. It then
                //      tries to bring the vendor driver up through
                //      vulkanplatform_wayland.so, which dlopens
                //      libwayland-client.so.0 — absent in this container —
                //      and assert()s. The Vulkan app dies before it logs
                //      anything. Measured: both drivers under test
                //      produced byte-identical output until this dir was
                //      un-shadowed (TAWC_DSH_DESIGN.md §11.1).
                //   2. We ship the loader too, so the first
                //      libvulkan.so.1 on this path is the one that goes
                //      with the ICD — no dependency on the container
                //      having vulkan-icd-loader installed.
                put("LD_LIBRARY_PATH", TurnipInstallProvider.GUEST_LIB_DIR)
                // Point the loader at our ICD explicitly. The container
                // ships no ICD of its own, so without this the loader
                // finds nothing to enumerate.
                put("VK_ICD_FILENAMES", TurnipInstallProvider.GUEST_ICD_PATH)
            }
        }
        // Firefox >= 155 SIGSEGVs every content process under a rootless
        // method: `HaveMemfd()` runs its `DupReadOnly()` probe (a
        // `open("/proc/self/fd/<n>")` on a memfd) only in the parent/GPU
        // role, and that open gets EACCES under Android's SELinux (Mozilla's
        // own comment in SharedMemoryPlatform_posix.cpp says the memfd type
        // is "always" un-openable there). The parent therefore concludes
        // "no memfd" and takes the POSIX-shm path WITHOUT applying
        // F_SEAL_SHRINK, while the child — which skips the probe — concludes
        // "memfd can seal" and fails its `IsSafeToMap()` seal check, then
        // makes that fatal. Result: parent and children disagree and every
        // tab crashes.
        //
        // `MOZ_SHM_NO_SEALS` is upstream's supported opt-out (defined a few
        // lines above the failing probe): it clears MFD_ALLOW_SEALING in
        // EVERY role, so `MemfdCanSeal()` is false everywhere, the
        // F_GET_SEALS branch is skipped as a whole, and both sides agree.
        // Seals are a hardening measure (they stop a shrunk segment from
        // SIGBUSing a mapping that was already validated), so this trades a
        // little of that away for working IPC. It is inert for non-Gecko
        // programs, and the check it works around is gone again in
        // mozilla-central — so this may become removable in a later release.
        //
        // Set here rather than via `/etc/profile.d/` because env state
        // comes solely from this object on every spawn (see notes/
        // installation.md), and a profile.d file would only reach login
        // shells.
        put("MOZ_SHM_NO_SEALS", "1")
        if (method == Method.PROOT) {
            put("MOZ_DISABLE_CONTENT_SANDBOX", "1")
            put("MOZ_DISABLE_GPU_SANDBOX", "1")
            put("MOZ_DISABLE_RDD_SANDBOX", "1")
            put("MOZ_DISABLE_SOCKET_PROCESS_SANDBOX", "1")
            put("MOZ_DISABLE_UTILITY_SANDBOX", "1")
            put("MOZ_DISABLE_GMP_SANDBOX", "1")
            put("MOZ_DISABLE_VR_SANDBOX", "1")
        }
    }

    /**
     * `/usr/bin/env -i -C /root KEY=VAL …` argv prefix. Each entry is
     * one argv element so callers don't have to worry about quoting
     * values that contain spaces. Dest is the in-rootfs `/usr/bin/env`
     * (resolved by tawcroot/proot path translation, or by the kernel
     * after chroot).
     *
     * `-C /root` chdir's to root's home before exec'ing the shell, so
     * an interactive `bash -l` lands in `/root` instead of wherever the
     * host-side cwd happened to translate to (`/tmp` for tawcroot's
     * `$rootfs/tmp` cwd; `/` for chroot's post-chroot cwd). Matches
     * `HOME=/root` set above. Requires GNU env (coreutils ≥ 9.0); both
     * Arch and Void ship 9.x.
     */
    fun envArgv(method: Method): List<String> = envArgv(method, defaultBackend())

    fun envArgv(
        method: Method,
        backend: GraphicsBackend,
        shell: String = RootShell.DEFAULT,
    ): List<String> {
        val out = ArrayList<String>(4 + 16)
        out += "/usr/bin/env"
        out += "-i"
        out += "-C"
        out += "/root"
        for ((k, v) in build(method, backend, shell)) out += "$k=$v"
        return out
    }
}
