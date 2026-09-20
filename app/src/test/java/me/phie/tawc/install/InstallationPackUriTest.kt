package me.phie.tawc.install

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `packUri` metadata round-trip.
 *
 * The field exists so "start over" after a failed import retries the
 * *pack* rather than reinstalling from the upstream mirror — the same
 * button, a different operation, and nothing on screen to tell them
 * apart. So the two properties that matter are that a URI survives a
 * save/load, and that ordinary (downloaded) installs don't acquire one.
 */
class InstallationPackUriTest {

    private val uri = "content://com.android.providers.downloads.documents/document/42"

    private fun imported() = Installation(
        id = "arch",
        distro = Installation.DISTRO_ARCH,
        arch = "arm64-v8a",
        method = "tawcroot",
        installedAtMillis = 1L,
        sourceUrl = "import:dsh-rootfs-arch-aarch64.tar.zst",
        packUri = uri,
    )

    private fun downloaded() = Installation(
        id = "arch",
        distro = Installation.DISTRO_ARCH,
        arch = "arm64-v8a",
        method = "tawcroot",
        installedAtMillis = 1L,
        sourceUrl = "https://fl.us.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz",
    )

    @Test
    fun anImportedInstallRoundTrips() {
        val parsed = Installation.fromJson(imported().toJson())
        assertEquals(uri, parsed.packUri)
        assertEquals("import:dsh-rootfs-arch-aarch64.tar.zst", parsed.sourceUrl)
    }

    @Test
    fun aDownloadedInstallCarriesNoUri() {
        val json = JSONObject(downloaded().toJson())
        assertFalse("no packUri should be written for a download", json.has("packUri"))
        assertNull(Installation.fromJson(json.toString()).packUri)
    }

    @Test
    fun recordsWrittenBeforePacksExistedParseAsNull() {
        // Absent field, not an empty string — and the empty string a
        // JSON writer might produce must land on null too, or "start
        // over" would try to open a URI that isn't one.
        val json = JSONObject(downloaded().toJson())
        json.put("packUri", "")
        assertNull(Installation.fromJson(json.toString()).packUri)
    }
}
