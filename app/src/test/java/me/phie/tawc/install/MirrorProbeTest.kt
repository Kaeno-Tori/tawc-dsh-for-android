package me.phie.tawc.install

import me.phie.tawc.install.distro.Distro
import me.phie.tawc.install.distro.DistroRegistry
import me.phie.tawc.install.distro.MirrorPresets
import me.phie.tawc.install.distro.TarballBootstrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The pure half of the mirror probe: turning a candidate base into the
 * exact descriptor the install would fetch. The measuring half needs a
 * network and is covered by hand on a device (the log lines it emits are
 * the record) — except for [MirrorProbe.orderSources], which is driven
 * against loopback servers below because its ordering is what an
 * imported-pack install trusts.
 */
class MirrorProbeTest {

    /**
     * ALARM — the one distro whose bootstrap tarball is mirrored widely
     * enough to be worth choosing between. Selected by prefix, not by
     * key: both Arch flavours share `key == "arch"` and are told apart
     * by ABI.
     */
    private val alarm: Distro = DistroRegistry.all.first {
        it.bootstrapMirrorPrefix?.contains("archlinuxarm.org") == true
    }

    private val alarmBootstrap = TarballBootstrap(
        url = "https://fl.us.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz",
        format = BootstrapFormat.GZIP,
        stripPrefix = null,
        verification = BootstrapVerification.Pgp(
            signatureUrl = "https://fl.us.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz.sig",
            keyResource = "archlinuxarm.gpg",
        ),
    )

    @Test
    fun aPinnedMirrorRewritesBothTheArchiveAndItsSignature() {
        // The signature has to move with the archive: a mirrored tarball
        // checked against an unmirrored .sig is a verification failure
        // after 800 MB, and that pairing is what BootstrapMirror.apply
        // exists to keep atomic.
        val target = MirrorProbe
            .pinned(alarmBootstrap, alarm.bootstrapMirrorPrefix, "https://mirrors.aliyun.com/archlinuxarm")
            .single()

        assertEquals("https://mirrors.aliyun.com/archlinuxarm", target.base)
        assertEquals("mirrors.aliyun.com", target.label)
        val rewritten = target.bootstrap as TarballBootstrap
        assertEquals(
            "https://mirrors.aliyun.com/archlinuxarm/os/ArchLinuxARM-aarch64-latest.tar.gz",
            rewritten.url,
        )
        assertEquals(
            "https://mirrors.aliyun.com/archlinuxarm/os/ArchLinuxARM-aarch64-latest.tar.gz.sig",
            (rewritten.verification as BootstrapVerification.Pgp).signatureUrl,
        )
    }

    @Test
    fun aPinCannotReRootADescriptorThatIsAlreadyOnTheMirror() {
        // Re-rooting is not idempotent, and the second application is
        // indistinguishable from "this base can't serve this distro":
        // the URL no longer sits under the distro's upstream prefix, so
        // `pinned` returns nothing and the caller silently measures
        // instead — losing the pin, and with it the base the package
        // manager was supposed to inherit. Which descriptor the installer
        // hands over is therefore load-bearing, and this is the test that
        // says so.
        val base = "https://mirrors.aliyun.com/archlinuxarm"
        val once = BootstrapMirror
            .apply(alarmBootstrap, alarm.bootstrapMirrorPrefix, base) as TarballBootstrap

        assertTrue(MirrorProbe.pinned(once, alarm.bootstrapMirrorPrefix, base).isEmpty())
        // From the distro's own URL it works — that is the one to pass.
        assertEquals(1, MirrorProbe.pinned(alarmBootstrap, alarm.bootstrapMirrorPrefix, base).size)
    }

    @Test
    fun aDistroWithNoMirrorPrefixHasNothingToPin() {
        // Debian resolves its bootstrap from a debuerreotype tarball:
        // there is no upstream root a mirror could stand in for, so the
        // caller falls through to measuring rather than being handed a
        // URL that 404s.
        val noPrefix = DistroRegistry.all.first { it.bootstrapMirrorPrefix == null }
        assertTrue(
            MirrorProbe.pinned(alarmBootstrap, noPrefix.bootstrapMirrorPrefix, "https://x.example").isEmpty(),
        )
    }

