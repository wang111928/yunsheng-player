package com.litemusic.player

import java.io.File

/** Selects one cache directory before SimpleCache takes its process-wide directory lock. */
internal object StreamCacheStorage {
    /**
     * Moves the legacy cache atomically when both app-owned roots are on the same volume.
     * A failed move deliberately keeps using the old directory so its spans and index remain
     * usable; a future process start can retry the move. Existing persistent storage always
     * wins and is never merged with or overwritten by legacy storage.
     */
    fun resolveDirectory(noBackupRoot: File, legacyCacheRoot: File): File {
        val persistent = File(noBackupRoot, StreamAudioCache.CACHE_DIRECTORY)
        val legacy = File(legacyCacheRoot, StreamAudioCache.CACHE_DIRECTORY)

        if (persistent.isDirectory) return persistent
        if (persistent.exists()) return legacy.takeIf(File::isDirectory) ?: persistent
        if (!legacy.isDirectory) return persistent
        if (!noBackupRoot.isDirectory && !noBackupRoot.mkdirs()) return legacy

        return if (legacy.renameTo(persistent)) persistent else legacy
    }
}
