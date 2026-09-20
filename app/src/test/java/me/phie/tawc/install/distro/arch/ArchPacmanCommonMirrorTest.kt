package me.phie.tawc.install.distro.arch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mirror choice has to cover pacman's package sources too, not just
 * the bootstrap tarball — an install that measures a fast mirror for the
 * 800 MB archive and then leaves pacman pointed at the host it was
 * routing around gets the worst of both: 11 KB/s after a 6 KB/s
 * download.
 *
 * The URL rewrite is shared with the bootstrap path, but the *shape* of
 * a pacman repo URL differs per distro, so these pin both.
 */
class ArchPacmanCommonMirrorTest {

    private val alarmPrefix = "https://fl.us.mirror.archlinuxarm.org/"
    private val alarmBody = """
        # United States
        Server = https://fl.us.mirror.archlinuxarm.org/${'$'}arch/${'$'}repo
        Server = https://ca.us.mirror.archlinuxarm.org/${'$'}arch/${'$'}repo
        Server = http://mirror.archlinuxarm.org/${'$'}arch/${'$'}repo
    """.trimIndent()

    private val aliyun = "https://mirrors.aliyun.com/archlinuxarm"
    private val ustc = "https://mirrors.ustc.edu.cn/archlinuxarm"
    private val nju = "https://mirrors.nju.edu.cn/archlinuxarm"

    /**
     * The distro's own upstream, which the probe adds to the pool by
     * itself — it is not one of [aliyun]/[ustc]/[nju], and no caller
     * passes it in. See
     * [theOfficialSourceIsAlwaysACandidateAndComesLast].
     */
    private val alarmOfficial = "https://fl.us.mirror.archlinuxarm.org"
    private val x86Official = "https://geo.mirror.pkgbuild.com"

    @Test
    fun alarmRepoUrlsKeepTheirArchAndRepoPlaceholders() {
        assertEquals(
            "Server = https://mirrors.aliyun.com/archlinuxarm/\$arch/\$repo",
            ArchPacmanCommon.mirrorListFor(alarmBody, alarmPrefix, listOf(aliyun)),
        )
    }

    @Test
    fun x86RepoUrlsKeepTheirRepoOsArchTail() {
        // Arch x86_64's tail is `$repo/os/$arch`, not `$arch/$repo` —
        // guessing the wrong one is exactly why the rewrite is a prefix
        // replacement rather than a template.
        assertEquals(
            "Server = https://mirrors.aliyun.com/archlinux/\$repo/os/\$arch",
            ArchPacmanCommon.mirrorListFor(
                "Server = https://geo.mirror.pkgbuild.com/\$repo/os/\$arch",
                "https://geo.mirror.pkgbuild.com/",
                listOf("https://mirrors.aliyun.com/archlinux"),
            ),
        )
    }

    @Test
    fun everyMeasuredMirrorBecomesAServerLineInOrder() {
        // pacman walks these in order on a 404, so the measured order has
        // to survive verbatim — sorting or de-duplicating here would
        // throw away the race the probe just ran.
        assertEquals(
            listOf(
                "Server = https://mirrors.aliyun.com/archlinuxarm/\$arch/\$repo",
                "Server = https://mirrors.ustc.edu.cn/archlinuxarm/\$arch/\$repo",
                "Server = https://mirrors.nju.edu.cn/archlinuxarm/\$arch/\$repo",
            ),
            ArchPacmanCommon.mirrorListFor(alarmBody, alarmPrefix, listOf(aliyun, ustc, nju))
                ?.lines(),
        )
    }

    @Test
    fun upstreamHostsAreDropped() {
        // Only the caller's bases become `Server =` lines; the body's
        // other upstream entries do not survive. A leftover entry there
        // is a silent fallback to a host we measured as bad, or that the
        // user set a mirror to avoid.
        //
        // This is about entries we were *not* handed, not about banning
        // upstream: the distro's own host is a legitimate line when the
        // probe measured it there (see
        // [theOfficialSourceIsAlwaysACandidateAndComesLast]) — it just
        // arrives as a base like any other.
        val result = ArchPacmanCommon.mirrorListFor(alarmBody, alarmPrefix, listOf(aliyun))!!
        assertEquals(1, result.lineSequence().count { it.startsWith("Server =") })
        assertEquals(false, result.contains("mirror.archlinuxarm.org"))
    }

    @Test
    fun anUnusableBaseIsSkippedRatherThanEmitted() {
        // A base the rewriter rejects (wrong scheme, or blank) must not
        // become a `Server = ` line with no URL after it — pacman would
        // fail the whole transaction on it.
        assertEquals(
            "Server = https://mirrors.aliyun.com/archlinuxarm/\$arch/\$repo",
            ArchPacmanCommon.mirrorListFor(alarmBody, alarmPrefix, listOf("", aliyun, "ftp://x")),
        )
    }

    @Test
    fun noMirrorLeavesTheCallerToUseTheOriginal() {
        assertNull(ArchPacmanCommon.mirrorListFor(alarmBody, alarmPrefix, listOf("")))
        assertNull(ArchPacmanCommon.mirrorListFor(alarmBody, alarmPrefix, listOf("   ")))
        assertNull(ArchPacmanCommon.mirrorListFor(alarmBody, alarmPrefix, emptyList()))
        assertNull(ArchPacmanCommon.mirrorListFor(alarmBody, null, listOf(aliyun)))
    }

