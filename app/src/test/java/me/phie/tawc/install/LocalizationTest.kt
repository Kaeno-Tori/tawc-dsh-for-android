package me.phie.tawc.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [Localization.zoneFor], which is a guard rather than a mapping — and
 * the guard is the whole point.
 *
 * glibc falls back to parsing `TZ` as a **POSIX TZ string** when the
 * value isn't a zone it can find, and POSIX inverts the sign. Android
 * returns a `GMT±HH:MM` string for a manually-chosen offset and an
 * IANA-shaped id otherwise, so "pass the device's id through" is right
 * for the common case and catastrophically wrong for the other one:
 * `TZ=GMT+08:00` would put a CST container at UTC−8, sixteen hours from
 * the device instead of eight. These tests pin the difference.
 */
class LocalizationTest {

    @Test
    fun ianaShapedIdsPassThrough() {
        assertEquals("Asia/Shanghai", Localization.zoneFor("Asia/Shanghai"))
        assertEquals("America/New_York", Localization.zoneFor("America/New_York"))
        assertEquals("UTC", Localization.zoneFor("UTC"))
        // Passes the shape check *and* is a real file, which is what
        // makes it safe: glibc looks up a `/`-bearing value under
        // /usr/share/zoneinfo instead of POSIX-parsing it.
        assertEquals("Etc/GMT+8", Localization.zoneFor("Etc/GMT+8"))
    }

    @Test
    fun manualOffsetsAreRefusedRatherThanInverted() {
        // The case this exists for. Both of these are POSIX TZ strings,
        // not zones, and POSIX reads the sign backwards.
        assertNull(Localization.zoneFor("GMT+08:00"))
        assertNull(Localization.zoneFor("GMT-05:00"))
        // Slash-less and not `UTC`: `GMT` happens to mean what it says
        // when POSIX-parsed, but there is nothing in the value that says
        // so, and the container is no worse off falling back to
        // /etc/localtime.
        assertNull(Localization.zoneFor("GMT"))
        assertNull(Localization.zoneFor(""))
    }

    @Test
    fun theZoneIsAlsoAStringThatGoesIntoAShellScript() {
        // `TZ` itself travels as one `env -i K=V` argv element, so it
        // cannot inject anything there. The zone is *also* interpolated
        // into the `ln -s /usr/share/zoneinfo/<zone>` line of
        // [Localization.configure], where it can — which makes the shape
        // check a correctness guard and an injection guard at once.
        assertNull(Localization.zoneFor("Asia/Shanghai; rm -rf /"))
        assertNull(Localization.zoneFor("/../../etc/shadow"))
        assertNull(Localization.zoneFor("../../etc/shadow"))
    }

    @Test
    fun surroundingWhitespaceIsTolerated() {
        assertEquals("Asia/Shanghai", Localization.zoneFor(" Asia/Shanghai\n"))
    }
}
