package me.phie.tawc.install

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards [Http] against the two ways it can regress.
 *
 * The bug this exists for: Android's `HttpURLConnection` sends
 * `User-Agent: Dalvik/…` unless told otherwise, and Aliyun answers 403
 * to that (and to no UA at all). The bootstrap tarball and its detached
 * signature are fetched by *different* classes, so fixing one and not
 * the other is silent until it isn't — the tarball downloads for
 * fifteen minutes and then verification fails.
 */
class HttpTest {

    @Test
    fun requestsIdentifyThemselvesWithAnOrdinaryUserAgent() {
        // openConnection() doesn't connect, so this needs no network.
        val ua = Http.open("https://example.org/x").getRequestProperty("User-Agent")
        assertTrue("no User-Agent set", !ua.isNullOrBlank())
        assertFalse(
            "User-Agent '$ua' still looks like the platform default, which " +
                "some mirrors reject with 403",
            ua!!.startsWith("Dalvik"),
        )
    }

    @Test
    fun noCallSiteOpensItsOwnConnection() {
        val srcDir = sequenceOf("src/main/java", "app/src/main/java")
            .map(::File).firstOrNull { it.isDirectory }
            ?: error("cannot locate src/main/java")

        val offenders = mutableListOf<String>()
        srcDir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            if (file.name == "Http.kt") return@forEach
            // Connectivity is about call sites, not prose: the KDoc in
            // several of these files names the API it stopped using.
            file.readLines().forEachIndexed { i, line ->
                val code = line.substringBefore("//").substringBefore("*")
                if (code.contains("openConnection()")) {
                    offenders += "${file.relativeTo(srcDir)}:${i + 1}"
                }
            }
        }
        assertTrue(
            "these bypass Http.open and would send Android's Dalvik " +
                "User-Agent over the wire: $offenders",
            offenders.isEmpty(),
        )
    }
}
