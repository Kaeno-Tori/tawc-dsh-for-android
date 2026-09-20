package me.phie.tawc.install

import android.content.Context
import java.io.File

/**
 * Lays the APK-bundled Turnip stack into a rootfs at [GUEST_LIB_DIR] =
 * `/usr/lib/turnip/`:
 *
 *  - `libvulkan_freedreno.so` — Mesa's freedreno Vulkan driver (kgsl), the
 *    ICD itself
 *  - `freedreno_icd.json` — the ICD manifest, pinned via
 *    `VK_ICD_FILENAMES` in [RootfsEnv]
 *  - `libvulkan.so.1` — the Vulkan loader the ICD is reached through
 *
 * All three come from one asset dir per ABI, built by
 * `scripts/build-turnip.sh` and packed by Gradle's `packTurnip`; they are
 * extracted to `<filesDir>/turnip/` by
 * [TawcAssets.ensureTurnipExtracted] and copied from there.
 *
 * Why the loader is ours rather than the distro's: see the script header.
 * In short, the container ships none, and a Vulkan ICD with no loader is
 * unreachable.
 *
 * Whole-dir RO bind under [TawcrootMethod] (`TawcrootMethod.assetBinds`),
 * per-rootfs copies under proot/chroot — the same split as
 * [LibhybrisInstallProvider]. Returns an empty list on a build that ships
 * no Turnip asset (turnip disabled at build time, or a non-aarch64 ABI).
 */
internal object TurnipInstallProvider : TawcInstallProvider {
    override val name: String = "turnip"

    /** Guest-side install root. `RootfsEnv` puts exactly this dir (and
     *  nothing else) on `LD_LIBRARY_PATH` for the `TURNIP` backend so
     *  our loader wins over any distro one, and so libhybris's
     *  `libvulkan.so.1` — which would otherwise be picked up by soname
     *  and fail to bring up an instance — is never in the path. */
    const val GUEST_LIB_DIR = "/usr/lib/turnip"

    const val GUEST_LIB_PATH = "$GUEST_LIB_DIR/${TawcAssets.TURNIP_LIB_ASSET}"
    const val GUEST_ICD_PATH = "$GUEST_LIB_DIR/${TawcAssets.TURNIP_ICD_ASSET}"
    const val GUEST_LOADER_PATH = "$GUEST_LIB_DIR/${TawcAssets.TURNIP_LOADER_ASSET}"

    override fun entries(context: Context, methodKey: String): List<TawcInstall> {
        // Build-time disabled (`-PtawcGraphics=...` without turnip): no
        // APK asset shipped, nothing to install. Gated before the extract
        // probe so the log doesn't grow a misleading "no asset" line on
        // every app start.
        if (!EnabledGraphicsBackends.turnip) return emptyList()
        // tawcroot binds the whole dir RO instead ([TawcrootMethod.assetBinds]);
        // this provider's entire output is that dir, so there's nothing
        // left to copy.
        if (methodKey == TawcrootMethod.KEY) return emptyList()
        if (!TawcAssets.ensureTurnipExtracted(context)) return emptyList()
        val srcDir = File(context.filesDir, "turnip")
        return listOf(
            TawcInstall(
                src = File(srcDir, TawcAssets.TURNIP_LIB_ASSET).absolutePath,
                dest = GUEST_LIB_PATH,
                type = TawcInstall.Type.COPY,
            ),
            TawcInstall(
                src = File(srcDir, TawcAssets.TURNIP_ICD_ASSET).absolutePath,
                dest = GUEST_ICD_PATH,
                type = TawcInstall.Type.COPY,
            ),
            TawcInstall(
                src = File(srcDir, TawcAssets.TURNIP_LOADER_ASSET).absolutePath,
                dest = GUEST_LOADER_PATH,
                type = TawcInstall.Type.COPY,
            ),
        )
    }
}
