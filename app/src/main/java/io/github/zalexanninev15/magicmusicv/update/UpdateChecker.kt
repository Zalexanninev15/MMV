package io.github.zalexanninev15.magicmusicv.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    const val REPO_URL = "https://github.com/Zalexanninev15/MMV"
    const val MASTODON_URL = "https://mastodon.ml/@voltmor"
    const val DONATE_URL = "https://z15.neocities.org/donate/pay"

    private const val LATEST = "https://api.github.com/repos/Zalexanninev15/MMV/releases/latest"
    private const val ALL = "https://api.github.com/repos/Zalexanninev15/MMV/releases?per_page=20"

    data class Result(
        val tag: String? = null,
        val url: String? = null,
        val newer: Boolean = false,
        val error: String? = null,
    )

    suspend fun check(currentVersion: String): Result = withContext(Dispatchers.IO) {
        val body = fetch(LATEST) ?: fetch(ALL)
        ?: return@withContext Result(error = "Could not reach GitHub")

        val release = runCatching {
            if (body.trimStart().startsWith("[")) {
                val arr = JSONArray(body)
                (0 until arr.length())
                    .map { arr.getJSONObject(it) }
                    .firstOrNull { isReleaseTag(it.optString("tag_name")) }
            } else {
                JSONObject(body).takeIf { isReleaseTag(it.optString("tag_name")) }
            }
        }.getOrNull() ?: return@withContext Result(error = "No published releases yet")

        val tag = release.optString("tag_name").takeIf { it.isNotBlank() }
            ?: return@withContext Result(error = "No releases published yet")
        val url = release.optString("html_url").takeIf { it.isNotBlank() } ?: REPO_URL

        Result(tag = tag, url = url, newer = isNewer(tag, currentVersion))
    }

    private fun isReleaseTag(tag: String?): Boolean =
        tag != null && tag.length >= 2 && tag[0] == 'v' && tag[1].isDigit()

    private fun fetch(url: String): String? = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36")
        }
        try {
            if (c.responseCode !in 200..299) return null
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }.getOrNull()

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