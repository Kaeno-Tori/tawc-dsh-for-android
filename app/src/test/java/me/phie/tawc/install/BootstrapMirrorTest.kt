package me.phie.tawc.install

import me.phie.tawc.install.distro.DistroRegistry
import me.phie.tawc.install.distro.MirrorPresets
import me.phie.tawc.install.distro.PackageBootstrap
import me.phie.tawc.install.distro.TarballBootstrap
import me.phie.tawc.install.distro.origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BootstrapMirror] is the only way to point a bootstrap download at a
 * mirror, and it is a prefix replacement rather than an origin swap —
 * both because mirrors re-root upstream's tree (ALARM's `/os/` lives
 * under `/alarm/os/` on Tsinghua) and because a wrong-but-plausible URL
 * fails as a confusing 404 rather than as an error. These tests pin the
 * substitution, the "all or nothing" rule, and the no-op cases.
 */
class BootstrapMirrorTest {

    private val alarm = DistroRegistry.all.first {
        it.bootstrapMirrorPrefix?.contains("archlinuxarm.org") == true
    }

    /** As the distro declares it — with a trailing slash, i.e. a directory. */
    private val upstream = alarm.bootstrapMirrorPrefix!!

    /** Same root, no trailing slash; a user typing a mirror writes this shape. */
    private val upstreamBare = upstream.trimEnd('/')

    private val tuna = "https://mirrors.tuna.tsinghua.edu.cn/alarm"

    @Test
    fun rewritesThePathUnderTheMirrorRootNotJustTheOrigin() {
        // The mirror re-roots the tree: upstream's `/os/` becomes
        // `…/alarm/os/`, which is where ALARM's Chinese mirrors keep it.
        // An origin swap would have produced `…/alarm/os/…` only by
        // coincidence; for Debian it would have doubled `/debian`.
        assertEquals(
            "$tuna/os/ArchLinuxARM-aarch64-latest.tar.gz",
            BootstrapMirror.rewrite(alarm.bootstrap.url, upstream, tuna),
        )
    }

    @Test
    fun exactlyOneSlashJoinsTheTwoHalves() {
        // Both Arch distros declare the prefix with a trailing slash
        // because it names a directory; a user typing a mirror usually
        // won't. Neither should produce `…/alarm//os/…`.
        val expected = "$tuna/os/ArchLinuxARM-aarch64-latest.tar.gz"
        assertEquals(expected, BootstrapMirror.rewrite(alarm.bootstrap.url, upstream, tuna))
        assertEquals(expected, BootstrapMirror.rewrite(alarm.bootstrap.url, upstreamBare, tuna))
        assertEquals(expected, BootstrapMirror.rewrite(alarm.bootstrap.url, upstream, "$tuna/"))
    }

    @Test
    fun surroundingWhitespaceIsTolerated() {
        val url = alarm.bootstrap.url
        assertEquals(
            BootstrapMirror.rewrite(url, upstream, "  $tuna  "),
            BootstrapMirror.rewrite(url, upstream, tuna),
        )
    }

    @Test
    fun aUrlOutsideThePrefixIsNotRewritten() {
        assertNull(BootstrapMirror.rewrite("https://example.org/os/x", upstream, tuna))
    }

    @Test
    fun aHostThatMerelyStartsWithThePrefixHostIsNotRewritten() {
        // `startsWith` alone matches this. It isn't a trust boundary (the
        // mirror is user-supplied anyway) but it would rewrite a URL that
        // isn't under the declared root, which is worse than doing nothing.
        assertNull(
            BootstrapMirror.rewrite(
                "${upstreamBare}.evil.example/os/x", upstream, tuna,
            ),
        )
    }

    @Test
    fun aBaseWithoutASchemeIsRefusedRatherThanUsed() {
        // Half-typed input must not turn into a download URL that can
        // never resolve; leaving the distro alone is the recoverable
        // failure.
        assertNull(
            BootstrapMirror.rewrite(alarm.bootstrap.url, upstream, "mirrors.tuna.tsinghua.edu.cn/alarm"),
        )
    }

    @Test
    fun anEmptyBaseLeavesTheDescriptorAlone() {
        assertSame(alarm.bootstrap, BootstrapMirror.apply(alarm.bootstrap, upstream, ""))
        assertSame(alarm.bootstrap, BootstrapMirror.apply(alarm.bootstrap, upstream, "   "))
    }

    @Test
    fun aDistroWithoutAPrefixIsNeverRewritten() {
        // Null is the honest default for a bootstrap resolved live from
        // somewhere no distro mirror serves — Debian's debuerreotype
        // tarball on raw.githubusercontent is the one left. Claiming such
        // a URL is mirrorable would corrupt it rather than mirror it.
        val unmirrorable = DistroRegistry.all.filter { it.bootstrapMirrorPrefix == null }
        assertTrue("no prefix-less distro to test against", unmirrorable.isNotEmpty())
        for (distro in unmirrorable) {
            assertSame(distro.bootstrap, BootstrapMirror.apply(distro.bootstrap, null, tuna))
        }
    }

