package me.phie.tawc.install

import android.util.Log
import me.phie.tawc.install.distro.DistroBootstrap
import me.phie.tawc.install.distro.TarballBootstrap
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Picks the mirror to download from by **measuring this network**, not
 * by guessing where the device is.
 *
 * The problem this exists for: upstream ALARM's bootstrap lives on one
 * US host, and on a bad route that is a ~800 MB fetch at single-digit
 * KB/s — 6 KB/s on the tablet this was written for, i.e. a day and a
 * half. The app already knew how to *re-root* a download onto a mirror
 * ([BootstrapMirror]) and shipped five Chinese origins as one-tap
 * choices, but nothing chose for the user, and the one-tap install path
 * never set a mirror at all.
 *
 * **No geolocation, by design.** Timezone and IP lookups are proxies
 * for "which mirror is fast", and a worse proxy than the thing itself:
 * a mirror being nearby doesn't mean the route to it is good, and the
 * lookup costs a third-party request (privacy, availability, one more
 * way for an install to fail) to learn something we can just measure.
 * Upstream's own answer — `mirror.archlinuxarm.org` is a GeoIP
 * redirector — doesn't help here either: it carries the package tree,
 * not `/os/` (the tarball 404s on the mirror it redirected us to), and
 * its certificate only covers `archlinuxarm.org`, so it can't be
 * reached over HTTPS at all.
 *
 * **The probe doubles as the availability check.** A mirror that
 * doesn't carry `/os/` (Tsinghua and BFSU both 403 it — they sync the
 * package tree, not the tarball), that has no `.sig` beside the
 * archive, or that is unreachable from this network fails the probe and
 * drops out of the candidate list on its own. That is why the list
 * needs no hand-curation: the check that would have been manual work is
 * the measurement we were going to do anyway.
 *
 * Two phases: a parallel TCP handshake to rank candidates cheaply, then
 * a real ranged read of the tarball for the shortlist. The second phase
 * is the one that matters — a mirror can win the handshake and still be
 * slow — and it must **count bytes itself**: some mirrors (JLU,
 * measured) ignore `Range` and answer `200` with the whole 62 MB body.
 *
 * Not `internal`, unlike its neighbours: [Source] is handed back through
 * the public [me.phie.tawc.install.distro.Distro.packageProbeUrls] hook,
 * and Kotlin won't let a public interface member expose an internal
 * type. The tarball-specific shapes ([Target], [order], [pinned]) stay
 * internal — only the URL-shaped pair is reachable from a distro.
 */
object MirrorProbe {

    private const val TAG = "tawc-install"

    /** Per-candidate phase-1 budget. A blackholed host must not stall the rest. */
    private const val CONNECT_TIMEOUT_MS = 2_500

    /** Phase 1 as a whole: whatever hasn't answered by now isn't a candidate. */
    private const val HANDSHAKE_BUDGET_MS = 3_000L

    /**
     * Phase 2 reads at most this much per candidate, or stops at
     * [MEASURE_DEADLINE_MS] — whichever comes first. The cap is why a
     * 6 KB/s mirror reports 6 KB/s in two seconds instead of holding
     * the install for four minutes while "measuring" it.
     */
    private const val MEASURE_BYTES = 256 * 1024
    private const val MEASURE_DEADLINE_MS = 2_000L

    /** How many handshake winners get a real throughput measurement. */
    private const val MEASURE_COUNT = 3

    /** Port the phase-1 handshake uses for a URL that doesn't name one. */
    private const val DEFAULT_PORT = 443

    /**
     * One place the install could fetch the bootstrap from: a mirror
     * (re-rooted descriptor) or the distro's own upstream ([base] null,
     * descriptor untouched).
     */
    internal data class Target(
        /** Mirror base to hand the package-manager side, or null for upstream. */
        val base: String?,
        /** Human label for log lines. */
        val label: String,
        /** The descriptor to actually download. */
        val bootstrap: DistroBootstrap,
        /**
         * Measured throughput in bytes/second, or 0 for a candidate the
         * probe never got to (or never measured). Callers that write a
         * package-mirror list read this: only a source proven on *this*
         * network is worth putting in front of pacman.
         */
        val rateBps: Long = 0,
    )