    @Test
    fun anUnrelatedBaseIsRejectedRatherThanProbed() {
        // A base that cannot be re-rooted onto this bootstrap is not a
        // candidate — probing it would measure someone else's server and
        // then download a 404 from it.
        assertTrue(
            MirrorProbe.pinned(alarmBootstrap, alarm.bootstrapMirrorPrefix, "ftp://mirrors.aliyun.com").isEmpty(),
        )
    }

    @Test
    fun everyOfferedPresetIsAProbeCandidate() {
        // The install form's dropdown and the probe's candidate pool are
        // the same list by construction; this pins the composition so a
        // preset can't be selectable but unmeasured, or vice versa.
        val bases = MirrorPresets.basesFor(alarm.bootstrapMirrorPath!!)
        assertEquals(MirrorPresets.ALL.size, bases.size)
        assertTrue(bases.all { it.endsWith("/archlinuxarm") })
        assertTrue(bases.contains("https://mirrors.aliyun.com/archlinuxarm"))
        assertNotNull(
            MirrorProbe.pinned(alarmBootstrap, alarm.bootstrapMirrorPrefix, bases.first()).singleOrNull(),
        )
    }

    /**
     * One package-repository candidate, shaped the way
     * `ArchPacmanCommon.packageProbeUrls` builds it: the base it was
     * re-rooted from, its host as the label, and the URL a read would
     * hit. `host` doubles as the label so the two loopback names below
     * stay distinguishable.
     */
    private fun source(host: String, port: Int, path: String = "archlinuxarm/aarch64/extra/extra.db") =
        MirrorProbe.Source(base = "http://$host:$port", label = host, url = "http://$host:$port/$path")

    @Test
    fun packageSourcesAreOrderedByMeasuredThroughput() {
        // Phase 1 needs a listening port *and* phase 2 needs a body, so
        // both are real sockets here: the probe takes a URL's own port
        // for the handshake, which is what lets loopback stand in for a
        // mirror. The slow server ships its body slowly, which is the
        // only difference that matters.
        FakeMirror("127.0.0.1", chunkDelayMs = 300, bytes = 4 * 1024).use { slow ->
            FakeMirror("localhost", chunkDelayMs = 2, bytes = 256 * 1024).use { fast ->
                val logs = mutableListOf<String>()
                val ordered = MirrorProbe.orderSources(
                    listOf(source("127.0.0.1", slow.port), source("localhost", fast.port)),
                    logs::add,
                )

                assertEquals(listOf("localhost", "127.0.0.1"), ordered.map { it.label })
                assertTrue("both sources should have been measured: $ordered", ordered.all { it.rateBps > 0 })
                assertTrue(
                    "the faster mirror should win: $ordered",
                    ordered.first().rateBps > ordered.last().rateBps,
                )
                assertEquals("http://localhost:${fast.port}", ordered.first().base)
                assertEquals("[mirror] measuring 2 sources for this network", logs.first())
                assertEquals("[mirror] order: localhost, 127.0.0.1", logs.last())
            }
        }
    }

    @Test
    fun anUnreachableSourceListComesBackInDeclaredOrder() {
        // The contract the caller leans on: whatever the network does, it
        // gets a list back to use — the unmeasured order is exactly what
        // it would have used before this existed. Nothing listens on
        // port 1, so every handshake fails.
        val declared = listOf(
            source("127.0.0.1", 1),
            source("127.0.0.2", 1),
            source("localhost", 1),
        )
        val logs = mutableListOf<String>()

        val ordered = MirrorProbe.orderSources(declared, logs::add)

        assertEquals(declared, ordered)
        assertTrue(ordered.isNotEmpty())
        assertTrue(logs.contains("[mirror] no candidate answered a TCP handshake"))
        assertTrue(logs.contains("[mirror] nothing measured; keeping the declared order"))
    }

