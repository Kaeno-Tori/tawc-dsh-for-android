package me.phie.tawc

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

/**
 * Process-global settings facade. Production uses [SharedPreferences];
 * integration tests can swap in an in-memory store with factory defaults.
 *
 * Initialised once from [TawcApplication.onCreate] so non-Activity code
 * (e.g. [me.phie.tawc.install.RootfsEnv], which runs on the broker
 * thread without a Context) can read settings without threading a
 * Context through every call site.
 *
 * Enum-like persisted settings (e.g. [GraphicsBackend]) keep their
 * wire-format key in code so adding a new variant later doesn't break
 * installs that already chose one of the existing values.
 */
object Settings {
    private const val PREFS_NAME = "tawc-settings"
    private const val KEY_VULKAN_DRIVER = "vulkan_driver"
    private const val KEY_BOOTSTRAP_MIRROR = "bootstrap_mirror"
    private const val KEY_AUTO_BIND_SHARED_STORAGE = "auto_bind_shared_storage"
    private const val KEY_DSH_DOCK_X = "dsh_dock_x"
    private const val KEY_DSH_DOCK_Y = "dsh_dock_y"
    private const val KEY_DSH_ZOOM_PERCENT = "dsh_zoom_percent"
    private const val KEY_THEME_MODE = "theme_mode"

    /**
     * Range of [dshZoomPercent], owned here because the settings screen and
     * the DSH screen's floating menu both step it and neither may drift into
     * values the other one can't produce. 100 is the untouched page; the low
     * end is what a narrow or short screen wants, since a smaller scale is
     * more CSS pixels for the layout to work with.
     */
    const val ZOOM_MIN_PERCENT = 50
    const val ZOOM_MAX_PERCENT = 150
    const val ZOOM_STEP_PERCENT = 5
    private const val DEFAULT_DSH_ZOOM_PERCENT = 100

    /**
     * Position of the DSH screen's floating tool entry, as a fraction of
     * the distance it can travel inside the window — not of the window
     * itself, so it stays in the same place when the screen rotates.
     * [Float.NaN] means "never dragged", which is what tells
     * [me.phie.tawc.ui.FloatingDock] to use its own default corner.
     */
    private const val DOCK_POSITION_UNSET = Float.NaN

    private interface Store {
        var vulkanDriver: VulkanDriver?
        var bootstrapMirror: String
        var autoBindSharedStorage: Boolean?
        var dshDockX: Float
        var dshDockY: Float
        var dshZoomPercent: Int
        var themeMode: ThemeMode
    }

    private class SharedPreferencesStore(private val prefs: SharedPreferences) : Store {
        /**
         * Tri-state, like [autoBindSharedStorage]: absent means the user has
         * never picked an accelerator, so the build's own default applies
         * ([VulkanDriver.effective]). A key this build doesn't recognise
         * (a variant that has since gone, a downgrade) reads the same way.
         */
        override var vulkanDriver: VulkanDriver?
            get() = VulkanDriver.fromKey(prefs.getString(KEY_VULKAN_DRIVER, null))
            set(value) {
                prefs.edit {
                    if (value == null) remove(KEY_VULKAN_DRIVER)
                    else putString(KEY_VULKAN_DRIVER, value.key)
                }
            }

        override var themeMode: ThemeMode
            get() = ThemeMode.fromKeyOrDefault(prefs.getString(KEY_THEME_MODE, null))
            set(value) {
                prefs.edit { putString(KEY_THEME_MODE, value.key) }
            }

        override var bootstrapMirror: String
            get() = prefs.getString(KEY_BOOTSTRAP_MIRROR, "") ?: ""
            set(value) {
                prefs.edit { putString(KEY_BOOTSTRAP_MIRROR, value) }
            }

