package me.phie.tawc.install

import me.phie.tawc.install.distro.ImportedPack
import me.phie.tawc.install.distro.PackageBootstrap
import me.phie.tawc.install.distro.TarballBootstrap
import me.phie.tawc.install.distro.origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * An imported pack is the one bootstrap the app can't vouch for: it
 * arrives as a file the user picked, with no upstream to check it
 * against. What these pin is that the *rest* of the machinery still
 * treats it as an ordinary bootstrap rather than a special case that
 * quietly skips things.
 */
class ImportedPackTest {

    private val pack = ImportedPack(
        uri = "content://com.android.providers.downloads.documents/document/42",
        displayName = "dsh-rootfs-arch-aarch64.tar.zst",
        format = BootstrapFormat.ZSTD,
    )

    private val tuna = "https://mirrors.tuna.tsinghua.edu.cn/alarm"

    @Test
    fun formatComesFromTheFileName() {
        assertEquals(
            BootstrapFormat.ZSTD,
            ImportedPack.formatFor("dsh-rootfs-arch-aarch64.tar.zst"),
        )
        assertEquals(BootstrapFormat.GZIP, ImportedPack.formatFor("bootstrap.tar.gz"))
        assertEquals(BootstrapFormat.XZ, ImportedPack.formatFor("bootstrap.tar.xz"))
    }

    @Test
    fun anUnknownOrAbsentExtensionIsRefusedRatherThanGuessed() {
        // A provider is free to report a display name with no extension.
        // Picking a decompressor for it would move the failure to
        // several hundred megabytes later, inside tar.
        assertNull(ImportedPack.formatFor("dsh-pack"))
        assertNull(ImportedPack.formatFor("dsh-pack.zip"))
        assertNull(ImportedPack.formatFor(null))
        // `.zst` alone isn't a tar; the extension must be the compound one.
        assertNull(ImportedPack.formatFor("thing.zst"))
    }

    @Test
    fun aPackHasNoOriginToShowOrReroot() {
        assertNull(pack.origin)
        // The other descriptors still answer, so the accessor isn't
        // just returning null for everything.
        assertEquals(
            "https://example.org/os/x.tar.gz",
            TarballBootstrap(
                url = "https://example.org/os/x.tar.gz",
                format = BootstrapFormat.GZIP,
                stripPrefix = null,
                verification = BootstrapVerification.ResolvedAtInstallTime,
            ).origin,
        )
        assertEquals(
            "http://deb.debian.org/debian",
            PackageBootstrap(
                archiveRoot = "http://deb.debian.org/debian",
                suite = "sid",
                packagesArch = "arm64",
                keyResource = "debian_archive_keyring",
            ).origin,
        )
    }

    @Test
    fun aMirrorSettingHasNoEffectOnAPack() {
        // A pack is already on the device. Running it through the mirror
        // rewrite must not invent a URL for it — the rewrite returns a
        // copy for the fetchable descriptors and the same instance here.
        assertSame(pack, BootstrapMirror.apply(pack, null, tuna))
        assertSame(pack, BootstrapMirror.apply(pack, "https://example.org/", tuna))
        assertSame(pack, BootstrapMirror.apply(pack, "https://example.org/", ""))
    }
}