    @Test
    fun aBodyWithNoServerLineIsNotRewritten() {
        assertNull(ArchPacmanCommon.mirrorListFor("# nothing here\n", alarmPrefix, listOf(aliyun)))
    }

    @Test
    fun probeUrlsAreTheDatabaseFileOnEachMirror() {
        // What pacman fetches from a `Server = <dir>` line is
        // `<dir>/<repo>.db` — that is the URL worth timing, and the one
        // an imported pack's install measures. `extra` on aarch64 is the
        // path a device was verified against.
        assertEquals(
            listOf(
                "https://mirrors.aliyun.com/archlinuxarm/aarch64/extra/extra.db",
                "https://mirrors.ustc.edu.cn/archlinuxarm/aarch64/extra/extra.db",
                "https://fl.us.mirror.archlinuxarm.org/aarch64/extra/extra.db",
            ),
            ArchPacmanCommon.packageProbeUrls(
                alarmBody, alarmPrefix, listOf(aliyun, ustc), repo = "extra", arch = "aarch64",
            ).map { it.url },
        )
    }

    @Test
    fun probeUrlsCarryTheBaseAndTheHostTheProbeLogsUnder() {
        // `base` is what lands in `configure`'s mirrorBases, and the
        // label has to be the host so these results read like every
        // other mirror line in the install log.
        val sources = ArchPacmanCommon.packageProbeUrls(
            alarmBody, alarmPrefix, listOf(ustc), repo = "extra", arch = "aarch64",
        )
        assertEquals(listOf(ustc, alarmOfficial), sources.map { it.base })
        assertEquals(listOf("mirrors.ustc.edu.cn", "fl.us.mirror.archlinuxarm.org"), sources.map { it.label })
    }

    @Test
    fun theOfficialSourceIsAlwaysACandidateAndComesLast() {
        // The package-mirror pool includes the distro's own upstream,
        // measured like any mirror rather than assumed to be slow: on a
        // network where it is genuinely the fastest source, picking it
        // is the honest answer.
        //
        // Last, though, and that part is load-bearing. The probe sorts
        // by measured throughput and leaves *unmeasured* candidates in
        // declared order — so the position is what makes upstream the
        // last resort when the race tells us nothing.
        //
        // It is in the pool even when no preset is usable: the official
        // host is derived from the prefix, so it rewrites regardless of
        // what the callers passed. Which is also why this is not part of
        // [nothingToProbeWithoutAPrefixOrATemplate] any more — invalid
        // bases no longer mean nothing to measure.
        val sources = ArchPacmanCommon.packageProbeUrls(
            alarmBody, alarmPrefix, listOf(ustc, aliyun), repo = "extra", arch = "aarch64",
        )
        assertEquals(alarmOfficial, sources.last().base)
        assertEquals(
            listOf(alarmOfficial),
            ArchPacmanCommon.packageProbeUrls(
                alarmBody, alarmPrefix, listOf("", "ftp://x"), repo = "extra", arch = "aarch64",
            ).map { it.base },
        )
    }

    @Test
    fun x86ProbeUrlsKeepTheRepoOsArchOrder() {
        // Same substitution, the other tail order: x86's template is
        // `$repo/os/$arch`, so a hardcoded ALARM-shaped probe URL would
        // 404 on every mirror.
        assertEquals(
            listOf(
                "https://mirrors.aliyun.com/archlinux/extra/os/x86_64/extra.db",
                "$x86Official/extra/os/x86_64/extra.db",
            ),
            ArchPacmanCommon.packageProbeUrls(
                "Server = https://geo.mirror.pkgbuild.com/${'$'}repo/os/${'$'}arch",
                "https://geo.mirror.pkgbuild.com/",
                listOf("https://mirrors.aliyun.com/archlinux"),
                repo = "extra",
                arch = "x86_64",
            ).map { it.url },
        )
    }

    @Test
    fun eachArchFlavourProbesItsOwnPackageTree() {
        // The two distro overrides, end to end: each one hands the probe
        // the list pacman will actually use on that arch — ALARM's base
        // is `/archlinuxarm` with an `$arch/$repo` tail, x86's is
        // `/archlinux` with `$repo/os/$arch`. Each also gets its own
        // upstream appended, derived from its own prefix, which is what
        // keeps the two from sharing a host.
        assertEquals(
            listOf(
                "https://mirrors.ustc.edu.cn/archlinuxarm/aarch64/extra/extra.db",
                "https://fl.us.mirror.archlinuxarm.org/aarch64/extra/extra.db",
            ),
            ArchLinuxArm.packageProbeUrls(listOf(ustc)).map { it.url },
        )
        assertEquals(
            listOf(
                "https://mirrors.ustc.edu.cn/archlinux/extra/os/x86_64/extra.db",
                "$x86Official/extra/os/x86_64/extra.db",
            ),
            ArchLinuxX86_64.packageProbeUrls(listOf("https://mirrors.ustc.edu.cn/archlinux")).map { it.url },
        )
    }

    @Test
    fun nothingToProbeWithoutAPrefixOrATemplate() {
        // No prefix means this distro's tree can't be re-rooted; no
        // Server line means there is no shape to re-root. Both are
        // answers, not errors — the caller keeps the distro's own list.
        assertTrue(
            ArchPacmanCommon.packageProbeUrls("# nothing\n", alarmPrefix, listOf(aliyun), "extra", "aarch64")
                .isEmpty(),
        )
        assertTrue(
            ArchPacmanCommon.packageProbeUrls(alarmBody, null, listOf(aliyun), "extra", "aarch64").isEmpty(),
        )
    }
}
