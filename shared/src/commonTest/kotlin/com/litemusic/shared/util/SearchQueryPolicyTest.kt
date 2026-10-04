package com.litemusic.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchQueryPolicyTest {
    @Test
    fun normalizeCollapsesWhitespaceWithoutChangingWords() {
        assertEquals("孤单心事", SearchQueryPolicy.normalize("  孤单心事  "))
        assertEquals("周杰伦 稻香", SearchQueryPolicy.normalize("周杰伦\n\t稻香"))
    }

    @Test
    fun blankInputIsNotSearchable() {
        assertFalse(SearchQueryPolicy.isSearchable(" \n\t"))
        assertTrue(SearchQueryPolicy.isSearchable("a"))
    }
}
