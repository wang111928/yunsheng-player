package com.litemusic.shared.util

private val emoteGlyphs = mapOf(
    "认可" to "👍", "感动" to "🥹", "偷笑" to "🤭", "大笑" to "😄", "爱心" to "❤️",
    "偷窥" to "🫣", "装可爱" to "🥺", "可爱" to "😊", "流泪" to "😭", "生气" to "😠",
    "呲牙" to "😁", "亲亲" to "😘", "惊恐" to "😱", "酷" to "😎",
)

/**
 * Common, server-safe comment tokens.  The app deliberately sends the token
 * rather than an image reference, so the server and clients that do not ship
 * a sticker pack still receive a readable comment.
 */
val commonEmoteTokens: List<String> = emoteGlyphs.keys.map { "[$it]" }

/** Text-only fallback for common server tokens; unknown tokens intentionally remain visible. */
fun displayEmotes(text: String): String {
    return Regex("\\[([^]]+)]").replace(text) { match ->
        emoteGlyphs[match.groupValues[1].replace(Regex("\\s+"), "")] ?: match.value
    }
}
