package com.litemusic.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EmoteTextTest {
    @Test
    fun knownTokensBecomeEmojiAndUnknownTokensAndUnicodeRemain() {
        assertEquals("👍 [未知] 🎵", displayEmotes("[认可] [未知] 🎵"))
        assertEquals("🥹🤭", displayEmotes("[感动][偷笑]"))
        assertEquals("🫣🥺", displayEmotes("[偷窥][装 可 爱]"))
    }

    @Test
    fun commonTokensAreServerSyntaxSupportedByTheTextFallback() {
        assertEquals("[认可]", commonEmoteTokens.first())
        assertEquals("[酷]", commonEmoteTokens.last())
        assertFalse(displayEmotes(commonEmoteTokens.joinToString("")).contains('['))
    }
}
