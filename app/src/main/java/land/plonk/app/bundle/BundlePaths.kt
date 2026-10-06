package land.plonk.app.bundle

/**
 * Which game URLs the bundle may ever answer, and with which content type.
 *
 * This is an allow-list on purpose. Everything the server generates per request (`/status`,
 * `/auth`, `/api`, skins, the service worker, websockets) must always reach the network, so the
 * bundle only covers the static client: the game shell, code, styles and art. A path outside
 * these rules goes to the network even if a manifest lists it.
 *
 * tools/fetch-bundle.mjs has the same rules (`servable`); change both together.
 */
internal object BundlePaths {
    /** The game shell. `/` is served from this file, as express.static does. */
    const val SHELL = "/index.html"

    private val DIRS =
        listOf("/js/", "/vendor/", "/css/", "/icons/", "/models/", "/sounds/", "/fx/", "/fonts/", "/images/", "/cards/")

    private val ROOT_IMAGES = setOf("png", "webp", "ico", "svg", "jpg", "jpeg", "gif", "avif")

    /** A content type, plus the charset for text types (WebResourceResponse wants them apart). */
    class Mime(
        val type: String,
        val charset: String? = null,
    )

    private const val TEXT = "utf-8"
    private val MIMES =
        mapOf(
            "html" to Mime("text/html", TEXT),
            "js" to Mime("text/javascript", TEXT),
            "mjs" to Mime("text/javascript", TEXT),
            "css" to Mime("text/css", TEXT),
            "json" to Mime("application/json", TEXT),
            "txt" to Mime("text/plain", TEXT),
            "wasm" to Mime("application/wasm"),
            "glb" to Mime("model/gltf-binary"),
            "gltf" to Mime("model/gltf+json", TEXT),
            "bin" to Mime("application/octet-stream"),
            "ktx2" to Mime("image/ktx2"),
            "png" to Mime("image/png"),
            "webp" to Mime("image/webp"),
            "avif" to Mime("image/avif"),
            "jpg" to Mime("image/jpeg"),
            "jpeg" to Mime("image/jpeg"),
            "gif" to Mime("image/gif"),
            "svg" to Mime("image/svg+xml", TEXT),
            "ico" to Mime("image/x-icon"),
            "mp3" to Mime("audio/mpeg"),
            "ogg" to Mime("audio/ogg"),
            "opus" to Mime("audio/ogg"),
            "wav" to Mime("audio/wav"),
            "m4a" to Mime("audio/mp4"),
            "woff2" to Mime("font/woff2"),
            "woff" to Mime("font/woff"),
            "ttf" to Mime("font/ttf"),
            "otf" to Mime("font/otf"),
        )

    /**
     * The manifest key for a decoded URL path, or null when the bundle must never answer it.
     * Keys are URL paths with a leading slash, e.g. `/js/main.js`; `/` maps to [SHELL].
     */
    fun keyFor(path: String): String? {
        if (path == "/" || path == SHELL) return SHELL
        if ("/." in path || ".." in path || "//" in path || '\\' in path) return null
        val ext = extensionOf(path) ?: return null
        if (ext !in MIMES) return null
        if (DIRS.any { path.startsWith(it) }) return path
        if (path.lastIndexOf('/') == 0 && ext in ROOT_IMAGES) return path
        return null
    }

    /** Content type for a key that passed [keyFor]. Unknown extensions never get this far. */
    fun mimeFor(key: String): Mime? = extensionOf(key)?.let { MIMES[it] }

    private fun extensionOf(path: String): String? {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        return name.substring(dot + 1).lowercase()
    }
}
