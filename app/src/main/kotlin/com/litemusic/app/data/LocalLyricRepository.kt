package com.litemusic.app.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.litemusic.lyric.LocalLrcLoader
import com.litemusic.shared.player.QueueItem
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Caches manually selected local lyrics in app-private storage.  The selected URI is never kept:
 * it may no longer be readable after the picker closes, while the validated text remains usable.
 */
class LocalLyricRepository(context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    private val store = LocalLyricFileStore(File(context.filesDir, "local-lyrics"))

    suspend fun load(item: QueueItem): String? = withContext(Dispatchers.IO) {
        val path = item.localPath ?: return@withContext null
        store.load(item.id, path) ?: LocalLrcLoader.load(path, item.title)
    }

    suspend fun import(item: QueueItem, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val path = item.localPath ?: return@withContext false
        try {
            resolver.openInputStream(uri)?.use { input ->
                store.save(item.id, path, input)
            } ?: false
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}

/** Kept file-only so import persistence and replacement safety can be tested without Android. */
internal class LocalLyricFileStore(private val root: File) {
    fun load(songId: Long, localPath: String): String? = try {
        fileFor(songId, localPath).takeIf { it.isFile && it.canRead() }?.inputStream()?.use {
            LocalLrcLoader.readValidatedText(it)
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /** Validates before touching the old cache entry, then replaces it through a temporary file. */
    fun save(songId: Long, localPath: String, input: InputStream): Boolean {
        val text = LocalLrcLoader.readValidatedText(input) ?: return false
        val encoded = text.toByteArray(StandardCharsets.UTF_8)
        if (encoded.size > LocalLrcLoader.MAX_LRC_BYTES) return false
        var temporary: File? = null
        return try {
            Files.createDirectories(root.toPath())
            val destination = fileFor(songId, localPath).toPath()
            temporary = Files.createTempFile(root.toPath(), "lyric-", ".tmp").toFile()
            temporary.outputStream().use { it.write(encoded) }
            try {
                Files.move(
                    temporary.toPath(),
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination, StandardCopyOption.REPLACE_EXISTING)
            }
            temporary = null
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } finally {
            temporary?.delete()
        }
    }

    private fun fileFor(songId: Long, localPath: String): File {
        val identity = "$songId\u0000$localPath".toByteArray(StandardCharsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(identity).joinToString("") { "%02x".format(it) }
        return File(root, "$hash.lrc")
    }
}