    /**
     * One package-repository URL to measure, as the shape it already is
     * — no descriptor to re-root and no upstream to fall back to,
     * because the install this serves downloads nothing.
     *
     * **No signature step, unlike [Target].** The tarball probe drops a
     * candidate whose `.sig` isn't beside the archive because that is a
     * verification failure waiting to happen after the whole download.
     * A repository has no such file to check — ALARM publishes no
     * `.db.sig` at all — so the same step here would drop every
     * candidate and leave the package manager on the distro's shipped
     * list, which is the thing this measurement exists to replace.
     */
    data class Source(
        /** Mirror base to hand the package manager, so a winner is usable as-is. */
        val base: String,
        /** Human label for log lines, and the key results come back under. */
        val label: String,
        /** The URL actually read. */
        val url: String,
        /**
         * Measured throughput in bytes/second, or 0 for a source the
         * probe never got to (or never measured) — the same contract as
         * [Target.rateBps], and read the same way by callers that write
         * a package-mirror list.
         */
        val rateBps: Long = 0,
    )

    /**
     * [sources] ordered for this network, best first. The URL-shaped
     * twin of [order]: same two phases, same constants, same log lines,
     * same never-empty contract, minus the `.sig` step (see [Source])
     * and minus the appended upstream (there is none).
     *
     * Never returns empty for a non-empty input: an interrupted or
     * all-failed probe returns the unmeasured order, which is what the
     * caller would have used before this existed.
     *
     * @throws InterruptedIOException when the install is cancelled
     *   mid-probe, so the caller's own cancel handling stays the only
     *   place that decides what a cancelled install does.
     */
    internal fun orderSources(sources: List<Source>, log: (String) -> Unit): List<Source> {
        // Nothing to choose between, exactly as in [order]: one
        // candidate has no race to run, and running it would still leave
        // nothing to reorder.
        if (sources.size < 2) return sources

        log("[mirror] measuring ${sources.size} sources for this network")
        val rateByLabel = try {
            measure(sources.map { Contender(it.label, it.url, signatureUrl = null) }, log)
        } catch (_: InterruptedException) {
            throw InterruptedIOException("mirror probe cancelled")
        }
        if (rateByLabel.isEmpty()) {
            log("[mirror] nothing measured; keeping the declared order")
            return sources
        }
        // Stable sort, as in [order]: measured candidates by throughput,
        // everything else after them in declared order.
        val ordered = sources
            .sortedByDescending { rateByLabel[it.label] ?: -1L }
            .map { s -> rateByLabel[s.label]?.let { s.copy(rateBps = it) } ?: s }
        log("[mirror] order: " + ordered.joinToString(", ") { it.label })
        return ordered
    }

    /**
     * [bases] ordered for this network, best first, with the distro's
     * upstream appended as the last resort.
     *
     * Never returns empty: an interrupted or all-failed probe returns
     * the unmeasured order, which is exactly what the caller would have
     * used before this existed. Callers walk the result on failure.
     *
     * @throws InterruptedIOException when the install is cancelled
     *   mid-probe, so the caller's own cancel handling stays the only
     *   place that decides what a cancelled install does.
     */
    internal fun order(
        bases: List<String>,
        bootstrap: DistroBootstrap,
        prefix: String?,
        log: (String) -> Unit,
    ): List<Target> {
        val rootable = if (prefix == null) {
            emptyList()
        } else {
            bases.mapNotNull { base -> target(bootstrap, prefix, base) }
        }
        val upstream = (bootstrap as? TarballBootstrap)?.let {
            Target(base = null, label = hostOf(it.url) ?: "upstream", bootstrap = it)
        }
        val candidates = rootable + listOfNotNull(upstream)
        // Nothing to choose between: an imported pack is local, a
        // non-mirrorable distro (Manjaro's GitHub release) has nothing
        // to re-root, and one candidate has no race to run.
        if (candidates.size < 2) return candidates

        log("[mirror] measuring ${candidates.size} sources for this network")
        val rateByLabel = try {
            measure(
                candidates.mapNotNull { c ->
                    // Every re-rooted candidate is a [TarballBootstrap];
                    // one that isn't carries no URL to measure.
                    val tarball = c.bootstrap as? TarballBootstrap ?: return@mapNotNull null
                    Contender(
                        label = c.label,
                        url = tarball.url,
                        signatureUrl = (tarball.verification as? BootstrapVerification.Pgp)
                            ?.signatureUrl,
                    )
                },
                log,
            )
        } catch (_: InterruptedException) {
            throw InterruptedIOException("mirror probe cancelled")
        }
        if (rateByLabel.isEmpty()) {
            log("[mirror] nothing measured; keeping the declared order")
            return candidates
        }
        // Stable sort: measured candidates by throughput, everything
        // else after them in declared order. Those trailing entries are
        // the fallback chain — an unmeasured source is a worse *bet*
        // than a measured one, not a proven-bad one, and the upstream is
        // deliberately among them as the last resort.
        val ordered = candidates
            .sortedByDescending { rateByLabel[it.label] ?: -1L }
            .map { c -> rateByLabel[c.label]?.let { c.copy(rateBps = it) } ?: c }
        log("[mirror] order: " + ordered.joinToString(", ") { it.label })
        return ordered
    }

