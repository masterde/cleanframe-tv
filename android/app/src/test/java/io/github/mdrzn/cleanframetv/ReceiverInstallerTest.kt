package io.github.mdrzn.cleanframetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverInstallerTest {
    private val base = "https://github.com/jqssun/android-airplay-server/releases/download/v0.0.37/app-release.apk"
    private val sha = "b8c7c595ed93f91562feedce92c27800f08f9c84df424f6ac2045e82324cf2a4"

    private fun release(tag: String = "v0.0.37", name: String = "app-release.apk", url: String = base, size: Long = 27335496, digest: String = "sha256:$sha", extra: String = "") =
        """{"tag_name":"$tag"$extra,"assets":[{"name":"app-release.aab","size":1,"browser_download_url":"$url.aab"},{"name":"$name","size":$size,"browser_download_url":"$url","digest":"$digest"}]}"""

    @Test fun readsTheApkAssetOfTheLatestRelease() {
        val r = ReceiverInstaller.parseLatest(release())!!
        assertEquals("0.0.37", r.version)
        assertEquals(base, r.url)
        assertEquals(27335496L, r.size)
        assertEquals(sha, r.sha256)
    }

    @Test fun aMissingDigestIsAllowedButABadOneIsIgnored() {
        assertNull(ReceiverInstaller.parseLatest(release(digest = ""))!!.sha256)
        assertNull(ReceiverInstaller.parseLatest(release(digest = "sha256:nothex"))!!.sha256)
    }

    @Test fun rejectsReleasesThatAreNotUsable() {
        assertNull("no apk asset", ReceiverInstaller.parseLatest(release(name = "other.apk")))
        assertNull("draft", ReceiverInstaller.parseLatest(release(extra = ""","draft":true""")))
        assertNull("prerelease", ReceiverInstaller.parseLatest(release(extra = ""","prerelease":true""")))
        assertNull("odd tag", ReceiverInstaller.parseLatest(release(tag = "v1.0-evil; rm")))
        assertNull("other repository", ReceiverInstaller.parseLatest(release(url = "https://github.com/someone/else/releases/download/v1/app-release.apk")))
        assertNull("other host", ReceiverInstaller.parseLatest(release(url = "https://evil.example/jqssun/android-airplay-server/releases/download/v1/app-release.apk")))
        assertNull("too big", ReceiverInstaller.parseLatest(release(size = ReceiverInstaller.MAX_APK + 1)))
        assertNull("empty", ReceiverInstaller.parseLatest(release(size = 0)))
        assertNull("not json", ReceiverInstaller.parseLatest("nope"))
        assertNull("no assets", ReceiverInstaller.parseLatest("""{"tag_name":"v1"}"""))
    }

    @Test fun comparesVersionsNumerically() {
        assertTrue(ReceiverInstaller.isNewer("0.0.37", "0.0.36"))
        assertTrue(ReceiverInstaller.isNewer("0.1", "0.0.99"))
        assertTrue(ReceiverInstaller.isNewer("1.0.0", "0.9.9"))
        assertTrue(ReceiverInstaller.isNewer("v0.0.10", "0.0.9"))
        assertFalse(ReceiverInstaller.isNewer("0.0.36", "0.0.36"))
        assertFalse(ReceiverInstaller.isNewer("0.0.35", "0.0.36"))
        assertFalse(ReceiverInstaller.isNewer("1.0", "1.0.0"))
    }

    @Test fun onlyAllowsHttpsOnGitHubHosts() {
        assertTrue(ReceiverInstaller.allowedUrl("https://api.github.com/repos/jqssun/android-airplay-server/releases/latest"))
        assertTrue(ReceiverInstaller.allowedUrl(base))
        assertTrue(ReceiverInstaller.allowedUrl("https://release-assets.githubusercontent.com/github-production-release-asset/1/2?x=y"))
        assertTrue(ReceiverInstaller.allowedUrl("https://objects.githubusercontent.com/a"))
        assertFalse(ReceiverInstaller.allowedUrl("http://github.com/a"))
        assertFalse(ReceiverInstaller.allowedUrl("https://evil.example/a"))
        assertFalse(ReceiverInstaller.allowedUrl("https://github.com.evil.example/a"))
        assertFalse(ReceiverInstaller.allowedUrl("https://evilgithubusercontent.com/a"))
        assertFalse(ReceiverInstaller.allowedUrl("https://user:pw@github.com/a"))
        assertFalse(ReceiverInstaller.allowedUrl("https://github.com:8443/a"))
        assertFalse(ReceiverInstaller.allowedUrl("file:///sdcard/a.apk"))
        assertFalse(ReceiverInstaller.allowedUrl("not a url"))
    }

    @Test fun acceptsOnlyTheSingleKeyThatIsPinned() {
        val pinned = ReceiverInstaller.PINNED_SIGNER
        assertTrue(ReceiverInstaller.signerAccepted(listOf(pinned)))
        assertTrue(ReceiverInstaller.signerAccepted(listOf(pinned.uppercase())))
        assertFalse(ReceiverInstaller.signerAccepted(listOf("0".repeat(64))))
        assertFalse("a second signer is refused", ReceiverInstaller.signerAccepted(listOf(pinned, "0".repeat(64))))
        assertFalse(ReceiverInstaller.signerAccepted(emptyList()))
    }

    @Test fun hashesCertificatesLikeApksigner() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ReceiverInstaller.sha256Hex("abc".toByteArray()))
        assertNotNull(ReceiverInstaller.PINNED_SIGNER)
        assertEquals(64, ReceiverInstaller.PINNED_SIGNER.length)
    }
}
