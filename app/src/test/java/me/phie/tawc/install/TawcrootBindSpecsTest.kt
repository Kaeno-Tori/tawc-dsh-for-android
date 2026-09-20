package me.phie.tawc.install

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RO/RW split of [TawcrootMethod.bindSpecs]: the system-partition
 * (libhybris dlopen) binds, the app-shipped asset dirs, and read-only
 * external binds emit tawcroot's 3-field `:ro` form; every other
 * built-in and writable external binds keep the 2-field RW form.
 * Exact-list assertions also pin bind order (asset dirs grouped with
 * the other RO dlopen sources, all built-ins before external so user
 * binds can't shadow the system/share/asset set).
 */
class TawcrootBindSpecsTest {
    private val files = "/data/data/me.phie.tawc/files"
    private val share = "$files/share"
    private val hybrisDirs =
        listOf("/apex", "/vendor", "/system", "/system_ext")

    private val assetBinds = listOf(
        TawcrootMethod.BindSpec("$files/libhybris", "/usr/lib/hybris", ro = true),
        TawcrootMethod.BindSpec("$files/turnip", "/usr/lib/turnip", ro = true),
    )

    @Test
    fun systemBindsRoOthersRw() {
        val args = TawcrootMethod.bindSpecs(
            tawcShare = share,
            libhybrisDirs = hybrisDirs,
            assetBinds = assetBinds,
            externalBinds = listOf(
                ExternalBind("/storage/emulated/0", "/home/android"),
                ExternalBind("/", "/android", readOnly = true),
            ),
            andoHostDir = "/data/data/me.phie.tawc/files/ando/arch",
        ).map { it.arg() }
        assertEquals(
            listOf(
                "/dev:/dev",
                "/proc:/proc",
                "/sys:/sys",
                "/apex:/apex:ro",
                "/vendor:/vendor:ro",
                "/system:/system:ro",
                "/system_ext:/system_ext:ro",
                "$files/libhybris:/usr/lib/hybris:ro",
                "$files/turnip:/usr/lib/turnip:ro",
                "$share:/usr/share/tawc",
                "/data/data/me.phie.tawc/files/ando/arch:/run/tawc-ando",
                "/storage/emulated/0:/home/android",
                "/:/android:ro",
            ),
            args,
        )
    }

    /** No asset shipped for this ABI (x86_64 without libhybris, a
     *  backend-disabled build): the bind list is exactly the pre-asset
     *  shape, not an empty slot or a bind to a missing dir — tawcroot
     *  `exit(93)`s on a bind src that doesn't exist. */
    @Test
    fun noAssetsAndNoAndoOmitsThoseBinds() {
        val args =
            TawcrootMethod.bindSpecs(share, hybrisDirs, emptyList(), emptyList(), null)
                .map { it.arg() }
        assertEquals(
            listOf(
                "/dev:/dev",
                "/proc:/proc",
                "/sys:/sys",
                "/apex:/apex:ro",
                "/vendor:/vendor:ro",
                "/system:/system:ro",
                "/system_ext:/system_ext:ro",
                "$share:/usr/share/tawc",
            ),
            args,
        )
    }
}