    @Test
    fun aRepositoryUrlIsMeasuredWithoutASignatureBesideIt() {
        // The `.sig` step belongs to the tarball path: it exists so a
        // mirror missing the detached signature is dropped before the
        // download, not after. ALARM publishes no `.db.sig` at all, so a
        // repository probe that inherited the step would drop every
        // candidate and leave pacman on the six US/EU hosts this route
        // is trying to get away from.
        FakeMirror("127.0.0.1", chunkDelayMs = 2, bytes = 64 * 1024).use { mirror ->
            val repoLogs = mutableListOf<String>()
            val measured = MirrorProbe.orderSources(
                listOf(source("127.0.0.1", mirror.port), source("127.0.0.2", 1)),
                repoLogs::add,
            )
            assertTrue(
                "the repository source has no .sig and must survive anyway: $repoLogs",
                measured.first { it.label == "127.0.0.1" }.rateBps > 0,
            )
            assertTrue(repoLogs.none { it.contains("no signature") })

            // Same host, tarball shape: there the gate does fire, so the
            // difference above is the gate's absence and not a server
            // that answers everything.
            val tarballLogs = mutableListOf<String>()
            val archive = "http://127.0.0.1:${mirror.port}/os/ArchLinuxARM-aarch64-latest.tar.gz"
            val targets = MirrorProbe.order(
                bases = listOf("http://127.0.0.1:${mirror.port}"),
                bootstrap = TarballBootstrap(
                    url = archive,
                    format = BootstrapFormat.GZIP,
                    stripPrefix = null,
                    verification = BootstrapVerification.Pgp(
                        signatureUrl = "$archive.sig",
                        keyResource = "archlinuxarm_signing_key",
                    ),
                ),
                prefix = "http://127.0.0.1:${mirror.port}/",
                log = tarballLogs::add,
            )
            assertTrue("a signature-less tarball source is dropped: $tarballLogs", targets.none { it.rateBps > 0 })
            assertTrue(tarballLogs.any { it.endsWith("no signature beside the archive (dropped)") })
        }
    }

    /**
     * A minimal HTTP server on loopback, for the probe's phase 2.
     *
     * The body arrives in 4 KiB chunks, each preceded by `chunkDelayMs`,
     * which is how the test manufactures a "slow mirror" without a slow
     * network. Both halves of that arrangement are load-bearing: the
     * probe reads the status line before it starts timing
     * ([MirrorProbe]'s `readThroughput`), so a delay spent before the
     * headers measures as instant; and it stops reading once
     * `Content-Length` is satisfied, so a body delivered in one piece
     * measures as instant too. Dribbling the body out is what lands the
     * delay inside the timed window.
     *
     * `.sig` paths answer 404, like a real repository mirror: the
     * contrast test above needs the tarball path's signature gate to
     * actually fire.
     */
    private class FakeMirror(host: String, private val chunkDelayMs: Long, private val bytes: Int) : Closeable {
        private val server = ServerSocket(0, 0, InetAddress.getByName(host))

        val port: Int get() = server.localPort

        private val worker = Thread({
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                try {
                    serve(socket)
                } catch (_: IOException) {
                    // A client that hung up early is not this test's problem.
                } finally {
                    runCatching { socket.close() }
                }
            }
        }, "fake-mirror").apply {
            isDaemon = true
            start()
        }

        private fun serve(socket: Socket) {
            // The handshake connects and closes without sending a request.
            val head = readHead(socket.getInputStream()) ?: return
            if (!head.startsWith("GET ")) return
            val path = head.split(' ')[1]
            val out = socket.getOutputStream()
            if (path.endsWith(".sig")) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                return
            }
            out.write(
                (
                    "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                        "Content-Length: $bytes\r\nConnection: close\r\n\r\n"
                    ).toByteArray(),
            )
            val chunk = ByteArray(4 * 1024)
            var sent = 0
            while (sent < bytes) {
                // Slept *after* the headers, not before them: the probe
                // waits for the status line inside `responseCode`, which
                // is outside its timed section, so a delay up there is
                // invisible. Here the client is already timing and blocks
                // in `read` for exactly this long.
                Thread.sleep(chunkDelayMs)
                val n = minOf(chunk.size, bytes - sent)
                out.write(chunk, 0, n)
                out.flush()
                sent += n
            }
        }

        private fun readHead(input: InputStream): String? {
            val head = StringBuilder()
            var b = input.read()
            while (b >= 0) {
                head.append(b.toChar())
                if (head.endsWith("\r\n\r\n")) return head.toString()
                b = input.read()
            }
            return null
        }

        override fun close() {
            // Closing the listening socket is the whole shutdown: the
            // worker is blocked in accept and breaks out of its loop on
            // the resulting IOException. Interrupting it as well would
            // only uncaught an InterruptedException if it happened to be
            // mid-chunk.
            server.close()
        }
    }
}
