package me.phie.tawc.install.distro.debian

import me.phie.tawc.install.BootstrapFormat
import me.phie.tawc.install.BootstrapVerification
import me.phie.tawc.install.Http
import me.phie.tawc.install.MirrorProxy
import me.phie.tawc.install.distro.TarballBootstrap
import org.json.JSONObject
import java.io.IOException

/**
 * Resolve the debuerreotype `docker-debian-artifacts` rootfs for a
 * suite/arch: pin the `dist-<arch>` branch tip to a commit SHA via the
 * GitHub API, then read the OCI `image-manifest.json` at that commit
 * and return the `rootfs.tar.gz` URL plus its layer SHA-256.
 *
 * Trust boundary: the digest and the tarball come from the **same
 * origin** (raw.githubusercontent.com, same repo). The SHA-256 check
 * catches mid-download corruption and redirect-to-a-different-host; it
 * is not a barrier against a compromised debuerreotype org or GitHub
 * itself, which could serve a matching tarball/digest pair. Debian
 * publishes no out-of-band signature for these artifacts. Commit-
 * pinning closes the mutable-branch race (a force-push between our
 * manifest fetch and tarball fetch, or a tampered tip serving mixed
 * states) but does not change who we trust. Once extracted, apt
 * verifies every package against the debian-archive-keyring shipped
 * in the bootstrap, so the exposure is the bootstrap itself. See
 * notes/installation.md "Bootstrap integrity".
 */
internal object DebianDockerResolver {
    private const val OWNER_REPO = "debuerreotype/docker-debian-artifacts"
    private const val RAW_BASE = "https://raw.githubusercontent.com/$OWNER_REPO"

    fun resolve(
        suite: String,
        bashbrewArch: String,
        mirrorProxy: MirrorProxy?,
    ): TarballBootstrap {
        val branch = "dist-$bashbrewArch"
        // Pin the branch tip to an immutable commit so the manifest and
        // the tarball are guaranteed to come from the same tree state.
        val commitUrl = "https://api.github.com/repos/$OWNER_REPO/commits/$branch"
        val sha = JSONObject(
            downloadText(mirrorProxy?.wrap(commitUrl) ?: commitUrl, githubApi = true),
        ).optString("sha").lowercase()
        if (sha.length != 40 || !sha.all { it.isDigit() || it in 'a'..'f' }) {
            throw IOException("GitHub commit lookup for $OWNER_REPO@$branch returned no usable sha ('$sha')")
        }

        val base = "$RAW_BASE/$sha/$suite/oci/blobs"
        val manifestUrl = "$base/image-manifest.json"
        val manifest = JSONObject(downloadText(mirrorProxy?.wrap(manifestUrl) ?: manifestUrl))
        val layers = manifest.getJSONArray("layers")
        if (layers.length() != 1) {
            throw IOException("Debian $suite $bashbrewArch manifest has ${layers.length()} layers, expected 1")
        }
        val layer = layers.getJSONObject(0)
        val mediaType = layer.getString("mediaType")
        if (mediaType != "application/vnd.oci.image.layer.v1.tar+gzip") {
            throw IOException("Debian $suite $bashbrewArch layer has unsupported mediaType $mediaType")
        }
        val digest = layer.getString("digest").removePrefix("sha256:")
        return TarballBootstrap(
            url = "$base/rootfs.tar.gz",
            format = BootstrapFormat.GZIP,
            stripPrefix = null,
            verification = BootstrapVerification.Sha256(digest),
        )
    }

    private fun downloadText(url: String, githubApi: Boolean = false): String {
        val conn = Http.open(url).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (githubApi) {
                // Pin to a known API version so a GitHub-side schema
                // change can't surprise us (same as GitHubReleaseResolver).
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "tawc-installer")
            }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("GET $url returned HTTP $code")
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
