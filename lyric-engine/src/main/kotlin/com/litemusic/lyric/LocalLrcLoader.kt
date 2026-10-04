package com.litemusic.lyric

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 本地歌词：同目录同名 .lrc。读取自动发现和手动导入时共用的格式验证。
 */
object LocalLrcLoader {
    const val MAX_LRC_BYTES: Int = 2 * 1024 * 1024

    fun load(songPath: String?, songTitle: String): String? {
        return try {
            val audio = localAudioFile(songPath) ?: return null
            val directory = audio.parentFile ?: return null
            if (!directory.isDirectory) return null

            val safeTitle = songTitle.takeIf(::isSafeFileStem)
            val candidates = buildList {
                add(File(directory, "${audio.nameWithoutExtension}.lrc"))
                add(File(directory, "${audio.nameWithoutExtension}.LRC"))
                safeTitle?.let { add(File(directory, "$it.lrc")) }
                safeTitle?.let { title ->
                    directory.listFiles { file ->
                        file.isFile &&
                            file.extension.equals("lrc", ignoreCase = true) &&
                            file.nameWithoutExtension.contains(title, ignoreCase = true)
                    }?.sortedBy { it.name.lowercase() }?.let(::addAll)
                }
            }.distinctBy { it.path }
            candidates.firstNotNullOfOrNull(::readValidatedFile)
        } catch (_: SecurityException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun readValidatedText(input: InputStream, maxBytes: Int = MAX_LRC_BYTES): String? = try {
        decodeAndValidate(readBounded(input, maxBytes))
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    fun decodeAndValidate(bytes: ByteArray): String? {
        if (bytes.isEmpty() || bytes.size > MAX_LRC_BYTES) return null
        val text = decode(bytes) ?: return null
        return text.takeIf { value -> LrcParser.parse(value).any { it.text.isNotBlank() } }
    }

    private fun readValidatedFile(file: File): String? {
        if (!file.isFile || !file.canRead()) return null
        return try {
            file.inputStream().use(::readValidatedText)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        if (maxBytes !in 1..MAX_LRC_BYTES) throw IOException("invalid lyric size limit")
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() > maxBytes - read) throw IOException("lyric is too large")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun localAudioFile(path: String?): File? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("file:", ignoreCase = true)) {
            val uri = try {
                URI(path)
            } catch (_: Exception) {
                return null
            }
            return if (uri.scheme.equals("file", ignoreCase = true)) File(uri) else null
        }
        if (path.contains("://") || path.startsWith("content:", ignoreCase = true)) return null
        return File(path)
    }

    private fun decode(bytes: ByteArray): String? = when {
        bytes.startsWith(UTF8_BOM) -> decodeStrict(bytes.copyOfRange(UTF8_BOM.size, bytes.size), Charsets.UTF_8)
        bytes.startsWith(UTF16_LE_BOM) -> decodeStrict(bytes.copyOfRange(UTF16_LE_BOM.size, bytes.size), Charsets.UTF_16LE)
        bytes.startsWith(UTF16_BE_BOM) -> decodeStrict(bytes.copyOfRange(UTF16_BE_BOM.size, bytes.size), Charsets.UTF_16BE)
        else -> decodeStrict(bytes, Charsets.UTF_8) ?: decodeStrict(bytes, GBK)
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun isSafeFileStem(value: String): Boolean =
        value.isNotBlank() && value.none { it in "\\/:*?\"<>|" || it.isISOControl() }

    private val GBK: Charset = Charset.forName("GBK")
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
}
