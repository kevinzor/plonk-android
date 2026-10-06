package land.plonk.app.bundle

import android.content.res.AssetManager
import android.util.Log
import org.json.JSONObject
import java.io.IOException

/**
 * The game files packed into this APK under `assets/www/`, as listed in
 * `assets/bundled-manifest.json` (written by tools/fetch-bundle.mjs).
 *
 * The hashes here are of the exact bytes in the APK, recorded at build time, so the app never
 * has to hash anything while the game is loading: deciding whether a bundled file is still
 * current is one string comparison against the live manifest.
 *
 * A build without a bundle (the default for a plain checkout) has an empty index, and then the
 * app streams every file from the server exactly as before.
 */
internal class BundleIndex private constructor(
    /** The server build the bundle was taken from, if the manifest said. */
    val build: String?,
    private val files: Map<String, Entry>,
) {
    class Entry(
        val size: Long,
        val sha256: String,
    )

    val isEmpty: Boolean get() = files.isEmpty()
    val size: Int get() = files.size
    val keys: Set<String> get() = files.keys

    operator fun get(key: String): Entry? = files[key]

    /** Asset path for a manifest key, e.g. `/js/main.js` -> `www/js/main.js`. */
    fun assetPath(key: String): String = ROOT + key

    companion object {
        private const val TAG = "Plonk"
        private const val MANIFEST_ASSET = "bundled-manifest.json"
        private const val ROOT = "www"
        private val SHA256_HEX = Regex("[0-9a-f]{64}")

        val EMPTY = BundleIndex(null, emptyMap())

        /** Read the bundled manifest. Slow-ish (JSON of ~1000 entries): never on the UI thread. */
        fun load(assets: AssetManager): BundleIndex {
            val text =
                try {
                    assets.open(MANIFEST_ASSET).bufferedReader().use { it.readText() }
                } catch (_: IOException) {
                    return EMPTY // no bundle in this build
                }
            return try {
                val json = JSONObject(text)
                val files =
                    parseFiles(json.optJSONObject("files"))
                        .filterKeys { BundlePaths.keyFor(it) == it }
                BundleIndex(json.optString("build").ifBlank { null }, files)
            } catch (e: Exception) {
                Log.w(TAG, "Bundled manifest unreadable; streaming everything", e)
                EMPTY
            }
        }

        /**
         * Parse a manifest `files` object, `{ "/js/main.js": [size, "sha256hex"], ... }`, skipping
         * malformed entries rather than failing the whole manifest. Shared with the live manifest.
         */
        fun parseFiles(files: JSONObject?): Map<String, Entry> {
            if (files == null) return emptyMap()
            val out = HashMap<String, Entry>(files.length() * 2)
            for (key in files.keys()) {
                val pair = files.optJSONArray(key) ?: continue
                val size = pair.optLong(0, -1)
                val sha = pair.optString(1).lowercase()
                if (!key.startsWith("/") || size < 0 || !SHA256_HEX.matches(sha)) continue
                out[key] = Entry(size, sha)
            }
            return out
        }
    }
}
