package me.phie.tawc.install

import me.phie.tawc.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/**
 * The one place this app decides how it identifies itself over HTTP.
 *
 * Android's [HttpURLConnection] defaults to
 * `User-Agent: Dalvik/2.x (Linux; U; Android …)`, and mirrors reject it:
 * Aliyun answers 403 to the Dalvik UA and to no UA at all, while any
 * ordinary one gets 200. That is invisible while every request goes to
 * the upstream origins (which don't care) and fatal the moment a user
 * picks a mirror — so every request that can reach a user-supplied URL
 * goes through here rather than inheriting the platform default.
 *
 * [HttpTest] pins both halves of that: that this sends an ordinary UA,
 * and that no other file opens a connection of its own. Missing one call
 * site is not a cosmetic bug — it's an 800 MB download followed by a
 * verification failure, because the bootstrap's tarball and its detached
 * signature are fetched by different classes.
 */
internal object Http {
    private val USER_AGENT = "tawc/" + BuildConfig.VERSION_NAME

    fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", USER_AGENT)
        }
}