        /**
         * Tri-state, and `contains` is the third state: absent means the
         * user has never answered, so the card is free to follow the
         * grant (see [Settings.autoBindSharedStorage]). Writing it — by
         * toggling the checkbox — is permanent: nothing re-derives it
         * afterwards, which is what stops a rebuild from re-checking a
         * box the user cleared.
         */
        override var autoBindSharedStorage: Boolean?
            get() = if (prefs.contains(KEY_AUTO_BIND_SHARED_STORAGE)) {
                prefs.getBoolean(KEY_AUTO_BIND_SHARED_STORAGE, false)
            } else {
                null
            }
            set(value) {
                prefs.edit {
                    if (value == null) remove(KEY_AUTO_BIND_SHARED_STORAGE)
                    else putBoolean(KEY_AUTO_BIND_SHARED_STORAGE, value)
                }
            }

        override var dshDockX: Float
            get() = prefs.getFloat(KEY_DSH_DOCK_X, DOCK_POSITION_UNSET)
            set(value) {
                prefs.edit { putFloat(KEY_DSH_DOCK_X, value) }
            }

        override var dshDockY: Float
            get() = prefs.getFloat(KEY_DSH_DOCK_Y, DOCK_POSITION_UNSET)
            set(value) {
                prefs.edit { putFloat(KEY_DSH_DOCK_Y, value) }
            }

        override var dshZoomPercent: Int
            get() = prefs.getInt(KEY_DSH_ZOOM_PERCENT, DEFAULT_DSH_ZOOM_PERCENT)
            set(value) {
                prefs.edit { putInt(KEY_DSH_ZOOM_PERCENT, value) }
            }
    }

    private class TestStore : Store {
        @Volatile override var vulkanDriver: VulkanDriver? = null
        @Volatile override var bootstrapMirror: String = ""
        @Volatile override var autoBindSharedStorage: Boolean? = null
        @Volatile override var dshDockX: Float = DOCK_POSITION_UNSET
        @Volatile override var dshDockY: Float = DOCK_POSITION_UNSET
        @Volatile override var dshZoomPercent: Int = DEFAULT_DSH_ZOOM_PERCENT
        @Volatile override var themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM
    }

    @Volatile private var store: Store? = null

    /** Called from [TawcApplication.onCreate]. Idempotent. */
    fun init(context: Context) {
        if (store == null) {
            store = SharedPreferencesStore(context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            )
        }
    }

    private fun requireStore(): Store =
        store ?: error("Settings.init(context) was not called — see TawcApplication.onCreate")

    /**
     * Swap all settings reads/writes to in-memory factory defaults. Debug
     * broker tests use this so no persisted user setting can leak into a
     * test, and no test mutation can survive app process death.
     */
    fun enterTestMode() {
        requireStore()
        store = TestStore()
    }

    /**
     * Which Vulkan accelerator the user picked for the installed rootfs's
     * standard paths, or `null` while they have never answered.
     *
     * Not the same question as "which driver is the container wired for":
     * [VulkanDriver.effective] answers that (it falls back to the build's
     * default), and [me.phie.tawc.install.VulkanProvisionOp] is what makes
     * the container true. Written by the settings screen, which is the only
     * place a user expresses a preference about this.
     */
    var vulkanDriver: VulkanDriver?
        get() = requireStore().vulkanDriver
        set(value) { requireStore().vulkanDriver = value }

    /**
     * Optional replacement for the distro's upstream bootstrap origin.
     *
     * Empty (the default) means "download the rootfs tarball from the
     * distro's own mirror". A non-empty value swaps the *prefix* the
     * distro declares in [me.phie.tawc.install.distro.Distro.bootstrapMirrorPrefix]
     * with this base, e.g. `https://mirrors.tuna.tsinghua.edu.cn`
     * rewriting `https://fl.us.mirror.archlinuxarm.org/os/…` to
     * `https://mirrors.tuna.tsinghua.edu.cn/alarm/os/…`.
     *
     * This is the only way to bootstrap behind a network that can't
     * reach the upstream origin — the tarball URL is a compile-time
     * constant, and the debug-only [me.phie.tawc.install.MirrorProxy]
     * is rejected in release builds.
     */
    var bootstrapMirror: String
        get() = requireStore().bootstrapMirror
        set(value) { requireStore().bootstrapMirror = value }

