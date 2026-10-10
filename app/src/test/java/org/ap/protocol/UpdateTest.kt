package org.ap.protocol

import org.ap.protocol.update.ApkAsset
import org.ap.protocol.update.bestApk
import org.ap.protocol.update.compareVersions
import org.ap.protocol.update.parseReleases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {

    @Test
    fun comparesVersionsNumericallyPerPart() {
        assertTrue(compareVersions("v1.2.10", "1.2.9") > 0) // not string order
        assertTrue(compareVersions("v1.0.1", "1.0.0") > 0)
        assertTrue(compareVersions("v1.0.0", "1.1") < 0)
        assertEquals(0, compareVersions("v1.0", "1.0.0")) // missing parts are 0
        assertEquals(0, compareVersions("v1.0.0-beta2", "1.0.0-debug")) // suffixes ignored
        assertTrue(compareVersions("v2.0.0", "1.9.9") > 0)
    }

    private val arm64 = ApkAsset("AntiProxy-v1.1.0-arm64-v8a.apk", "u1", "arm64-v8a")
    private val universal = ApkAsset("AntiProxy-v1.1.0-universal.apk", "u2", "universal")
    private val x86 = ApkAsset("AntiProxy-v1.1.0-x86_64.apk", "u3", "x86_64")

    @Test
    fun picksThePhonesApkThenUniversal() {
        assertEquals(arm64, bestApk(listOf(universal, arm64), abi = "arm64-v8a"))
        assertEquals(universal, bestApk(listOf(x86, universal), abi = "arm64-v8a"))
        assertEquals(x86, bestApk(listOf(x86), abi = "arm64-v8a"))
        assertNull(bestApk(emptyList(), abi = "arm64-v8a"))
    }

    @Test
    fun parsesGitHubReleasesKeepingOnlyApks() {
        val json = """
            [{"tag_name":"v1.1.0","name":"","body":"- Faster check-in","published_at":"2026-10-11T08:00:00Z",
              "html_url":"https://github.com/The3AMOrg/anti-proxy/releases/tag/v1.1.0","prerelease":true,
              "assets":[{"name":"AntiProxy-v1.1.0-universal.apk","browser_download_url":"https://x/a.apk"},
                        {"name":"checksums.txt","browser_download_url":"https://x/c.txt"}]},
             {"tag_name":"","name":"draft without a tag"}]
        """
        val releases = parseReleases(json)
        assertEquals(1, releases.size) // the untagged entry is dropped
        val r = releases.single()
        assertEquals("v1.1.0", r.title) // blank name falls back to the tag
        assertTrue(r.prerelease)
        assertEquals(listOf(ApkAsset("AntiProxy-v1.1.0-universal.apk", "https://x/a.apk", "universal")), r.assets)
    }
}
