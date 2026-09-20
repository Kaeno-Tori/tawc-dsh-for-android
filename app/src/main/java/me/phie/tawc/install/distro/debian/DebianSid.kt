package me.phie.tawc.install.distro.debian

import me.phie.tawc.install.BootstrapFormat
import me.phie.tawc.install.BootstrapVerification
import me.phie.tawc.install.Installation
import me.phie.tawc.install.InstallationMethod
import me.phie.tawc.install.MirrorProxy
import me.phie.tawc.install.distro.BootstrapFlavor
import me.phie.tawc.install.distro.Distro
import me.phie.tawc.install.distro.DistroBootstrap
import me.phie.tawc.install.distro.PackageBootstrap
import me.phie.tawc.install.distro.TarballBootstrap
import me.phie.tawc.install.distro.apt.AptCommon

internal sealed class DebianSid(
    override val linuxArch: String,
    override val androidAbi: String,
    private val bashbrewArch: String,
    /** dpkg architecture (`arm64` / `amd64`) — names the archive's
     *  `binary-<arch>` index for the packages flavor. */
    private val dpkgArch: String,
) : Distro {
    final override val key: String = Installation.DISTRO_DEBIAN_SID
    final override val displayName: String = "Debian Sid"
    final override val supported: Boolean = true
    final override val defaultLabel: String = "Sid"
    final override val cacheKey: String = "$key-$linuxArch"

    final override val bootstrap: TarballBootstrap = TarballBootstrap(
        url = "https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/dist-$bashbrewArch/sid/oci/blobs/rootfs.tar.gz",
        format = BootstrapFormat.GZIP,
        stripPrefix = null,
        verification = BootstrapVerification.ResolvedAtInstallTime,
    )

    /** Real debootstrap on-device against the signed sid archive,
     *  trust-rooted in the shipped
     *  `res/raw/debian_archive_keyring.asc`. Debug builds only — see
     *  [me.phie.tawc.install.EnabledBootstrapFlavors]. */
    private val packageBootstrap = PackageBootstrap(
        archiveRoot = REPO_URL,
        suite = SUITE,
        packagesArch = dpkgArch,
        keyResource = "debian_archive_keyring",
    )

    /**
     * Two flavors: the debuerreotype tarball (supported, unchanged,
     * the default everywhere) and `packages`, which stays debug-only
     * until it has earned supported status — release builds filter it
     * out of `bootstrapFlavors` entirely.
     */
    final override val declaredBootstrapFlavors: Map<BootstrapFlavor, DistroBootstrap> = mapOf(
        BootstrapFlavor.TARBALL to bootstrap,
        BootstrapFlavor.PACKAGES to packageBootstrap,
    )

    final override fun resolveBootstrap(
        log: (String) -> Unit,
        mirrorProxy: MirrorProxy?,
        flavor: BootstrapFlavor,
    ): DistroBootstrap = when (flavor) {
        BootstrapFlavor.TARBALL -> {
            log("debian sid: resolving latest $linuxArch rootfs via OCI manifest")
            val b = DebianDockerResolver.resolve(SUITE, bashbrewArch, mirrorProxy)
            val v = b.verification as BootstrapVerification.Sha256
            log("debian sid: rootfs=${b.url} sha256=${v.expectedHex}")
            b
        }
        // Static descriptor is complete: all live data (InRelease,
        // index) is fetched and verified inside the packages installer
        // itself, behind the shipped-keyring trust boundary.
        BootstrapFlavor.PACKAGES -> packageBootstrap
    }

    final override val basePackages: List<String> = AptCommon.DEFAULT_BASE_PACKAGES

    final override val runtimePackages: List<String> = AptCommon.RUNTIME_PACKAGES

    final override fun configure(
        method: InstallationMethod,
        rootfs: String,
        mirrorProxy: MirrorProxy?,
        log: (String) -> Unit,
        // Ignored: [AptCommon] pins one archive URL, and Debian's mirror
        // story (a `deb.debian.org` CDN + a signed InRelease) has no
        // equivalent of a per-host `/os/` directory to measure. The
        // bootstrap probe still applies to this distro — see
        // `Distro.bootstrapMirrorPath`.
        @Suppress("UNUSED_PARAMETER") mirrorBases: List<String>,
    ) = AptCommon.configure(
        method = method,
        rootfs = rootfs,
        suite = SUITE,
        repoUrl = REPO_URL,
        signedBy = DEBIAN_ARCHIVE_KEYRING,
        mirrorProxy = mirrorProxy,
        log = log,
    )

    final override fun initPackageManager(method: InstallationMethod, rootfs: String, log: (String) -> Unit) =
        AptCommon.initPackageManager(method, rootfs, log)

    final override fun installPackages(
        method: InstallationMethod,
        rootfs: String,
        packages: List<String>,
        log: (String) -> Unit,
    ) = AptCommon.installPackages(method, rootfs, packages, log)

    companion object {
        private const val SUITE = "sid"
        private const val REPO_URL = "http://deb.debian.org/debian"
        private const val DEBIAN_ARCHIVE_KEYRING = "/usr/share/keyrings/debian-archive-keyring.pgp"
    }
}

internal object DebianSidX86_64 : DebianSid(
    linuxArch = "x86_64",
    androidAbi = "x86_64",
    bashbrewArch = "amd64",
    dpkgArch = "amd64",
)

internal object DebianSidAarch64 : DebianSid(
    linuxArch = "aarch64",
    androidAbi = "arm64-v8a",
    bashbrewArch = "arm64v8",
    dpkgArch = "arm64",
)
