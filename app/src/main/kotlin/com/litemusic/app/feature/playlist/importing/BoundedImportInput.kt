package com.litemusic.app.feature.playlist.importing

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Returns null rather than truncating a too-large document. Compatible with Android API 28. */
internal fun readImportBytes(stream: InputStream, limit: Int): ByteArray? {
    require(limit >= 0)
    val output = ByteArrayOutputStream(minOf(limit, 16_384))
    val buffer = ByteArray(16_384)
    while (true) {
        // One extra byte distinguishes exact-limit EOF from an oversized input.
        val count = stream.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
        if (count < 0) return output.toByteArray()
        if (count == 0) {
            val byte = stream.read()
            if (byte < 0) return output.toByteArray()
            if (output.size() == limit) return null
            output.write(byte)
        } else {
            if (output.size() + count > limit) return null
            output.write(buffer, 0, count)
        }
    }
}

private val supportedImportExtensions = setOf("txt", "csv", "json", "m3u", "m3u8")

/**
 * Decodes only plain UTF-8 import documents. A provider MIME type is advisory and varies across
 * Android file managers, so named documents are accepted by their normalised filename extension.
 * Unnamed provider streams remain usable only when their bytes are strict, printable UTF-8.
 */
internal fun decodeImportDocument(bytes: ByteArray, fileName: String?): String? {
    if (fileName != null) {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension !in supportedImportExtensions) return null
    }
    val text = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()?.removePrefix("\uFEFF") ?: return null
    if (text.isBlank()) return null
    return text.takeUnless { value ->
        value.any { char ->
            val control = char.code in 0..0x1F || char.code in 0x7F..0x9F
            control && char !in setOf('\n', '\r', '\t')
        }
    }
}

/** Opens one user-selected URI, checking its bounded bytes before strict text decoding. */
internal fun readBoundedImportFile(context: Context, uri: Uri, limit: Int = MAX_IMPORT_FILE_BYTES): String? = runCatching {
    val fileName = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }.getOrNull()
    val bytes = context.contentResolver.openInputStream(uri)?.use { stream -> readImportBytes(stream, limit) }
        ?: return@runCatching null
    decodeImportDocument(bytes, fileName)
}.getOrNull()

internal const val MAX_IMPORT_FILE_BYTES = 1_048_576