    @Test
    fun everyDistroWithAPrefixActuallySitsUnderIt() {
        // The prefix is declared by hand per distro; if it ever drifts
        // from the URL it's supposed to describe, every mirrored
        // install for that distro silently falls back to the upstream
        // origin — the rewrite returns null and apply() keeps the
        // original, which looks like "the mirror setting does nothing".
        for (distro in DistroRegistry.all) {
            val prefix = distro.bootstrapMirrorPrefix ?: continue
            for ((flavor, descriptor) in distro.declaredBootstrapFlavors) {
                val url = requireNotNull(descriptor.origin) {
                    "${distro.displayName} $flavor: a distro must not declare an " +
                        "imported pack — those come from the user's storage"
                }
                assertTrue(
                    "${distro.displayName} $flavor: $url does not sit under its " +
                        "bootstrapMirrorPrefix $prefix",
                    url.startsWith(prefix) || url.startsWith(prefix.trimEnd('/') + "/"),
                )
            }
        }
    }

    @Test
    fun theSignatureMovesWithTheArtifactItSigns() {
        // A mirrored tarball paired with an unmirrored .sig would fail
        // verification with no hint as to why. Both URLs come out of the
        // same rewrite, so the pair is always coherent — and a mirror
        // serving different bytes still fails closed against the key in
        // res/raw.
        val rewritten = BootstrapMirror.apply(alarm.bootstrap, upstream, tuna) as TarballBootstrap
        assertEquals(
            "$tuna/os/ArchLinuxARM-aarch64-latest.tar.gz",
            rewritten.url,
        )
        assertEquals(
            "$tuna/os/ArchLinuxARM-aarch64-latest.tar.gz.sig",
            (rewritten.verification as BootstrapVerification.Pgp).signatureUrl,
        )
    }

    @Test
    fun digestVerificationIsCarriedThroughUntouched() {
        // A digest is a value, not a location: mirroring can't weaken
        // (or strengthen) it, and rewriting the policy would be a lie.
        val descriptor = TarballBootstrap(
            url = "${upstreamBare}/os/x.tar.gz",
            format = alarm.bootstrap.format,
            stripPrefix = alarm.bootstrap.stripPrefix,
            verification = BootstrapVerification.Sha256("a".repeat(64)),
        )
        val rewritten = BootstrapMirror.apply(descriptor, upstream, tuna) as TarballBootstrap
        assertEquals(descriptor.verification, rewritten.verification)
        assertEquals("$tuna/os/x.tar.gz", rewritten.url)
    }

    @Test
    fun aPackageBootstrapRewritesItsArchiveRoot() {
        // Debian's tarball lives on raw.githubusercontent.com, which no
        // Debian archive mirror serves — so the distro declares no
        // prefix. The packages flavor's archive root *is* mirrorable,
        // and this is the shape a distro would point at one.
        val descriptor = PackageBootstrap(
            archiveRoot = "http://deb.debian.org/debian",
            suite = "sid",
            packagesArch = "arm64",
            keyResource = "debian_archive_keyring",
        )
        val rewritten = BootstrapMirror.apply(
            descriptor, "http://deb.debian.org/", tuna,
        ) as PackageBootstrap
        assertEquals("$tuna/debian", rewritten.archiveRoot)
        assertEquals(descriptor.suite, rewritten.suite)
        assertEquals(descriptor.packagesArch, rewritten.packagesArch)
        assertEquals(descriptor.keyResource, rewritten.keyResource)
    }

    @Test
    fun everyPresetWorksForEveryMirrorableDistro() {
        // The presets are one shared list of origins; each distro supplies
        // the path underneath. If a distro declares a path but not a
        // prefix (or vice versa) the rewrite returns null and apply()
        // keeps the upstream URL — the user picks "Aliyun", sees the same
        // upstream URL, and has no way to tell why.
        var checked = 0
        for (distro in DistroRegistry.all) {
            val path = distro.bootstrapMirrorPath ?: continue
            assertTrue(
                "${distro.displayName}: has a mirror path ($path) but no " +
                    "bootstrapMirrorPrefix, so nothing can be re-rooted",
                distro.bootstrapMirrorPrefix != null,
            )
            for (preset in MirrorPresets.ALL) {
                val base = preset.origin + path
                for ((flavor, descriptor) in distro.declaredBootstrapFlavors) {
                    val rewritten = BootstrapMirror.apply(
                        descriptor, distro.bootstrapMirrorPrefix, base,
                    )
                    assertTrue(
                        "${distro.displayName} $flavor: mirror base $base did not " +
                            "re-root anything",
                        rewritten !== descriptor,
                    )
                    val url = requireNotNull(rewritten.origin) {
                        "${distro.displayName} $flavor: rewriting produced a " +
                            "descriptor with no address"
                    }
                    assertTrue(
                        "${distro.displayName} $flavor: rewritten URL $url is not " +
                            "under $base",
                        url.startsWith("$base/"),
                    )
                    checked++
                }
            }
        }
        assertTrue("no mirrorable distro to check", checked > 0)
    }

    @Test
    fun aliyunIsOffered() {
        // The one preset explicitly asked for; a Chinese device that can
        // reach neither GitHub nor a US mirror needs at least this.
        assertTrue(
            "no Aliyun preset",
            MirrorPresets.ALL.any { it.origin == "https://mirrors.aliyun.com" },
        )
    }
}
