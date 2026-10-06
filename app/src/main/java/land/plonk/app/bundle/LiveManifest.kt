package land.plonk.app.bundle

import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the live server is serving right now: `GET /app/manifest` (contract in docs/BUNDLE.md).
 *
 * The last good answer is kept on disk with its ETag, so a relaunch usually costs a ~200-byte
 * 304 instead of the full list, which matters on the weak signal this feature exists for.
 *
 * The cached copy is only ever used to answer a 304. It is never trusted on its own when the
 * server can't be reached: a deploy may have happened since, and serving old bundled code next
 * to a newer page from the network would mix two client versions.
 */
internal class LiveManifest(
    private val url: String,
    cacheDir: File,
    private val userAgent: String,
) {
    sealed interface Result {
        /** The server confirmed this list just now. Maps manifest key to sha256. */
        class Fresh(
            val build: String?,
            val hashes: Map<String, String>,
        ) : Result

        /** The server has no manifest endpoint (404): a server that predates bundling. */
        object Missing : Result

        /** No usable answer (offline, timeout, 5xx, bad JSON). */
        class Unreachable(
            val reason: String,
        ) : Result
    }

    private val bodyFile = AtomicFile(File(cacheDir, "live-manifest.json"))
    private val etagFile = AtomicFile(File(cacheDir, "live-manifest.etag"))

    /** Blocking network call. Run it on a background thread only. */
    fun fetch(): Result = fetch(conditional = true)

    private fun fetch(conditional: Boolean): Result {
        val conn =
            try {
                URL(url).openConnection() as HttpURLConnection
            } catch (e: IOException) {
                return Result.Unreachable(e.javaClass.simpleName)
            }
        return try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", userAgent)
            if (conditional) cachedEtag()?.let { conn.setRequestProperty("If-None-Match", it) }

            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    parse(body)?.also { save(body, conn.getHeaderField("ETag")) }
                        ?: Result.Unreachable("bad manifest")
                }
                HttpURLConnection.HTTP_NOT_MODIFIED ->
                    cachedBody()?.let(::parse) ?: if (conditional) retryInFull() else Result.Unreachable("HTTP 304")
                HttpURLConnection.HTTP_NOT_FOUND -> Result.Missing
                else -> Result.Unreachable("HTTP $code")
            }
        } catch (e: IOException) {
            Result.Unreachable(e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    /** A 304 whose cached body has gone missing or bad: forget the ETag and ask once more in full. */
    private fun retryInFull(): Result {
        etagFile.delete()
        return fetch(conditional = false)
    }

    private fun parse(body: String): Result.Fresh? =
        try {
            val json = JSONObject(body)
            if (json.optInt("v") != PROTOCOL_VERSION) {
                null
            } else {
                val hashes = BundleIndex.parseFiles(json.optJSONObject("files")).mapValues { it.value.sha256 }
                Result.Fresh(json.optString("build").ifBlank { null }, hashes)
            }
        } catch (_: Exception) {
            null
        }

    private fun cachedEtag(): String? = read(etagFile)?.takeIf { it.isNotBlank() && bodyFile.baseFile.exists() }

    private fun cachedBody(): String? = read(bodyFile)

    private fun save(
        body: String,
        etag: String?,
    ) {
        write(bodyFile, body)
        if (etag.isNullOrBlank()) etagFile.delete() else write(etagFile, etag)
    }

    private fun read(file: AtomicFile): String? =
        try {
            file.readFully().toString(Charsets.UTF_8)
        } catch (_: IOException) {
            null
        }

    private fun write(
        file: AtomicFile,
        text: String,
    ) {
        val out =
            try {
                file.startWrite()
            } catch (e: IOException) {
                Log.w(TAG, "Can't cache the live manifest", e)
                return
            }
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            Log.w(TAG, "Can't cache the live manifest", e)
        }
    }

    private companion object {
        const val TAG = "Plonk"
        const val PROTOCOL_VERSION = 1

        // Short on purpose: the game shell waits for this answer (see GameBundle.SHELL_WAIT_MS).
        const val CONNECT_TIMEOUT_MS = 2500
        const val READ_TIMEOUT_MS = 3000
    }
}
