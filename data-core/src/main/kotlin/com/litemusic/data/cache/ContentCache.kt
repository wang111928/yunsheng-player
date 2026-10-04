package com.litemusic.data.cache

import android.content.Context
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 双层缓存（内存 + 磁盘）：图片 / 歌词 / API 响应。
 * 内存默认 64MB（按构建 flavor 可在 Settings 调整），磁盘上限 256MB（12GB RAM 的 2%）。
 */
class ContentCache(private val context: Context) {
    private val memory = LruCache<String, ByteArray>(64 * 1024 * 1024)
    private val dir: File = File(context.cacheDir, "nml-cache").apply { mkdirs() }

    @Volatile
    private var diskSize = computeDiskSize()

    suspend fun get(key: String): ByteArray? = withContext(Dispatchers.IO) {
        memory.get(key) ?: run {
            val f = file(key)
            if (f.exists() && f.length() > 0) {
                val bytes = f.readBytes()
                memory.put(key, bytes)
                bytes
            } else null
        }
    }

    suspend fun getString(key: String): String? = get(key)?.toString(Charsets.UTF_8)

    suspend fun put(key: String, bytes: ByteArray) {
        memory.put(key, bytes)
        withContext(Dispatchers.IO) {
            val f = file(key)
            f.writeBytes(bytes)
            diskSize += bytes.size
            enforceLimit()
        }
    }

    suspend fun putString(key: String, value: String) = put(key, value.toByteArray(Charsets.UTF_8))

    suspend fun keyExists(key: String): Boolean = withContext(Dispatchers.IO) {
        memory.get(key) != null || file(key).exists()
    }

    /** 删除单个缓存项（内存 + 磁盘），用于播放地址失效等场景 */
    suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        memory.remove(key)
        val f = file(key)
        if (f.exists()) {
            diskSize -= f.length()
            f.delete()
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        memory.evictAll()
        dir.listFiles()?.forEach { it.delete() }
        diskSize = 0
    }

    fun currentDiskSize(): Long = diskSize

    private fun file(key: String): File = File(dir, key.hashCode().toUInt().toString(16) + ".bin")

    private fun computeDiskSize(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun enforceLimit() {
        val limit = 256L * 1024 * 1024
        if (diskSize <= limit) return
        dir.listFiles()
            ?.sortedBy { it.lastModified() }
            ?.forEach { f ->
                if (diskSize <= limit) return
                diskSize -= f.length()
                f.delete()
            }
    }
}
