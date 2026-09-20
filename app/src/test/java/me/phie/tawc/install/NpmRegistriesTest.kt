package me.phie.tawc.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The npm-registry candidate list, which is pure data plus the URL
 * shape derived from it.
 *
 * Worth a test because [MirrorProbe.orderSources] keys its results by
 * [MirrorProbe.Source.label] and sorts by that lookup: a duplicated
 * label would silently collapse two registries into one entry, and the
 * install would then never fall back to the other. The scoped-name
 * encoding is the other half — a packument URL with a literal slash is
 * a 404 on every registry, which reads as "no registry has the
 * package" rather than as a bug.
 */
class NpmRegistriesTest {

    private val packageName = "@deepseek-ai/dsh"

    @Test
    fun everyCandidateIsAScopedPackumentUrl() {
        val sources = NpmRegistries.probeSources(packageName)
        assertTrue(sources.isNotEmpty())
        for (source in sources) {
            // Written out rather than rebuilt from [packageName]: the
            // point is the literal shape the registry API expects (the
            // scope separator encoded, the package name's slash not).
            assertEquals(
                "unexpected packument URL for ${source.label}",
                "${source.base}/@deepseek-ai%2Fdsh",
                source.url,
            )
            assertTrue(source.url.startsWith("https://"))
            assertTrue(source.label.isNotBlank())
        }
    }

    @Test
    fun labelsAndBasesAreDistinct() {
        val sources = NpmRegistries.probeSources(packageName)
        assertEquals(sources.size, sources.map { it.label }.toSet().size)
        assertEquals(sources.size, sources.map { it.base }.toSet().size)
        // A base is handed to npm as `--registry=` and written to
        // `.npmrc` verbatim, so separators that npm would have to
        // normalise are worth catching here.
        assertTrue(sources.all { !it.base.endsWith("/") })
    }
}
