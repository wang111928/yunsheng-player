package com.litemusic.player

import com.litemusic.shared.player.PlayerStateMachine

/** Maps platform next/previous commands to the authoritative Lite queue, never Exo's preload list. */
class QueueSessionNavigator(private val stateMachine: PlayerStateMachine) {
    fun hasNext(): Boolean = stateMachine.nextIndex() != null
    fun hasPrevious(): Boolean = stateMachine.previousIndex() >= 0

    fun next(): Boolean = moveTo(stateMachine.nextIndex())
    fun previous(): Boolean = moveTo(stateMachine.previousIndex().takeIf { it >= 0 })

    private fun moveTo(index: Int?): Boolean {
        if (index == null) return false
        stateMachine.playIndex(index)
        return true
    }
}
