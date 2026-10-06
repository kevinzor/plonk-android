package land.plonk.app.bundle

import android.content.res.AssetManager
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Turns one bundled file into the HTTP response the game would have got from the server.
 *
 * Headers match what the server sends for static files (`Cache-Control: no-cache`, exact
 * `Content-Length`, correct `Content-Type`) so the page can't tell the difference, except for
 * `X-Plonk-Bundle`, which shows up in the WebView devtools network panel as a debugging aid.
 * Single byte ranges are answered with 206, because media elements ask for ranges.
 */
internal object BundleResponse {
    /**
     * Build the response, or return null to let the request go to the network instead (the
     * asset is missing from the APK, or the request asks for something we don't do, like
     * multiple ranges).
     */
    fun build(
        assets: AssetManager,
        index: BundleIndex,
        key: String,
        rangeHeader: String?,
    ): WebResourceResponse? {
        val entry = index[key] ?: return null
        val mime = BundlePaths.mimeFor(key) ?: return null
        val size = entry.size
        val range =
            if (rangeHeader == null) {
                null
            } else {
                parseRange(rangeHeader, size) ?: return null
            }
        if (range == UNSATISFIABLE) {
            return WebResourceResponse(
                mime.type,
                mime.charset,
                416,
                "Range Not Satisfiable",
                baseHeaders(index) + ("Content-Range" to "bytes */$size") + ("Content-Length" to "0"),
                ByteArrayInputStream(ByteArray(0)),
            )
        }
        val stream =
            try {
                assets.open(index.assetPath(key), AssetManager.ACCESS_STREAMING)
            } catch (_: IOException) {
                return null
            }
        if (range == null) {
            return WebResourceResponse(
                mime.type,
                mime.charset,
                200,
                "OK",
                baseHeaders(index) + ("Content-Length" to size.toString()),
                stream,
            )
        }
        val length = range.last - range.first + 1
        val body =
            try {
                skipFully(stream, range.first)
                LimitedStream(stream, length)
            } catch (_: IOException) {
                stream.close()
                return null
            }
        return WebResourceResponse(
            mime.type,
            mime.charset,
            206,
            "Partial Content",
            baseHeaders(index) +
                ("Content-Range" to "bytes ${range.first}-${range.last}/$size") +
                ("Content-Length" to length.toString()),
            body,
        )
    }

    private val UNSATISFIABLE = LongRange(-1, -1)

    private fun baseHeaders(index: BundleIndex): Map<String, String> =
        mapOf(
            "Cache-Control" to "no-cache",
            "Accept-Ranges" to "bytes",
            "X-Plonk-Bundle" to (index.build ?: "apk"),
        )

    /**
     * Parse `bytes=a-b`, `bytes=a-` or `bytes=-n` against [size]. Returns null for anything else
     * (multiple ranges, other units), which sends the request to the network.
     */
    internal fun parseRange(
        header: String,
        size: Long,
    ): LongRange? {
        val spec = header.trim()
        if (!spec.startsWith("bytes=", ignoreCase = true)) return null
        val value = spec.substring(6).trim()
        if (',' in value) return null
        val dash = value.indexOf('-')
        if (dash < 0) return null
        val a = value.substring(0, dash).trim()
        val b = value.substring(dash + 1).trim()
        if (a.isEmpty()) {
            val suffix = b.toLongOrNull() ?: return null
            if (suffix <= 0 || size == 0L) return UNSATISFIABLE
            return maxOf(0, size - suffix)..(size - 1)
        }
        val start = a.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val end = if (b.isEmpty()) Long.MAX_VALUE else b.toLongOrNull() ?: return null
        if (end < start) return null
        if (start >= size) return UNSATISFIABLE
        return start..minOf(end, size - 1)
    }

    private fun skipFully(
        stream: InputStream,
        count: Long,
    ) {
        var left = count
        while (left > 0) {
            val skipped = stream.skip(left)
            if (skipped > 0) {
                left -= skipped
            } else if (stream.read() >= 0) {
                left--
            } else {
                throw IOException("Range starts past the end of the asset")
            }
        }
    }

    /** Ends the body after [limit] bytes, for a 206 response. */
    private class LimitedStream(
        source: InputStream,
        private var limit: Long,
    ) : FilterInputStream(source) {
        override fun read(): Int {
            if (limit <= 0) return -1
            val b = super.read()
            if (b >= 0) limit--
            return b
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (limit <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), limit).toInt())
            if (n > 0) limit -= n
            return n
        }

        override fun skip(n: Long): Long {
            val skipped = super.skip(minOf(n, limit))
            limit -= skipped
            return skipped
        }

        override fun available(): Int = minOf(super.available().toLong(), limit).toInt()

        override fun markSupported() = false
    }
}