    /**
     * Whether an install binds shared storage without being asked, as a
     * tri-state: `null` = the user has never touched the checkbox, so
     * follow the all-files grant (asked for, then answered by granting);
     * `true`/`false` = an explicit answer, which nothing overrides.
     *
     * The grant is what makes it a question worth defaulting rather than
     * a plain `false`: shared-storage binds are fail-closed, so binding
     * them without the grant fails the install, and offering the box only
     * once the grant exists is what keeps "on" always installable.
     * [me.phie.tawc.install.AllFilesAccess.granted] decides the default;
     * see [me.phie.tawc.MainActivity.renderGrantCard].
     */
    var autoBindSharedStorage: Boolean?
        get() = requireStore().autoBindSharedStorage
        set(value) { requireStore().autoBindSharedStorage = value }

    /**
     * Where the user dragged that entry, normalised to `0..1` across the
     * range it can travel; [Float.NaN] until the first drag. Stored as
     * two floats rather than a point so a read never allocates.
     */
    var dshDockX: Float
        get() = requireStore().dshDockX
        set(value) { requireStore().dshDockX = value }

    /** See [dshDockX]. */
    var dshDockY: Float
        get() = requireStore().dshDockY
        set(value) { requireStore().dshDockY = value }

    /**
     * Page scale for DSH's own interface, in percent.
     *
     * DSH's client is a desktop-shaped web app, and a WebView lays it out at
     * the display's density: the page gets exactly as many CSS pixels as the
     * screen has density-independent pixels. A 16:9 phone in landscape is
     * ~430 wide and ~430 tall, which is where that layout runs out of room —
     * this is the knob for that.
     *
     * It scales the *page*, not the text, which is why
     * [me.phie.tawc.dsh.DshActivity] applies it as a CSS `zoom` on the
     * document root instead of using a WebView font size: `zoom` takes the
     * panel widths, the composer and the spacing with it. DSH's own "font
     * size" setting is the other half of that pair — it sizes conversation
     * text only.
     *
     * 100 leaves the page exactly as it was, and is the default, so an
     * install that never opens this control renders as it did before the
     * control existed. Note the contrast with [themeMode]: this is the one
     * setting here that *does* reach inside the WebView.
     */
    var dshZoomPercent: Int
        get() = requireStore().dshZoomPercent
        set(value) {
            requireStore().dshZoomPercent =
                value.coerceIn(ZOOM_MIN_PERCENT, ZOOM_MAX_PERCENT)
        }

    /**
     * Light / dark / follow the system, for the app's own surfaces.
     *
     * Applied via `AppCompatDelegate.setDefaultNightMode`, so it decides
     * which of every `-night`-qualified resource the platform hands out:
     * the whole app UI, and the DSH boot overlay
     * ([me.phie.tawc.dsh.DshBootOverlay]) with it.
     *
     * **It does not reach inside the WebView.** DSH's own interface is a web
     * client with an appearance setting of its own, and nothing here can
     * change that — see [ThemeMode]'s KDoc.
     */
    var themeMode: ThemeMode
        get() = requireStore().themeMode
        set(value) { requireStore().themeMode = value }

}

/**
 * Which GPU driver the app puts in a rootfs spawn's environment.
 *
 * There used to be five of these, and the enum's job was to describe how
 * Linux GUI programs would *draw* — libhybris GLES, Mesa+Zink for
 * desktop GL, an in-compositor gfxstream renderer. DSH only ever wanted
 * Vulkan *compute* (llama.cpp and friends), never a surface, so the two
 * display-only backends went away with the compositor
 * (TAWC_DSH_DESIGN.md §11).
 *
 * Stored as a string so additional options can be added without breaking
 * already-saved preferences.
 */
enum class GraphicsBackend(val key: String, val displayName: String) {
    /**
     * The Android vendor GPU blob, loaded into the rootfs via libhybris.
     * Reached headlessly: the Vulkan platform plugin is the null one we
     * build ourselves (`HYBRIS_VULKANPLATFORM=null`), so no Wayland or
     * GL stack is involved.
     */
    LIBHYBRIS("libhybris", "libhybris"),

