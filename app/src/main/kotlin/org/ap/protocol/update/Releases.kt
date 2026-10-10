package org.ap.protocol.update

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** Where releases, issues and the source live. Releases must be public for the update check to see them. */
object Links {
    const val REPO_SLUG = "The3AMOrg/anti-proxy"
    const val REPO = "https://github.com/$REPO_SLUG"
    const val RELEASES = "$REPO/releases"
    const val ISSUES_NEW = "$REPO/issues/new"
    const val ORG = "https://github.com/The3AMOrg"
    const val ORG_AVATAR = "https://github.com/The3AMOrg.png"
    private const val API = "https://api.github.com/repos/$REPO_SLUG/releases"
    const val API_LATEST = "$API/latest"
    fun apiPage(page: Int, perPage: Int) = "$API?page=$page&per_page=$perPage"
}

data class ApkAsset(val name: String, val downloadUrl: String, val architecture: String)

data class Release(
    val tagName: String,
    val title: String,
    val notes: String,
    val publishedAt: String,
    val htmlUrl: String,
    val prerelease: Boolean,
    val assets: List<ApkAsset>
)

sealed interface UpdateResult {
    data class Success(val release: Release, val isNewer: Boolean) : UpdateResult
    data object NoReleases : UpdateResult
    data object NetworkError : UpdateResult
    data object Timeout : UpdateResult
    data object UnknownError : UpdateResult
}

/** Reads releases from the GitHub REST API (no token: public repos only, 60 requests/hour per IP). */
object GitHubReleases {

    suspend fun check(currentVersion: String, includePrerelease: Boolean): UpdateResult = try {
        val latest = if (includePrerelease) {
            parseReleases(get(Links.apiPage(1, 10))).firstOrNull()
        } else {
            getOrNull(Links.API_LATEST)?.let { parseRelease(JSONObject(it)) }
        }
        if (latest == null) UpdateResult.NoReleases
        else UpdateResult.Success(latest, compareVersions(latest.tagName, currentVersion) > 0)
    } catch (_: SocketTimeoutException) {
        UpdateResult.Timeout
    } catch (_: IOException) {
        UpdateResult.NetworkError
    } catch (_: Exception) {
        UpdateResult.UnknownError
    }

    /** Every release, newest first. Up to 5 pages of 30, which is years of releases. */
    suspend fun all(): List<Release> {
        val releases = mutableListOf<Release>()
        for (page in 1..5) {
            val batch = parseReleases(get(Links.apiPage(page, 30)))
            releases += batch
            if (batch.size < 30) break
        }
        return releases
    }

    /** Null on 404: GitHub's "latest" endpoint has nothing until the first non-pre-release exists. */
    private suspend fun getOrNull(url: String): String? = withContext(Dispatchers.IO) {
        val conn = open(url)
        try {
            when (conn.responseCode) {
                HttpURLConnection.HTTP_OK -> conn.inputStream.bufferedReader().use { it.readText() }
                HttpURLConnection.HTTP_NOT_FOUND -> null
                else -> throw IOException("GitHub returned ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun get(url: String): String = getOrNull(url) ?: "[]"

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "AntiProxy-Android")
    }
}

fun parseReleases(json: String): List<Release> {
    val array = JSONArray(json)
    return (0 until array.length()).map { parseRelease(array.getJSONObject(it)) }.filter { it.tagName.isNotEmpty() }
}

fun parseRelease(o: JSONObject): Release {
    val assets = o.optJSONArray("assets") ?: JSONArray()
    val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
        .filter { it.optString("name").endsWith(".apk") }
        .map { a ->
            val name = a.optString("name")
            ApkAsset(name, a.optString("browser_download_url"), architectureOf(name))
        }
    val tag = o.optString("tag_name")
    return Release(
        tagName = tag,
        title = o.optString("name").ifBlank { tag },
        notes = o.optString("body"),
        publishedAt = o.optString("published_at"),
        htmlUrl = o.optString("html_url"),
        prerelease = o.optBoolean("prerelease"),
        assets = apks
    )
}

private fun architectureOf(fileName: String) = listOf("arm64-v8a", "armeabi-v7a", "x86_64").firstOrNull { it in fileName } ?: "universal"

/** "v1.2.10" vs "1.2.9": numeric per part, ignores a leading v and anything after "-" (e.g. -debug, -beta1). */
fun compareVersions(a: String, b: String): Int {
    fun parts(v: String) = v.trim().removePrefix("v").substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
    val pa = parts(a)
    val pb = parts(b)
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val c = (pa.getOrNull(i) ?: 0).compareTo(pb.getOrNull(i) ?: 0)
        if (c != 0) return c
    }
    return 0
}

/** The phone's main ABI, used to pick its APK from a release. */
fun deviceAbi(): String {
    val abis = Build.SUPPORTED_ABIS.orEmpty()
    return listOf("arm64-v8a", "armeabi-v7a", "x86_64").firstOrNull { it in abis } ?: "universal"
}

/** Exact ABI match, then the universal APK, then whatever there is. */
fun bestApk(assets: List<ApkAsset>, abi: String = deviceAbi()): ApkAsset? =
    assets.firstOrNull { it.architecture == abi } ?: assets.firstOrNull { it.architecture == "universal" } ?: assets.firstOrNull()
