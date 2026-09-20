package me.phie.tawc.install

import me.phie.tawc.BuildConfig
import me.phie.tawc.GraphicsBackend

/**
 * Build-time gate for the graphics backends this APK ships. Driven by
 * the `GRAPHICS_*_ENABLED` BuildConfig fields set in
 * `app/build.gradle.kts` (override with `-PtawcGraphics=libhybris,turnip,none`).
 *
 * Two of the three members are drivers this APK ships. The third,
 * [GraphicsBackend.NONE], ships nothing and means "no driver
 * provisioned" — see its KDoc. It is a build-flag member like the others
 * because a build can drop it (somebody who wants no chance of a
 * software path being reached by hand), not because there is an artifact
 * to gate. The display-only backends (`gfxstream`, `libhybris-zink`) are
 * gone with the compositor — see TAWC_DSH_DESIGN.md §11. Pattern mirrors
 * [EnabledMethods].
 *
 * Settings UI calls [enabled] to filter the picker.
 * [me.phie.tawc.install.TurnipInstallProvider] short-circuits to empty
 * when turnip is disabled. [RootfsEnv.defaultBackend] falls back to
 * [GraphicsBackend.DEFAULT] when the Vulkan pick derives a backend this
 * APK doesn't ship — covers users who downgrade or switch flag sets
 * between builds.
 *
 * One backend carries a runtime condition on top of its flag: Turnip only runs
 * on a Qualcomm Adreno ([AdrenoGpu]). Without that check a MediaTek/Exynos/
 * Tensor device — arm64-v8a like any Snapdragon, so the ABI cannot separate
 * them — would be offered freedreno as a working option, and would even get it
 * as the default backend.
 */
object EnabledGraphicsBackends {
    val libhybris: Boolean = BuildConfig.GRAPHICS_LIBHYBRIS_ENABLED

    /** Build flag *and* device check — see this object's KDoc and [AdrenoGpu]. */
    val turnip: Boolean = BuildConfig.GRAPHICS_TURNIP_ENABLED && AdrenoGpu.present

    val none: Boolean = BuildConfig.GRAPHICS_NONE_ENABLED

    fun isEnabled(backend: GraphicsBackend): Boolean = when (backend) {
        GraphicsBackend.LIBHYBRIS -> libhybris
        GraphicsBackend.TURNIP -> turnip
        GraphicsBackend.NONE -> none
    }

    /** Backends this APK ships, in enum declaration order. Settings
     *  UI iterates this; absent backends never get shown. */
    val enabled: List<GraphicsBackend> = GraphicsBackend.entries.filter { isEnabled(it) }
}
