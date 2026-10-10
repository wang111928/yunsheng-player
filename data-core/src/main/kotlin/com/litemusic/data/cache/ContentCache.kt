package com.litemusic.data.cache

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/**
 * 双层缓存（内存 + 磁盘）：图片 / 歌词 / API 响应。
 * 内存默认 64 MiB，磁盘上限 256 MiB。长期离线歌单元数据独立保存在私有目录，
 * 不参与容量限制，也不会被 [clear] 删除。
 */
class ContentCache private constructor(
    private val dir: File,
    private val offlinePlaylistDir: File,
) {
    constructor(context: Context) : this(
        File(context.cacheDir, "nml-cache"),
        File(context.filesDir, "nml-offline-playlists"),
    )

    /** Test-only filesystem root; production callers should supply an Android context. */
    internal constructor(root: File) : this(File(root, "nml-cache"), File(root, "nml-offline-playlists"))

    private val memory = ByteLruCache(64 * 1024 * 1024)
    private val diskLock = Any()

    @Volatile
    private var diskSize: Long = synchronized(diskLock) {
        dir.mkdirs()
        computeDiskSize()
    }

    suspend fun get(key: String): ByteArray? = withContext(Dispatchers.IO) {
        memory.get(key) ?: synchronized(diskLock) {
            val f = file(key)
            if (f.exists() && f.length() > 0) {
                f.readBytes().also { memory.put(key, it) }
            } else null
        }
    }

    suspend fun getString(key: String): String? = get(key)?.toString(Charsets.UTF_8)

    suspend fun put(key: String, bytes: ByteArray) {
        withContext(Dispatchers.IO) { synchronized(diskLock) {
            val f = file(key)
            val previousSize = f.takeIf(File::exists)?.length() ?: 0L
            writeAtomically(f, bytes)
            diskSize = (diskSize - previousSize + bytes.size).coerceAtLeast(0L)
            memory.put(key, bytes)
            enforceLimit()
        } }
    }

    suspend fun putString(key: String, value: String) = put(key, value.toByteArray(Charsets.UTF_8))

    suspend fun keyExists(key: String): Boolean = withContext(Dispatchers.IO) {
        memory.get(key) != null || synchronized(diskLock) { file(key).exists() }
    }

    /** 删除单个缓存项（内存 + 磁盘），用于播放地址失效等场景 */
    suspend fun remove(key: String) = withContext(Dispatchers.IO) { synchronized(diskLock) {
        memory.remove(key)
        val f = file(key)
        if (f.exists()) {
            val length = f.length()
            if (f.delete()) diskSize = (diskSize - length).coerceAtLeast(0L)
        }
    } }

    /** Clear only disposable content such as covers, lyrics, and temporary API responses. */
    suspend fun clear() = withContext(Dispatchers.IO) { synchronized(diskLock) {
        memory.evictAll()
        dir.listFiles()?.forEach { it.delete() }
        diskSize = computeDiskSize()
    } }

    fun currentDiskSize(): Long = synchronized(diskLock) { diskSize }

    /** Account-private durable playlist catalog/detail storage. */
    suspend fun getOfflinePlaylistString(userId: Long, key: String): String? = withContext(Dispatchers.IO) {
        if (userId <= 0L) null else synchronized(diskLock) {
            offlineFile(userId, key).takeIf { it.exists() && it.length() > 0 }
                ?.readBytes()?.toString(Charsets.UTF_8)
        }
    }

    suspend fun putOfflinePlaylistString(userId: Long, key: String, value: String) = withContext(Dispatchers.IO) {
        if (userId > 0L) synchronized(diskLock) {
            writeAtomically(offlineFile(userId, key), value.toByteArray(Charsets.UTF_8))
        }
    }

    suspend fun removeOfflinePlaylist(userId: Long, key: String) = withContext(Dispatchers.IO) {
        if (userId > 0L) synchronized(diskLock) { offlineFile(userId, key).delete() }
    }

    private fun file(key: String): File = File(dir, key.hashCode().toUInt().toString(16) + ".bin")

    private fun offlineFile(userId: Long, key: String): File {
        val accountDir = File(offlinePlaylistDir, userId.toString()).apply { mkdirs() }
        return File(accountDir, key.hashCode().toUInt().toString(16) + ".bin")
    }

    private fun computeDiskSize(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun enforceLimit() {
        val limit = 256L * 1024 * 1024
        if (diskSize <= limit) return
        dir.listFiles()
            ?.sortedBy { it.lastModified() }
            ?.forEach { f ->
                if (diskSize <= limit) return
                val length = f.length()
                if (f.delete()) diskSize = (diskSize - length).coerceAtLeast(0L)
            }
    }
}

/** Android's platform LruCache is stubbed in local JVM tests; keep the same byte-based contract. */
private class ByteLruCache(private val maxBytes: Int) {
    private val entries = LinkedHashMap<String, ByteArray>(0, 0.75f, true)
    private var sizeBytes = 0

    @Synchronized
    fun get(key: String): ByteArray? = entries[key]

    @Synchronized
    fun put(key: String, value: ByteArray) {
        entries.put(key, value)?.let { sizeBytes -= it.size }
        sizeBytes += value.size
        while (sizeBytes > maxBytes && entries.isNotEmpty()) {
            val eldest = entries.entries.iterator().next()
            sizeBytes -= eldest.value.size
            entries.remove(eldest.key)
        }
    }

    @Synchronized
    fun remove(key: String) {
        entries.remove(key)?.let { sizeBytes -= it.size }
    }

    @Synchronized
    fun evictAll() {
        entries.clear()
        sizeBytes = 0
    }
}
