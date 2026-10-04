package com.litemusic.player

import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import com.litemusic.shared.player.PlayerStateMachine

/**
 * ExoPlayer intentionally contains only the current item (and an optional preload).  This
 * adapter advertises and handles MediaSession navigation from the authoritative Lite queue.
 */
internal class QueueSessionPlayer(
    wrapped: Player,
    private val stateMachine: PlayerStateMachine,
) : ForwardingPlayer(wrapped) {
    private val listeners = PlayerListenerForwarderRegistry(
        sessionPlayer = { this },
        commands = ::getAvailableCommands,
    )
    private val navigator = QueueSessionNavigator(stateMachine)

    override fun getAvailableCommands(): Player.Commands = getWrappedPlayer().availableCommands.buildUpon()
        .removeAll(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_TO_WINDOW)
        .addIf(Player.COMMAND_SEEK_TO_NEXT, hasNext())
        .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasNext())
        .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, canGoPrevious())
        .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, canGoPrevious())
        .build()

    override fun isCommandAvailable(command: Int): Boolean = getAvailableCommands().contains(command)

    override fun hasNext(): Boolean = navigator.hasNext()
    override fun hasNextMediaItem(): Boolean = hasNext()
    override fun hasPreviousMediaItem(): Boolean = canGoPrevious()
    private fun canGoPrevious(): Boolean = navigator.hasPrevious()

    override fun next() = move { navigator.next() }
    override fun seekToNext() = next()
    override fun seekToNextMediaItem() = next()
    override fun seekToNextWindow() = next()
    override fun seekToPrevious() = move { navigator.previous() }
    override fun seekToPreviousMediaItem() = seekToPrevious()
    override fun seekToPreviousWindow() = seekToPrevious()

    override fun addListener(listener: Player.Listener) {
        listeners.add(listener)?.let { super.addListener(it) }
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)?.let { super.removeListener(it) }
    }

    fun refreshCommands() {
        val commands = getAvailableCommands()
        val events = Player.Events(FlagSet.Builder().add(Player.EVENT_AVAILABLE_COMMANDS_CHANGED).build())
        listeners.originals().forEach { listener ->
            listener.onAvailableCommandsChanged(commands)
            listener.onEvents(this, events)
        }
    }

    private fun move(navigate: () -> Boolean) {
        val oldIndex = stateMachine.state.value.currentIndex
        if (!navigate()) return
        // Repeat-one intentionally returns the current index: playIndex resets position and
        // drives the service collector to prepare/play the item again.
        val sameItem = stateMachine.state.value.currentIndex == oldIndex
        if (sameItem) {
            getWrappedPlayer().seekTo(0)
            getWrappedPlayer().play()
        }
    }
}
