package io.github.mdrzn.cleanframetv

import org.json.JSONObject
import java.net.URL
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The signed description of the latest release (see docs/plans/2026-11-10-auto-update-design.md).
 * A manifest is only parsed after [UpdateVerifier.verify] accepted its exact bytes, and every URL must pass [UpdateVerifier.allowedUrl].
 */
data class UpdateManifest(val sequence: Long, val issued: String, val bundle: Bundle?, val apk: Apk?) {
    data class Bundle(val version: Int, val url: String, val sha256: String, val size: Long, val minApk: Int)
    data class Apk(val versionCode: Int, val versionName: String, val url: String, val sha256: String, val size: Long)

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
        const val MAX_BYTES = 64 * 1024

        /** Returns null for anything malformed: missing fields, bad hashes, non-positive sizes or versions. */
        fun parse(bytes: ByteArray): UpdateManifest? {
            if (bytes.size > MAX_BYTES) return null
            return try {
                val o = JSONObject(String(bytes, Charsets.UTF_8))
                val sequence = o.getLong("sequence").takeIf { it > 0 } ?: return null
                val bundle = o.optJSONObject("bundle")?.let {
                    Bundle(it.getInt("version"), it.getString("url"), it.getString("sha256"), it.getLong("size"), it.getInt("minApk"))
                        .takeIf { b -> b.version > 0 && b.minApk > 0 && b.size > 0 && HEX64.matches(b.sha256) } ?: return null
                }
                val apk = o.optJSONObject("apk")?.let {
                    Apk(it.getInt("versionCode"), it.getString("versionName"), it.getString("url"), it.getString("sha256"), it.getLong("size"))
                        .takeIf { a -> a.versionCode > 0 && a.versionName.isNotBlank() && a.size > 0 && HEX64.matches(a.sha256) } ?: return null
                }
                UpdateManifest(sequence, o.optString("issued"), bundle, apk)
            } catch (e: Exception) { null }
        }
    }
}

object UpdateVerifier {
    /** ECDSA P-256 / SHA-256 over the manifest's exact bytes. [publicKeyB64] is the X.509 SubjectPublicKeyInfo (DER) in base64; [signatureB64] is the DER signature in base64. */
    fun verify(manifest: ByteArray, signatureB64: String, publicKeyB64: String): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64.trim())))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(manifest)
            verify(Base64.getDecoder().decode(signatureB64.trim()))
        }
    } catch (e: Exception) { false }

    /** HTTPS only, and only the one host this build was configured for. Redirects are refused elsewhere (the download code does not follow them). */
    fun allowedUrl(url: String, host: String): Boolean = try {
        val u = URL(url)
        u.protocol == "https" && u.host == host && u.userInfo == null && (u.port == -1 || u.port == 443)
    } catch (e: Exception) { false }
}

/** What to do with a verified manifest, given what is installed and the last sequence number accepted. */
object UpdateDecision {
    data class Result(val bundle: UpdateManifest.Bundle?, val apk: UpdateManifest.Apk?, val reason: String)

    /**
     * Replays and downgrades ([UpdateManifest.sequence] not above [lastSequence]) offer nothing. A bundle is offered only when newer than
     * [installedBundle] and its minApk is met by [installedApk]; an APK only when its versionCode is higher than [installedApk].
     * A bundle that needs a newer APK is held back so the APK is offered first.
     */
    fun decide(m: UpdateManifest, installedApk: Int, installedBundle: Int, lastSequence: Long, badBundles: Set<Int> = emptySet()): Result {
        if (m.sequence <= lastSequence) return Result(null, null, "stale")
        val apk = m.apk?.takeIf { it.versionCode > installedApk }
        val bundle = m.bundle?.takeIf { it.version > installedBundle && it.version !in badBundles && it.minApk <= installedApk }
        return Result(bundle, apk, if (bundle == null && apk == null) "current" else "update")
    }
}
