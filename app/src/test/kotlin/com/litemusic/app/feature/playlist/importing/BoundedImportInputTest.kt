package com.litemusic.app.feature.playlist.importing

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class BoundedImportInputTest {
    @Test fun acceptsTheExactLimitAndRejectsAnExtraByteWithoutTruncation() {
        val data = "夜曲 - 周杰伦".toByteArray(Charsets.UTF_8)
        assertArrayEquals(data, readImportBytes(ByteArrayInputStream(data), data.size))
        assertNull(readImportBytes(ByteArrayInputStream(data), data.size - 1))
        assertArrayEquals(ByteArray(0), readImportBytes(ByteArrayInputStream(ByteArray(0)), 20))
    }

    @Test fun decodesEachSupportedImportExtensionCaseInsensitively() {
        listOf("list.TXT", "list.CSV", "list.JSON", "list.M3U", "list.M3U8").forEach { name ->
            assertEquals("夜曲 - 周杰伦", decodeImportDocument("夜曲 - 周杰伦".toByteArray(), name))
        }
    }

    @Test fun allowsUnnamedStrictUtf8AndStripsUtf8Bom() {
        assertEquals("晴天 - 周杰伦", decodeImportDocument("晴天 - 周杰伦".toByteArray(), null))
        assertEquals("晴天", decodeImportDocument(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0xE6.toByte(), 0x99.toByte(), 0xB4.toByte(), 0xE5.toByte(), 0xA4.toByte(), 0xA9.toByte()), "list.txt"))
    }

    @Test fun rejectsUnsupportedNamesMalformedUtf8AndBinaryControls() {
        assertNull(decodeImportDocument("歌单".toByteArray(), "list.pdf"))
        assertNull(decodeImportDocument(byteArrayOf(0xC3.toByte(), 0x28), "list.txt"))
        assertNull(decodeImportDocument(byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte()), "list.txt"))
        assertNull(decodeImportDocument(byteArrayOf(1, 'a'.code.toByte()), "list.txt"))
    }
}
