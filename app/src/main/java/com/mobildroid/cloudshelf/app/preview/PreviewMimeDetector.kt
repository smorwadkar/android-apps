package com.mobildroid.cloudshelf.app.preview

/**
 * Maps an object key (or filename) to a [PreviewKind] based on its extension.
 * We don't fetch the actual `Content-Type` header because that would cost an
 * extra HEAD request per row — extension is a reasonable proxy.
 */
enum class PreviewKind { IMAGE, PDF, VIDEO, TEXT, UNSUPPORTED }

object PreviewMimeDetector {

    fun kindFor(key: String): PreviewKind = when (extension(key)) {
        in IMAGE_EXTS -> PreviewKind.IMAGE
        "pdf" -> PreviewKind.PDF
        in VIDEO_EXTS -> PreviewKind.VIDEO
        in TEXT_EXTS -> PreviewKind.TEXT
        else -> PreviewKind.UNSUPPORTED
    }

    /** Best-guess MIME type for "Open with…" intents on the fallback path. */
    fun mimeTypeFor(key: String): String = when (val ext = extension(key)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "pdf" -> "application/pdf"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "txt", "log" -> "text/plain"
        "md" -> "text/markdown"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "html", "htm" -> "text/html"
        "csv" -> "text/csv"
        "yml", "yaml" -> "application/x-yaml"
        in CODE_EXTS -> "text/plain"
        else -> "application/octet-stream"
    }

    private fun extension(key: String): String =
        key.substringAfterLast('.', missingDelimiterValue = "").lowercase()

    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")
    private val VIDEO_EXTS = setOf("mp4", "m4v", "webm", "mov", "mkv", "3gp")
    private val CODE_EXTS = setOf(
        "kt", "java", "py", "js", "ts", "tsx", "jsx",
        "go", "rs", "c", "cpp", "h", "hpp", "swift",
        "rb", "sh", "bash", "zsh", "ps1",
        "gradle", "kts", "toml", "ini", "conf", "properties"
    )
    private val TEXT_EXTS = setOf(
        "txt", "log", "md", "json", "xml", "html", "htm", "csv",
        "yml", "yaml"
    ) + CODE_EXTS
}
