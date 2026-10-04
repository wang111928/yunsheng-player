package com.litemusic.app.feature.player

import com.litemusic.shared.player.QueueItem

/**
 * Owns the picker target and the import result's UI relevance.  Keeping this separate from the
 * Android picker makes a result chosen for an earlier song unable to reload or message a new one.
 */
internal class LocalLyricImportCoordinator<T> {
    enum class Completion { RELOAD_CURRENT, SHOW_FAILURE, NO_CURRENT_UPDATE }

    private var pending: QueueItem? = null
    private var inFlight: QueueItem? = null
    private var failedSource: LyricSourceKey? = null

    fun prepare(item: QueueItem): Boolean {
        if (!item.isLocal || pending != null || inFlight != null) return false
        pending = item
        return true
    }

    /** A null picker result is a cancellation and makes the next selection available immediately. */
    fun accept(result: T?): QueueItem? {
        val target = pending ?: return null
        pending = null
        if (result == null) return null
        inFlight = target
        return target
    }

    fun finish(target: QueueItem, succeeded: Boolean, current: QueueItem?): Completion {
        if (inFlight != target) return Completion.NO_CURRENT_UPDATE
        inFlight = null
        val source = target.lyricSourceKey()
        if (succeeded && failedSource == source) failedSource = null
        if (current?.lyricSourceKey() != source) return Completion.NO_CURRENT_UPDATE
        return if (succeeded) Completion.RELOAD_CURRENT else {
            failedSource = source
            Completion.SHOW_FAILURE
        }
    }

    fun onCurrentChanged(current: QueueItem?) {
        if (failedSource != current?.lyricSourceKey()) failedSource = null
    }

    fun failureMessageFor(item: QueueItem?): String? =
        if (failedSource != null && item?.lyricSourceKey() == failedSource) IMPORT_FAILURE_MESSAGE else null

    companion object {
        const val IMPORT_FAILURE_MESSAGE = "歌词导入失败，请选择有效的 .lrc 文件"
    }
}

/** Applies an import failure without discarding an already parsed local lyric document. */
internal fun PlayerViewModel.LyricUi.withLocalImportMessage(
    item: QueueItem?,
    imports: LocalLyricImportCoordinator<*>,
): PlayerViewModel.LyricUi = copy(message = imports.failureMessageFor(item) ?: message)
