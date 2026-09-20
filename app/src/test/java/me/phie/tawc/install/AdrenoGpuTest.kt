package me.phie.tawc.install

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AdrenoGpu.detect] against strings real devices actually report.
 *
 * The whole point of the check is telling Snapdragons apart from the other
 * arm64 SoCs, so the cases use the values those families ship — `qcom` or a
 * SoC codename on Qualcomm, the platform name elsewhere — rather than
 * invented ones that would only prove the code agrees with itself.
 */
class AdrenoGpuTest {
    private fun detect(
        soc: String? = null,
        hardware: String? = null,
        kgsl: Boolean = false,
    ): Boolean = AdrenoGpu.detect(soc, hardware, kgsl)

    @Test
    fun `the kgsl node alone is enough`() {
        // The node is the hardware, and what Turnip opens: it outranks the
        // string probes, which are only ever proxies for it.
        assertTrue(detect(kgsl = true))
    }

    @Test
    fun `soc manufacturer is enough`() {
        assertTrue(detect(soc = "Qualcomm"))
    }

    @Test
    fun `soc manufacturer matches case-insensitively and within a longer name`() {
        assertTrue(detect(soc = "qualcomm"))
        // Some builds report the full legal name.
        assertTrue(detect(soc = "Qualcomm Technologies, Inc"))
    }

    @Test
    fun `the values a real Snapdragon reports`() {
        // Redmi K60 Ultra (SM8475), as actually observed on the device:
        //   ro.soc.manufacturer = QTI      -- the abbreviation, not "Qualcomm"
        //   ro.hardware         = qcom
        //   /dev/kgsl-3d0       present
        // Each probe is asserted alone as well, so a regression in one of them
        // cannot hide behind the other two covering for it.
        assertTrue(detect(soc = "QTI"))
        assertTrue(detect(hardware = "qcom"))
        assertTrue(detect(kgsl = true))
        assertTrue(detect(soc = "QTI", hardware = "qcom", kgsl = true))
    }

    @Test
    fun `hardware qcom is enough`() {
        assertTrue(detect(hardware = "qcom"))
    }

    @Test
    fun `qualcomm soc codenames are enough`() {
        assertTrue(detect(hardware = "sm8550"))
        assertTrue(detect(hardware = "msm8998"))
        assertTrue(detect(hardware = "sdm845"))
        assertTrue(detect(hardware = "qcs6490"))
        assertTrue(detect(hardware = "apq8084"))
        // `Build.HARDWARE` is uppercase on some builds.
        assertTrue(detect(hardware = "SM8650"))
    }

    @Test
    fun `other arm64 platforms are rejected`() {
        val notQualcomm = listOf(
            "mt6983",          // MediaTek Dimensity, Mali
            "exynos990",       // Samsung, Mali
            "universal9820",   // Exynos 9820's board name
            "kirin9000",       // HiSilicon, Mali
            "gs201",           // Google Tensor G2, Mali
            "rk3588",          // Rockchip
        )
        for (hardware in notQualcomm) {
            assertFalse("$hardware was taken for an Adreno", detect(hardware = hardware))
        }
        assertFalse(detect(soc = "MediaTek"))
        assertFalse(detect(soc = "Samsung"))
    }

    @Test
    fun `merely starting with those letters is not enough`() {
        // Why the pattern is anchored *and* digit-suffixed: `samsung` starts
        // with `sm`, and a bare `msm` has no SoC number.
        assertFalse(detect(hardware = "samsung"))
        assertFalse(detect(hardware = "smth"))
        assertFalse(detect(hardware = "msm"))
    }

    @Test
    fun `having nothing to go on is a no`() {
        assertFalse(detect())
        assertFalse(detect(soc = "", hardware = ""))
    }

    @Test
    fun `one convinced probe carries it`() {
        // Probes are independent on purpose: each has a hole, and a device only
        // has to look Qualcomm on one of them.
        assertTrue(detect(soc = "MediaTek", hardware = "mt6983", kgsl = true))
        assertTrue(detect(soc = "Qualcomm", hardware = "exynos990"))
    }
}