    /**
     * Mesa's Turnip — the open-source freedreno Vulkan driver — driving
     * the Adreno GPU straight through `/dev/kgsl-3d0`. No libhybris, no
     * vendor blob in the chroot: the driver is an ordinary Vulkan ICD
     * that we ship (see `scripts/build-turnip.sh`).
     *
     * aarch64 only, and headless: the build has no WSI, which is exactly
     * what compute wants. Vulkan apps reach it through the loader we
     * ship alongside it — see [me.phie.tawc.install.TurnipInstallProvider].
     */
    TURNIP("turnip", "turnip"),

    /**
     * No driver provisioned: the app hands the container nothing and
     * pins no Vulkan ICD.
     *
     * A *state*, not a fallback backend — which is why it is not called
     * CPU any more. It ships no driver (there is no
     * `NONEInstallProvider`), and the two variables this branch used to
     * set (`LIBGL_ALWAYS_SOFTWARE`, `GALLIUM_DRIVER`) are read by a
     * distro's own Mesa GL: nothing in this app installs Mesa, and
     * nothing here has anything to draw to, since the fork has no
     * display stack.
     *
     * When no GPU driver works, the right answer for the agent's
     * workload is CPU-native inference — llama.cpp's own CPU backend,
     * which needs no Vulkan ICD at all. Routing that through a software
     * Vulkan implementation is a detour, and a slower one.
     *
     * Leaving the ICD unpinned is what keeps the manual diagnostic
     * available: installing `vulkan-swrast` in the container by hand lets
     * the distro loader find lavapipe, which is how the §11 three-way
     * comparison established that Turnip's `q4_K MUL_MAT` crash was a
     * driver bug rather than llama.cpp's.
     */
    NONE("none", "none");

    companion object {
        /**
         * Default backend picked when nothing is saved yet.
         *
         * On aarch64 (physical devices) [TURNIP] is the default: it is
         * the same GPU path libhybris takes, minus libhybris — a plain
         * Vulkan ICD over `/dev/kgsl-3d0` — and its q4_K MUL_MAT path
         * was the reason to move the pin to Mesa 26.2.2
         * (TAWC_DSH_DESIGN.md §11.1). [LIBHYBRIS] remains the
         * fallback for a build that ships no Turnip asset: it is
         * `enabled.first()`, i.e. first in enum declaration order.
         *
         * On x86_64 (emulator) libhybris can't load against bionic
         * (notes/emulator.md "libhybris on x86_64") and there is no
         * kgsl device, and the gfxstream backend that used to cover this
         * case is gone with the compositor its kumquat server lived in.
         * So x86_64 gets [NONE] when it is shipped, and otherwise
         * whatever the build does ship.
         */
        val DEFAULT: GraphicsBackend
            get() {
                val abi = Build.SUPPORTED_ABIS.firstOrNull()
                val preferred = if (abi == "x86_64") NONE else TURNIP
                if (me.phie.tawc.install.EnabledGraphicsBackends.isEnabled(preferred)) {
                    return preferred
                }
                return me.phie.tawc.install.EnabledGraphicsBackends.enabled.first()
            }
    }
}

/**
 * Which Vulkan accelerator the settings screen has provisioned into every
 * installed rootfs.
 *
 * This is *not* [GraphicsBackend]. That one picks the environment the app
 * itself hands to each rootfs spawn; this one is about what the container
 * finds on its own — an ICD loader at `/usr/lib/libvulkan.so.1` plus, for
 * [TURNIP], a manifest under `/usr/share/vulkan/icd.d/`. Programs started
 * from inside the rootfs (a login shell, a build script, a program the
 * agent launches from a shell)
 * get that, whereas app-spawned processes still get [GraphicsBackend]'s env,
 * which wins when the two disagree.
 *
 * The `key` doubles as the `enable-<key>.sh` suffix in
 * `app/src/main/assets/vulkan-scripts/`, which is why [OFF] has one that no
 * script answers to: turning off runs `cleanup.sh` instead.
 *
 * Whatever the scripts write is recorded in the rootfs at
 * `/usr/lib/tawc/vulkan/state`, next to a backup of any loader the distro
 * shipped so [OFF] can put it back. See TAWC_DSH_DESIGN.md §11.4.
 */
