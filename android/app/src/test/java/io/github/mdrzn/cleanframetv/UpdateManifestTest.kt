package io.github.mdrzn.cleanframetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fixtures below were signed with throwaway keys (private halves deleted): a signature made by `scripts/release-update.mjs` or `openssl dgst -sha256 -sign`. */
class UpdateManifestTest {
    private val manifest = """{"sequence":12,"issued":"2026-11-10T12:00:00Z","bundle":{"version":12,"url":"https://raw.githubusercontent.com/masterde/cleanframe-tv/updates/bundle-12.zip","sha256":"1111111111111111111111111111111111111111111111111111111111111111","size":1234567,"minApk":2},"apk":{"versionCode":3,"versionName":"0.3.0","url":"https://raw.githubusercontent.com/masterde/cleanframe-tv/updates/CleanFrameTV-0.3.0.apk","sha256":"2222222222222222222222222222222222222222222222222222222222222222","size":17276829}}"""
    private val signature = "MEUCIAc2x5JKJE+BK7t1s5+3s2YDqaeSo69HOFsWRJ7vA2HfAiEA5FdAi/8co4pa13Bt1w09nGAmoZb6zO8z4VHdWI+czxY="
    private val key1 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEr2iaoprWJjX6a3eEoZ25BZ38QFgeQIYG1O0p9NSFIhw21LDdIcRRiU/inep/gmKzlYXaoAn6IXIAyOJ6XoDnzA=="
    private val key2 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE0cKv6OvIFvnzElGstgSgokTzKTTC/Fip+C90TzxQcPbFXm7BXefkiMnJOi+nM7pyKa7jIAaeyEsBsffYwPAGDg=="
    private val bytes = manifest.toByteArray()

    @Test fun acceptsTheRightSignature() = assertTrue(UpdateVerifier.verify(bytes, signature, key1))

    @Test fun rejectsAnotherKey() = assertFalse(UpdateVerifier.verify(bytes, signature, key2))

    @Test fun rejectsATamperedManifest() {
        assertFalse(UpdateVerifier.verify(manifest.replace("\"sequence\":12", "\"sequence\":13").toByteArray(), signature, key1))
        assertFalse(UpdateVerifier.verify(manifest.replace("2222", "3333").toByteArray(), signature, key1))
        assertFalse(UpdateVerifier.verify(bytes + " ".toByteArray(), signature, key1))
    }

    @Test fun rejectsGarbageSignaturesAndKeys() {
        assertFalse(UpdateVerifier.verify(bytes, "", key1))
        assertFalse(UpdateVerifier.verify(bytes, "not base64!", key1))
        assertFalse(UpdateVerifier.verify(bytes, signature, ""))
        assertFalse(UpdateVerifier.verify(bytes, signature, signature))
    }

    @Test fun parsesAVerifiedManifest() {
        val m = UpdateManifest.parse(bytes)!!
        assertEquals(12L, m.sequence)
        assertEquals(12, m.bundle!!.version)
        assertEquals(2, m.bundle!!.minApk)
        assertEquals("0.3.0", m.apk!!.versionName)
        assertEquals(17276829L, m.apk!!.size)
    }

    @Test fun parseRejectsMalformedManifests() {
        assertNull(UpdateManifest.parse("{}".toByteArray()))
        assertNull(UpdateManifest.parse("not json".toByteArray()))
        assertNull(UpdateManifest.parse(manifest.replace("\"sequence\":12", "\"sequence\":0").toByteArray()))
        assertNull(UpdateManifest.parse(manifest.replace("1111111111111111111111111111111111111111111111111111111111111111", "xyz").toByteArray()))
        assertNull(UpdateManifest.parse(manifest.replace("\"size\":1234567", "\"size\":0").toByteArray()))
        assertNull(UpdateManifest.parse(ByteArray(UpdateManifest.MAX_BYTES + 1) { ' '.code.toByte() }))
    }

    @Test fun bundleOrApkMayBeAbsent() {
        val onlyApk = """{"sequence":1,"apk":{"versionCode":3,"versionName":"0.3.0","url":"https://h/a","sha256":"${"a".repeat(64)}","size":5}}"""
        val m = UpdateManifest.parse(onlyApk.toByteArray())!!
        assertNull(m.bundle); assertNotNull(m.apk)
    }

    @Test fun onlyHttpsOnTheConfiguredHost() {
        val host = "raw.githubusercontent.com"
        assertTrue(UpdateVerifier.allowedUrl("https://raw.githubusercontent.com/masterde/cleanframe-tv/updates/manifest.json", host))
        assertFalse(UpdateVerifier.allowedUrl("http://raw.githubusercontent.com/x", host))
        assertFalse(UpdateVerifier.allowedUrl("https://evil.example.com/x", host))
        assertFalse(UpdateVerifier.allowedUrl("https://raw.githubusercontent.com.evil.example/x", host))
        assertFalse(UpdateVerifier.allowedUrl("https://user@raw.githubusercontent.com/x", host))
        assertFalse(UpdateVerifier.allowedUrl("https://raw.githubusercontent.com:8443/x", host))
        assertFalse(UpdateVerifier.allowedUrl("file:///sdcard/x", host))
        assertFalse(UpdateVerifier.allowedUrl("", host))
    }

    private fun decide(installedApk: Int = 2, installedBundle: Int = 11, last: Long = 11, bad: Set<Int> = emptySet()) =
        UpdateDecision.decide(UpdateManifest.parse(bytes)!!, installedApk, installedBundle, last, bad)

    @Test fun offersNewerBundleAndApk() {
        val r = decide()
        assertEquals(12, r.bundle!!.version); assertEquals(3, r.apk!!.versionCode); assertEquals("update", r.reason)
    }

    @Test fun refusesReplayAndDowngrade() {
        assertEquals("stale", decide(last = 12).reason)
        assertEquals("stale", decide(last = 40).reason)
        assertNull(decide(last = 12).bundle)
    }

    @Test fun holdsABundleThatNeedsANewerApk() {
        val r = decide(installedApk = 1)
        assertNull(r.bundle); assertEquals(3, r.apk!!.versionCode)
    }

    @Test fun offersNothingWhenCurrentOrBad() {
        assertEquals("current", decide(installedApk = 3, installedBundle = 12).reason)
        assertNull(decide(bad = setOf(12)).bundle)
        assertNull(decide(installedApk = 3).apk)
    }
}