    /**
     * The single-target list for a mirror the user pinned by hand: no
     * measuring, no alternatives, no fallback. Empty when this distro
     * can't be re-rooted onto [base] at all, which is the caller's cue
     * to ignore the setting rather than to download a 404.
     */
    internal fun pinned(bootstrap: DistroBootstrap, prefix: String?, base: String): List<Target> =
        prefix?.let { target(bootstrap, it, base) }?.let { listOf(it) } ?: emptyList()

    /**
     * Re-root [bootstrap] onto [base], or null when this distro can't
     * be mirrored (or the base is unusable). Reuses the rewrite the
     * download itself will use, so the probe measures the exact URL —
     * per-distro path differences included — rather than a lookalike.
     */
    private fun target(bootstrap: DistroBootstrap, prefix: String, base: String): Target? {
        val rewritten = BootstrapMirror.apply(bootstrap, prefix, base)
        if (rewritten === bootstrap) return null
        return Target(base = base, label = hostOf(base) ?: base, bootstrap = rewritten)
    }

    /**
     * What the probe needs from one candidate once the caller's shape
     * has been flattened away — the two entry points above hold
     * different things ([Target] vs. [Source]) and this is the overlap.
     */
    private data class Contender(
        val label: String,
        val url: String,
        /**
         * Detached signature that must exist beside [url] to keep this
         * candidate, or null when there is nothing to check. Null is
         * the repository case, not a missing value.
         */
        val signatureUrl: String?,
    )

    /**
     * Phase 1 (parallel handshakes) → phase 2 (real reads). Returns
     * label → bytes/second for the candidates that passed both; a
     * candidate the probe *rejected* (no data, no `.sig`) is simply
     * absent, and so is one it never got to.
     */
    private fun measure(candidates: List<Contender>, log: (String) -> Unit): Map<String, Long> {
        val latency = Collections.synchronizedMap(mutableMapOf<Contender, Long>())
        inParallel(candidates, HANDSHAKE_BUDGET_MS) { c ->
            handshakeMs(c.url)?.let { latency[c] = it }
        }
        val ranked = candidates.mapNotNull { c -> latency[c]?.let { c to it } }.sortedBy { it.second }
        if (ranked.isEmpty()) {
            log("[mirror] no candidate answered a TCP handshake")
            return emptyMap()
        }
        log("[mirror] handshake: " + ranked.joinToString(", ") { "${it.first.label} ${it.second}ms" })

        val rates = mutableMapOf<String, Long>()
        for ((candidate, _) in ranked.take(MEASURE_COUNT)) {
            val throughput = readThroughput(candidate.url)
            if (throughput == null || throughput <= 0) {
                log("[mirror] ${candidate.label}: no data (dropped)")
                continue
            }
            // A mirror without the .sig is a guaranteed verification
            // failure — the expensive way to find out, after the whole
            // download. Only asked when the descriptor says a detached
            // signature is what we'll fetch.
            val sig = candidate.signatureUrl
            if (sig != null && !reaches(sig)) {
                log("[mirror] ${candidate.label}: no signature beside the archive (dropped)")
                continue
            }
            log("[mirror] ${candidate.label}: ${humanRate(throughput)}")
            rates[candidate.label] = throughput
        }
        return rates
    }