enum class VulkanDriver(val key: String) {
    /**
     * Leave the rootfs alone; remove anything a previous pick installed.
     *
     * This is the user's answer, not the absence of one: an install nobody
     * has ever answered for follows [effective] instead, which on aarch64
     * is [TURNIP]. Treating the two as the same value meant a container
     * installed on a Turnip-defaulting device came up with nothing at
     * `/usr/lib/libvulkan.so.1` while the app already ran its own spawns
     * with Turnip — see [effective].
     */
    OFF("off"),

    /**
     * The device's own vendor driver, reached through libhybris. The APK
     * ships libhybris's `libvulkan.so.1`; the vendor blob has no ICD
     * manifest on this device, so nothing goes in `icd.d/`.
     */
    SYSTEM("system"),

    /**
     * Mesa's freedreno Vulkan driver over `/dev/kgsl-3d0`, from the APK's
     * Turnip assets. aarch64 only — the assets aren't built elsewhere.
     */
    TURNIP("turnip"),

    ;

    companion object {
        /** [key]'s variant, or null for anything unrecognised — including a
         *  key written by a build that offered a variant this one has
         *  dropped. */
        fun fromKey(key: String?): VulkanDriver? = entries.firstOrNull { it.key == key }

        /**
         * Which accelerator the container should be wired for: the user's
         * pick, or — while they have never made one — the driver behind
         * [GraphicsBackend.DEFAULT].
         *
         * Deliberately the same answer as
         * [me.phie.tawc.install.RootfsEnv.defaultBackend], because that is
         * the point of provisioning at all: programs started *inside* the
         * rootfs must find the driver the app already gives the processes it
         * spawns. Letting the two derive separately is what left a
         * Turnip-defaulting device with an unwired container.
         *
         * [OFF] as a *choice* still wins — it is how a user says "leave my
         * rootfs alone" — but [GraphicsBackend.NONE] also lands on it,
         * since there is no driver to provision and no manifest to write.
         */
        fun effective(): VulkanDriver {
            Settings.vulkanDriver?.let { return it }
            return when (GraphicsBackend.DEFAULT) {
                GraphicsBackend.TURNIP -> TURNIP
                GraphicsBackend.LIBHYBRIS -> SYSTEM
                GraphicsBackend.NONE -> OFF
            }
        }
    }
}

/**
 * Light, dark, or whatever the OS is doing — the app's own theme pick.
 *
 * The `key` is the persisted wire format, the same convention as
 * [GraphicsBackend]: adding a variant later must not disturb installs that
 * already picked one of these.
 *
 * [delegateMode] is the constant
 * [androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode] wants, kept
 * on the enum so the two places that apply this setting cannot drift:
 * [TawcApplication.onCreate] at process start, and the settings screen's
 * appearance card when the user picks.
 *
 * **Scope: this app's surfaces only.** It swaps which `-night`-qualified
 * resources the platform hands out, which covers every screen we build
 * ourselves and the DSH boot overlay. DSH's own interface is a web client
 * running inside a WebView with an appearance setting of its own — the
 * Android theme cannot reach it, and "follow the system" for that surface
 * remains DSH's business. See `TAWC_DSH_DESIGN.md` §2.
 */
enum class ThemeMode(val key: String, val delegateMode: Int) {
    /** The OS decides, and the app follows day/night switches live. */
    FOLLOW_SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),

    /** Always the day resources. */
    LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),

    /** Always the `-night` resources. */
    DARK("dark", AppCompatDelegate.MODE_NIGHT_YES),

    ;

    companion object {
        fun fromKeyOrDefault(key: String?): ThemeMode =
            entries.firstOrNull { it.key == key } ?: FOLLOW_SYSTEM
    }
}
