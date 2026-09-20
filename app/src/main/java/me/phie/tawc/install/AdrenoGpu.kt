package me.phie.tawc.install

import android.os.Build
import java.io.File

/**
 * Is this device's GPU a Qualcomm Adreno?
 *
 * Turnip *is* Mesa's freedreno — it drives Adreno over `/dev/kgsl-3d0` and
 * nothing else. Every other arm64 SoC is `arm64-v8a` too (Mali on MediaTek,
 * Exynos, Tensor, Kirin), so an ABI check cannot tell them apart, and shipping
 * Turnip there means offering a driver whose ICD fails to load — with no way
 * for the user to tell that apart from "this device has no working GPU".
 * Hence a device probe rather than another build flag.
 *
 * Three probes, any one of which is enough. They are deliberately independent,
 * because each has a hole the others cover:
 *
 *  1. `/dev/kgsl-3d0` exists. kgsl *is* Qualcomm's GPU kernel driver, so the
 *     node is the hardware itself, and it is exactly what Turnip opens. Hole:
 *     the minor number is not 0 on every SoC revision, and `exists()` needs
 *     `/dev` to be traversable.
 *  2. `Build.SOC_MANUFACTURER` names Qualcomm (API 31+). The platform's own
 *     answer, which beats parsing anything. Holes: it does not exist below 31,
 *     and its *value* is whatever the OEM put there — a Snapdragon reports the
 *     abbreviation "QTI" as often as the full name, which is why
 *     [QUALCOMM_SOC_MANUFACTURERS] carries both.
 *  3. `Build.HARDWARE` names a Qualcomm SoC. Covers the pre-31 devices probe 2
 *     misses — `qcom`, plus the `sm*`/`msm*`/`apq*`/`sdm*`/`qcs*` codenames.
 *     Hole: some OEMs put a platform codename there instead (`kona`,
 *     `lahaina`, `waipio`, ...), and no list of those stays complete.
 *
 * A wrong "no" needs an Adreno device to fail all three at once; a wrong "yes"
 * needs a non-Qualcomm device to look Qualcomm on one of them. Both are
 * far-fetched, and the cost of being wrong is the Turnip *option*, not the app.
 */
object AdrenoGpu {
    /**
     * `sm8550`, `msm8998`, `sdm845`, `qcs6490` and friends. Anchored and
     * digit-suffixed on purpose: without both, a hardware string that merely
     * starts with those letters would pass.
     */
    private val QUALCOMM_HARDWARE = Regex("^(sm|msm|apq|sdm|qcs|qsc)\\d+")

    /**
     * The values `Build.SOC_MANUFACTURER` is known to take on Qualcomm
     * hardware. Both are real, and both were seen: the long name, and the
     * abbreviation "QTI" (Qualcomm Technologies, Inc) — which is what a Redmi
     * K60 Ultra reports, so matching only the long form would miss a device
     * the other two probes merely happened to cover.
     */
    private val QUALCOMM_SOC_MANUFACTURERS = listOf("Qualcomm", "QTI")

    /** The first 3D core's node, as Turnip expects to find it. */
    private const val KGSL_NODE = "/dev/kgsl-3d0"

    val present: Boolean by lazy {
        detect(
            // `SOC_MANUFACTURER` is API 31+, and it is not a compile-time
            // constant, so touching it below 31 is a NoSuchFieldError rather
            // than a null.
            socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MANUFACTURER
            } else {
                null
            },
            hardware = Build.HARDWARE,
            hasKgslNode = kgslNodePresent(),
        )
    }

    /**
     * Pure, and takes the node's existence as an argument, so the decision can
     * be tested against the devices it is about rather than the one it runs on.
     */
    internal fun detect(
        socManufacturer: String?,
        hardware: String?,
        hasKgslNode: Boolean,
    ): Boolean =
        hasKgslNode ||
            // `contains`, not `equals`: the long form is often reported as
            // "Qualcomm Technologies, Inc".
            (socManufacturer != null && QUALCOMM_SOC_MANUFACTURERS.any {
                socManufacturer.contains(it, ignoreCase = true)
            }) ||
            hardware?.lowercase()?.let { h ->
                h.contains("qcom") || QUALCOMM_HARDWARE.containsMatchIn(h)
            } == true

    private fun kgslNodePresent(): Boolean = try {
        File(KGSL_NODE).exists()
    } catch (_: Throwable) {
        // A blocked `/dev` read is not evidence of absence; the other two
        // probes get their say either way.
        false
    }
}
