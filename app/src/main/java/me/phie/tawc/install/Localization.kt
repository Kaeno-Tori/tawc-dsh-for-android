package me.phie.tawc.install

import android.util.Log
import java.io.IOException
import java.util.TimeZone

/**
 * The device's locale and timezone, written into a freshly-installed
 * rootfs and re-applied on every spawn.
 *
 * Neither is guesswork about *where* the device is — both are read from
 * Android, which already knows (and already keeps them in sync with the
 * network). What the container lacks is any way to *ask*:
 *
 *  - **Timezone.** Measured on a fresh ALARM container: `/etc/localtime`
 *    does not exist, so `date` reads UTC — eight hours off in CST.
 *    `tzdata` is already installed and `/usr/share/zoneinfo/Asia/Shanghai`
 *    is there, so this costs nothing but the link.
 *  - **Locale.** Measured: ALARM ships `/etc/locale.conf` containing
 *    `LANG=C`, and its own `/etc/profile.d/locale.sh` reads that file
 *    before defaulting to `C.UTF-8` — so `locale charmap` is
 *    `ANSI_X3.4-1968`, i.e. ASCII. Every "wide character" behaviour
 *    (`cut -c`, `wc -m`, `printf %5s`, `[[:alpha:]]`) is then counted in
 *    bytes, and GUI toolkits derive their message language from
 *    `LC_MESSAGES`, which derives from `LANG`.
 *
 * Both halves need the same treatment twice over, for a reason that is
 * easy to miss: `/etc/locale.conf` and `/etc/localtime` are files read
 * by the *distro's* startup path, and that path only runs for **login**
 * shells. Measured: under `env -i bash -c` (what DSH's bash tool and
 * every non-interactive spawn look like) `LANG` is not merely `C` — it is
 * *unset*, because `locale.sh` never ran. So the files cover the
 * terminal, and [RootfsEnv] carries the same two values so every spawn
 * covers everything else.
 *
 * **The env copy is also the only part that is actually automatic.**
 * `/etc/localtime` is a link to a zone, fixed at install time; the
 * `TZ` in [RootfsEnv] is re-read from Android on every spawn, so a
 * device whose timezone changes (travel, or the user switching off
 * automatic time) is followed without touching the container.
 *
 * Nothing here installs anything. `tzdata` and the `C.UTF-8` locale are
 * both already present in every supported distro — `glibc-locales` is
 * *not*, which is why the locale is `C.UTF-8` (UTF-8 semantics, English
 * messages) rather than `zh_CN.UTF-8`; the latter needs a ~200 MB
 * package and is a deliberate product decision, not an oversight.
 */
internal object Localization {

    private const val TAG = "tawc-install"

    /** Guest path of the locale config the distros' own startup reads. */
    const val GUEST_LOCALE_CONF = "/etc/locale.conf"

    /**
     * The locale every container gets, and the one [RootfsEnv] passes.
     *
     * `C.UTF-8` rather than `C`: the encoding is the half that changes
     * behaviour, and it costs nothing — it is built into glibc, and
     * ALARM's own `locale.sh` defaults to it when `/etc/locale.conf`
     * doesn't get in the way. Deliberately not `LC_ALL` (which would
     * override anything the user sets later) and deliberately not a
     * `zh_CN.*` locale (needs `glibc-locales`, ~200 MB, and is not what
     * an English-message toolchain wants by default).
     */
    const val LANG = "C.UTF-8"

    /**
     * [androidZone] as a `TZ` value, or null when it cannot be used
     * safely.
     *
     * The shape check is load-bearing. glibc treats a `TZ` value that
     * isn't a known zone **as a POSIX TZ string**, and POSIX inverts the
     * sign: `TZ=GMT+08:00` means UTC−8, so passing an Android manual
     * offset through verbatim would put the container *sixteen* hours
     * away from the device instead of eight. Android returns an
     * IANA-shaped id (`Asia/Shanghai`) for the normal "set automatically"
     * case and a `GMT±HH:MM` string only for a manually-chosen offset;
     * the latter fails this check and the caller falls back to whatever
     * `/etc/localtime` says (usually UTC — wrong, but wrong the safe
     * way, and stable).
     *
     * `/` is required for the same reason: it is what makes glibc look
     * the name up under `/usr/share/zoneinfo` instead of parsing it.
     * `UTC` is the one slash-less id worth taking, and `Etc/GMT+8`
     * passes on the slash and resolves to a real file (correctly —
     * file lookup wins over the POSIX parse).
     */
    fun zoneFor(androidZone: String): String? {
        val id = androidZone.trim()
        if (id == "UTC") return id
        if (!id.contains('/')) return null
        return id.takeIf { ZONE_RE.matches(it) }
    }

    private val ZONE_RE = Regex("""[A-Za-z][A-Za-z0-9_+\-]*(/[A-Za-z0-9_+\-]+)+""")

    /**
     * Write [GUEST_LOCALE_CONF] and link `/etc/localtime` into [rootfs].
     *
     * Runs via [method.runOutside] — `su` on the chroot path, plain
     * app-uid writes on proot — because `/etc` belongs to root in the
     * extracted tree.
     *
     * Called for **every** flavor, imported packs included: which zone a
     * container is in, and whether its tools speak UTF-8, are properties
     * of *this device*, not of the rootfs that happens to be on disk.
     */
    fun configure(
        method: InstallationMethod,
        rootfs: String,
        log: (String) -> Unit,
    ) {
        val zone = zoneFor(TimeZone.getDefault().id)
        log(
            "[locale] LANG=$LANG" +
                (zone?.let { ", TZ=$it" } ?: ", no usable timezone from Android"),
        )
        // `ROOTFS` is not something [InstallationMethod.runOutside] sets
        // — the distro configure scripts each export it themselves — so
        // this script has to do the same or `set -u` kills it.
        val script = buildString {
            appendLine("# Device locale and timezone. Rewritten on every install.")
            appendLine("ROOTFS='$rootfs'")
            appendLine("mkdir -p \"\$ROOTFS/etc\"")
            appendLine("printf 'LANG=%s\\n' '$LANG' > \"\$ROOTFS$GUEST_LOCALE_CONF\"")
            if (zone == null) {
                appendLine("rm -f \"\$ROOTFS/etc/localtime\"")
            } else {
                // The link target is the **guest** absolute path, i.e.
                // what resolves once the rootfs is chrooted (or
                // path-rewritten) — not the host path we're writing from.
                // `rm` before `ln` rather than `ln -sf`: replacing an
                // existing symlink with a plain `-f` is not something
                // every toybox/ln combination does.
                appendLine("if [ -e \"\$ROOTFS/usr/share/zoneinfo/$zone\" ]; then")
                appendLine("  rm -f \"\$ROOTFS/etc/localtime\"")
                appendLine("  ln -s \"/usr/share/zoneinfo/$zone\" \"\$ROOTFS/etc/localtime\"")
                appendLine("else")
                appendLine("  echo \"[locale] \${ROOTFS} has no zoneinfo/$zone\" >&2")
                appendLine("fi")
            }
        }
        val result = method.runOutside(script) { log("locale: $it") }
        if (!result.ok) {
            // Unlike the package steps, nothing downstream depends on
            // this having succeeded: a container with the wrong clock
            // still runs DSH, and turning a cosmetic failure into a
            // failed install would be the worse trade.
            Log.w(TAG, "locale/timezone configure failed (exit=${result.exitCode})")
            log("[locale] configure failed (exit=${result.exitCode}); continuing")
        }
    }
}
