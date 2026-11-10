package io.github.mdrzn.cleanframetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BundleStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Memory : KeyValue {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> files.forEach { (name, body) -> z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun good(version: Int, extra: Array<Pair<String, String>> = emptyArray()) =
        zip("index.html" to "<html>$version</html>", "bundle.json" to """{"version":$version,"minApk":3}""", "assets/a.txt" to "a", *extra)

    private fun stage(store: BundleStore, version: Int) = store.stage(version, ByteArrayInputStream(good(version)))

    private fun newStore(kv: KeyValue = Memory()) = BundleStore(File(tmp.root, "bundles"), kv)

    private fun rejected(bytes: ByteArray, version: Int = 5): Boolean {
        val store = newStore()
        return try { store.stage(version, ByteArrayInputStream(bytes)); false } catch (e: IOException) {
            assertEquals(0, store.pending)
            assertFalse(File(tmp.root, "bundles/$version").exists())
            assertFalse(File(tmp.root, "bundles/$version.tmp").exists())
            true
        }
    }

    @Test fun extractsAGoodBundle() {
        val store = newStore()
        stage(store, 5)
        assertEquals(5, store.pending)
        assertEquals("<html>5</html>", File(store.dir(5), "index.html").readText())
        assertEquals("a", File(store.dir(5), "assets/a.txt").readText())
    }

    @Test fun refusesZipSlipAndOtherUnsafePaths() {
        assertTrue(rejected(good(5, arrayOf("../evil.js" to "x"))))
        assertTrue(rejected(good(5, arrayOf("a/../../evil.js" to "x"))))
        assertTrue(rejected(good(5, arrayOf("/abs.js" to "x"))))
        assertTrue(rejected(good(5, arrayOf("a\\b.js" to "x"))))
        assertTrue(rejected(good(5, arrayOf("C:evil.js" to "x"))))
        assertFalse(File(tmp.root, "evil.js").exists())
    }

    @Test fun refusesIncompleteOrMismatchedBundles() {
        assertTrue(rejected(zip("bundle.json" to """{"version":5,"minApk":3}""")))
        assertTrue(rejected(zip("index.html" to "x")))
        assertTrue(rejected(zip("index.html" to "x", "bundle.json" to """{"version":6,"minApk":3}""")))
        assertTrue(rejected(zip("index.html" to "x", "bundle.json" to "nope")))
        assertTrue(rejected("not a zip".toByteArray()))
    }

    @Test fun refusesTooManyFilesAndTooMuchData() {
        val many = Array(BundleStore.MAX_ENTRIES + 1) { "f/$it.txt" to "x" }
        assertTrue(rejected(good(5, many)))
        val big = "x".repeat(1024 * 1024)
        val files = Array(101) { "big/$it.bin" to big }
        assertTrue(rejected(good(5, files)))
    }

    @Test fun aBadBundleCannotBeStagedAgain() {
        val store = newStore()
        stage(store, 5)
        store.markBad(5)
        try { stage(store, 5); fail() } catch (e: IOException) { }
        assertEquals(setOf(5), store.bad)
    }

    @Test fun pendingBecomesActiveWhenReadyAndKeepsThePreviousOne() {
        val store = newStore()
        stage(store, 1)
        assertEquals(1, store.beginLaunch())
        assertTrue(store.markReady(1))
        assertEquals(1, store.active); assertEquals(0, store.pending)
        stage(store, 2)
        assertEquals(2, store.beginLaunch())
        assertTrue(store.markReady(2))
        assertEquals(2, store.active); assertEquals(1, store.previous)
        stage(store, 3); store.beginLaunch(); store.markReady(3)
        assertEquals(3, store.active); assertEquals(2, store.previous)
        assertFalse("older bundles are pruned", store.dir(1).exists())
        assertTrue(store.dir(2).isDirectory && store.dir(3).isDirectory)
    }

    @Test fun readyFromAnythingButThePendingBundleIsIgnored() {
        val store = newStore()
        assertFalse(store.markReady(0))
        stage(store, 4)
        assertFalse(store.markReady(3))
        assertEquals(4, store.pending); assertEquals(0, store.active)
    }

    @Test fun aPendingBundleThatNeverReportsReadyIsDroppedAfterTwoLaunches() {
        val store = newStore()
        stage(store, 7)
        assertEquals(7, store.beginLaunch())
        assertEquals(7, store.beginLaunch())
        assertEquals("third launch: crash loop, so back to the built-in interface", 0, store.beginLaunch())
        assertEquals(setOf(7), store.bad)
        assertEquals(0, store.pending)
        assertFalse(store.dir(7).exists())
    }

    @Test fun aFailedPendingBundleFallsBackToTheActiveOne() {
        val store = newStore()
        stage(store, 1); store.beginLaunch(); store.markReady(1)
        stage(store, 2)
        assertEquals(2, store.beginLaunch())
        store.markBad(2)
        assertEquals(1, store.beginLaunch())
        assertEquals(1, store.active)
        assertEquals(setOf(2), store.bad)
    }

    @Test fun anActiveBundleMarkedBadRollsBackToThePreviousOne() {
        val store = newStore()
        stage(store, 1); store.beginLaunch(); store.markReady(1)
        stage(store, 2); store.beginLaunch(); store.markReady(2)
        store.markBad(2)
        assertEquals(1, store.active)
        assertEquals(1, store.beginLaunch())
        store.markBad(1)
        assertEquals(0, store.beginLaunch())
    }

    @Test fun aMissingDirectoryFallsBackInsteadOfServingNothing() {
        val store = newStore()
        stage(store, 1); store.beginLaunch(); store.markReady(1)
        store.dir(1).deleteRecursively()
        assertEquals(0, store.beginLaunch())
        assertTrue(1 in store.bad)
    }

    @Test fun stateSurvivesARestart() {
        val kv = Memory()
        val first = newStore(kv)
        stage(first, 1); first.beginLaunch(); first.markReady(1)
        stage(first, 2)
        val second = newStore(kv)
        assertEquals(1, second.active); assertEquals(2, second.pending); assertEquals(2, second.newestKnown)
    }

    @Test fun stagingRemovesHalfWrittenLeftovers() {
        val store = newStore()
        File(tmp.root, "bundles/9.tmp").mkdirs()
        stage(store, 1)
        assertFalse(File(tmp.root, "bundles/9.tmp").exists())
    }

    @Test fun resolveStaysInsideTheBundle() {
        val store = newStore()
        stage(store, 1)
        File(tmp.root, "secret.txt").writeText("s")
        val dir = store.dir(1)
        assertNotNull(BundleStore.resolve(dir, "index.html"))
        assertNotNull(BundleStore.resolve(dir, "assets/a.txt"))
        assertNull(BundleStore.resolve(dir, "nothing.js"))
        assertNull(BundleStore.resolve(dir, "../../secret.txt"))
        assertNull(BundleStore.resolve(dir, "assets/../../1.tmp"))
        assertNull(BundleStore.resolve(dir, ""))
        assertNull("a directory is not a file", BundleStore.resolve(dir, "assets"))
    }
}
