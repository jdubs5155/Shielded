package com.aggregatorx.app.engine.media

/**
 * Heuristic media-type detection for video URLs the scraper hands to
 * ExoPlayer. Combines URL extension *and* an optional MIME hint extracted
 * from the original page (`<source type="...">` or `Content-Type` header)
 * so we still pick HLS / DASH for manifest URLs that hide their format
 * behind query strings.
 *
 * This is what fixed the previous "black screen" bug: relying on
 * `videoUrl.contains(".m3u8")` missed manifests served as
 * `https://cdn.example/manifest?type=m3u8`, which then fell through to the
 * progressive source and never started rendering.
 */
object MediaTypeDetector {

    enum class Type { HLS, DASH, PROGRESSIVE }

    fun detect(videoUrl: String, mimeHint: String? = null): Type {
        // 1) Trust an explicit MIME hint from the page if we have one.
        if (!mimeHint.isNullOrBlank()) {
            val mh = mimeHint.lowercase()
            when {
                "mpegurl" in mh || mh == "application/x-mpegurl" -> return Type.HLS
                "dash"    in mh || mh == "application/dash+xml" -> return Type.DASH
                "mp4"     in mh || mh == "video/mp4"            -> return Type.PROGRESSIVE
            }
        }

        // 2) Otherwise look at the path (ignoring the query string).
        val pathPart = videoUrl.substringBefore('?').lowercase()
        return when {
            pathPart.endsWith(".m3u8") || pathPart.contains(".m3u8/") -> Type.HLS
            pathPart.endsWith(".mpd")  || pathPart.contains(".mpd/")  -> Type.DASH
            // 3) Last-resort: scan the full URL (catches `?type=m3u8`
            // / `?format=dash` style query-only manifests).
            else -> {
                val lower = videoUrl.lowercase()
                when {
                    "m3u8" in lower -> Type.HLS
                    "mpd"  in lower || "dash" in lower -> Type.DASH
                    else -> Type.PROGRESSIVE
                }
            }
        }
    }
}
