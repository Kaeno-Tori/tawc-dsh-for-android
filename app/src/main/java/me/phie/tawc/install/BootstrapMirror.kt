package me.phie.tawc.install

import me.phie.tawc.install.distro.DistroBootstrap
import me.phie.tawc.install.distro.ImportedPack
import me.phie.tawc.install.distro.PackageBootstrap
import me.phie.tawc.install.distro.TarballBootstrap

/**
 * Points a distro's bootstrap download at a user-chosen mirror.
 *
 * Upstream hardcodes the bootstrap URL as a `private const` per distro
 * (`ArchLinuxArm.BOOTSTRAP_URL` and friends). That is fine for the
 * author's route and wrong for everyone else's: installing Arch Linux
 * ARM means an ~800 MB fetch from a single US mirror, and on a bad route
 * the install simply never finishes. Until now the only download knob in
 * the app was [MirrorProxy], which is a different thing entirely — a
 * dev-only caching reverse proxy using a `/<scheme>/<host>/<path>`
 * layout, whose URL is a compile-time constant and which release builds
 * reject outright.
 *
 * **The substitution is a prefix replacement, not an origin swap.** A
 * mirror re-roots upstream's tree under its own directory, so the part
 * of the URL *before* the upstream root has to be replaced wholesale.
 * Swapping only scheme+host would drop `/alarm` for ALARM and double
 * `/debian` for Debian — both silently produce a URL that 404s. Which is
 * why the upstream root is declared per distro
 * ([me.phie.tawc.install.distro.Distro.bootstrapMirrorPrefix]) instead
 * of being guessed.
 *
 * **Trust is unaffected, and that is the point of doing it here rather
 * than in the distro classes.** The bootstrap is verified against a key
 * shipped in the APK, and the PGP signature is fetched through the same
 * rewrite as the artifact it signs — so a mirror serving different bytes
 * fails closed at verification. This helper deliberately does not touch
 * a digest-verified distro's policy: that trust root is whatever
 * produced the digest, and mirroring it is only ever as good as the
 * mirror. See [BootstrapVerification]'s per-variant notes.
 */
internal object BootstrapMirror {

    /**
     * A mirror base as the user typed it: trimmed, without a trailing
     * slash. Empty means "no mirror set", which every entry point here
     * treats as "leave the distro alone".
     */
    fun normalise(base: String): String = base.trim().trimEnd('/')

    /**
     * Re-root [url] from [prefix] onto [base], or null when [url] does
     * not sit under [prefix].
     *
     * Null is a real answer, not an error: a distro with no
     * [me.phie.tawc.install.distro.Distro.bootstrapMirrorPrefix] (a
     * bootstrap resolved live from a GitHub release, say) has a URL that
     * no mirror can serve, and rewriting it anyway would produce a
     * plausible-looking URL pointing at nothing. Callers keep the
     * original on null.
     *
     * Exactly one `/` joins the two halves whatever the caller writes:
     * both Arch distros declare their prefix with a trailing slash (it
     * is a directory) while a user typing a mirror usually won't, and
     * `…/alarm//os/…` would be both visibly wrong and a 404 on mirrors
     * that don't collapse empty path segments.
     */
    fun rewrite(url: String, prefix: String, base: String): String? {
        // Compare without the prefix's trailing slash, then require the
        // boundary to be a real one. Plain `startsWith` would also match
        // `…archlinuxarm.org.evil.example/…` — irrelevant as a trust
        // boundary (the mirror is user-supplied) but it would silently
        // rewrite a URL that isn't under this root.
        val root = prefix.trimEnd('/')
        if (url != root && !url.startsWith("$root/")) return null
        val normalisedBase = normalise(base)
        // A base without a scheme cannot be a download URL. Ignoring the
        // setting keeps a half-typed value from making installs
        // impossible; the install form is where it gets validated.
        if (!normalisedBase.startsWith("http://") && !normalisedBase.startsWith("https://")) return null
        val remainder = url.removePrefix(root).trimStart('/')
        return if (remainder.isEmpty()) normalisedBase else "$normalisedBase/$remainder"
    }

    /**
     * [rewrite] applied to every URL in [bootstrap], or [bootstrap]
     * untouched when there is nothing to do.
     *
     * All-or-nothing: if any URL cannot be re-rooted the whole
     * descriptor is left alone, so a mirrored artifact can never end up
     * paired with an unmirrored signature (or vice versa) — a mismatch
     * that would surface as a confusing verification failure rather than
     * a clear one.
     */
    fun apply(bootstrap: DistroBootstrap, prefix: String?, base: String): DistroBootstrap {
        if (prefix == null || normalise(base).isEmpty()) return bootstrap
        return when (bootstrap) {
            is TarballBootstrap -> {
                val url = rewrite(bootstrap.url, prefix, base) ?: return bootstrap
                val verification = rewriteVerification(bootstrap.verification, prefix, base)
                    ?: return bootstrap
                bootstrap.copy(url = url, verification = verification)
            }

            is PackageBootstrap -> {
                // Debian's archive root: same re-rooting, but there is no
                // signature URL — the trust root is the clearsigned
                // InRelease fetched through archiveRoot itself.
                val root = rewrite(bootstrap.archiveRoot, prefix, base) ?: return bootstrap
                bootstrap.copy(archiveRoot = root)
            }

            // Nothing to re-root: an imported pack is already a local
            // file, and its "location" is the user's own storage. A
            // mirror setting has no meaning for it.
            is ImportedPack -> bootstrap
        }
    }

    /**
     * Rewrite the verification policy's URL, if it has one.
     *
     * Only [BootstrapVerification.Pgp] carries a location. A digest is a
     * value, so the `Sha256` and `ResolvedAtInstallTime` variants are
     * returned as-is — and note that this is also why a digest-verified
     * distro's security does not improve by being mirrored.
     */
    private fun rewriteVerification(
        verification: BootstrapVerification,
        prefix: String,
        base: String,
    ): BootstrapVerification? = when (verification) {
        is BootstrapVerification.Pgp -> rewrite(verification.signatureUrl, prefix, base)
            ?.let { verification.copy(signatureUrl = it) }

        else -> verification
    }
}
