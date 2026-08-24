package io.github.zalexanninev15.magicmusicv.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer build.
 *
 * `HttpURLConnection` rather than an HTTP client dependency: two GET requests against a
 * JSON API do not justify pulling OkHttp and its transitive tree into an app whose APK is
 * otherwise tiny.
 */
object UpdateChecker {

    const val REPO_URL = "https://github.com/Zalexanninev15/MMV"
    const val MASTODON_URL = "https://mastodon.ml/@voltmor"

    private const val LATEST = "https://api.github.com/repos/Zalexanninev15/MMV/releases/latest"
    private const val ALL = "https://api.github.com/repos/Zalexanninev15/MMV/releases?per_page=1"

    data class Result(
        val tag: String? = null,
        val url: String? = null,
        val newer: Boolean = false,
        val error: String? = null,
    )

    suspend fun check(currentVersion: String): Result = withContext(Dispatchers.IO) {
        // /releases/latest ignores pre-releases and 404s when every release is one, so fall
        // back to the full list and take the newest entry.
        val body = fetch(LATEST) ?: fetch(ALL)
        ?: return@withContext Result(error = "Could not reach GitHub")

        val release = runCatching {
            if (body.trimStart().startsWith("[")) {
                val arr = JSONArray(body)
                if (arr.length() == 0) null else arr.getJSONObject(0)
            } else {
                JSONObject(body)
            }
        }.getOrNull() ?: return@withContext Result(error = "Unexpected response from GitHub")

        val tag = release.optString("tag_name").takeIf { it.isNotBlank() }
            ?: return@withContext Result(error = "No releases published yet")
        val url = release.optString("html_url").takeIf { it.isNotBlank() } ?: REPO_URL

        Result(tag = tag, url = url, newer = isNewer(tag, currentVersion))
    }

    private fun fetch(url: String): String? = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub rejects requests without one.
            setRequestProperty("User-Agent", "MagicMusicV")
        }
        try {
            if (c.responseCode !in 200..299) return null
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    /** Tags are `v{version}`, so the leading v is stripped before comparing. */
    internal fun isNewer(tag: String, current: String): Boolean {
        val a = parts(tag)
        val b = parts(current)
        if (a.isEmpty() || b.isEmpty()) return tag.trimStart('v', 'V') != current
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parts(v: String): List<Int> = v.trim().trimStart('v', 'V')
        .split('.', '-', '+')
        .mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }
}