    /**
     * Run [worker] for every item at once, waiting at most [budgetMs]
     * for all of them. Daemon threads: a straggler is bounded by its
     * own socket timeouts and can be left behind — the install must not
     * be held hostage by the slowest mirror, which is the whole point
     * of ranking them.
     */
    private fun <T> inParallel(items: List<T>, budgetMs: Long, worker: (T) -> Unit) {
        val latch = CountDownLatch(items.size)
        for (item in items) {
            Thread({
                try {
                    worker(item)
                } catch (_: Throwable) {
                    // A candidate that throws is a candidate that lost.
                } finally {
                    latch.countDown()
                }
            }, "tawc-mirror-probe").apply { isDaemon = true }.start()
        }
        latch.await(budgetMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Milliseconds to open a TCP connection to [url]'s endpoint, or null
     * when it doesn't answer.
     *
     * The port is taken from the URL rather than pinned to 443 so the
     * handshake measures the endpoint the phase-2 read would use; every
     * URL this app produces is https on the default port, so that is
     * what it resolves to in practice.
     */
    private fun handshakeMs(url: String): Long? {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return null
        }
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else DEFAULT_PORT
        val start = System.nanoTime()
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
            (System.nanoTime() - start) / 1_000_000
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /** True when [url] answers 2xx. The cheap half of the `.sig` check. */
    private fun reaches(url: String): Boolean = openQuietly(url) { conn ->
        conn.responseCode in 200..299
    } ?: false

    /**
     * Bytes per second over the first [MEASURE_BYTES] of [url], or null
     * when it can't be read.
     *
     * Reads with its own counter and closes early on purpose: `Range` is
     * advisory, and a mirror that ignores it (JLU, measured) would
     * otherwise stream its entire body into the measurement.
     */
    private fun readThroughput(url: String): Long? = openQuietly(url) { conn ->
        conn.setRequestProperty("Range", "bytes=0-${MEASURE_BYTES - 1}")
        if (conn.responseCode !in 200..299) return@openQuietly null
        val deadline = System.nanoTime() + MEASURE_DEADLINE_MS * 1_000_000
        val buf = ByteArray(64 * 1024)
        var read = 0L
        val start = System.nanoTime()
        conn.inputStream.use { input ->
            while (read < MEASURE_BYTES && System.nanoTime() < deadline) {
                val want = minOf(buf.size.toLong(), MEASURE_BYTES - read).toInt()
                val n = input.read(buf, 0, want)
                if (n < 0) break
                read += n
            }
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        if (read <= 0 || elapsedMs <= 0) null else read * 1000 / elapsedMs
    }

    /**
     * [Http.open] plus timeouts, `disconnect()` and a null-on-failure
     * contract. Every candidate is a host we don't control, so "cannot
     * even open a connection" has to be an answer rather than an
     * exception.
     */
    private fun <T> openQuietly(url: String, body: (HttpURLConnection) -> T): T? = try {
        val conn = Http.open(url).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = CONNECT_TIMEOUT_MS
        }
        try {
            body(conn)
        } finally {
            conn.disconnect()
        }
    } catch (t: Throwable) {
        Log.d(TAG, "mirror probe: $url: ${t.message}")
        null
    }

    /**
     * Host of [url], or null when it can't be parsed.
     *
     * Shared with the callers that build [Source] values
     * ([me.phie.tawc.install.distro.arch.ArchPacmanCommon.packageProbeUrls]),
     * so a probe log line names a mirror the same way whichever entry
     * point measured it.
     */
    internal fun hostOf(url: String): String? = try {
        URI(url).host
    } catch (_: Exception) {
        null
    }

    private fun humanRate(bytesPerSecond: Long): String =
        if (bytesPerSecond >= 1024 * 1024) {
            "%.1f MiB/s".format(bytesPerSecond / 1024.0 / 1024.0)
        } else {
            "${bytesPerSecond / 1024} KiB/s"
        }
}
