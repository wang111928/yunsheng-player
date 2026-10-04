package com.litemusic.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class PlayerListenerForwarderTest {
    @Test
    fun forwardsEveryDefaultPlayerListenerCallbackAndUsesTheSessionPlayerForEvents() {
        val delivered = mutableListOf<String>()
        val eventPlayers = mutableListOf<Player?>()
        val listener = listenerProxy { methodName, args ->
            delivered += methodName
            if (methodName == "onEvents") eventPlayers += args?.firstOrNull() as? Player
        }
        val sessionPlayer = playerProxy()
        val forwarder = PlayerListenerForwarder(listener, { sessionPlayer }, { Player.Commands.EMPTY })

        val callbacks = Player.Listener::class.java.methods.filter { it.declaringClass == Player.Listener::class.java }
        val forwardedCallbacks = PlayerListenerForwarder::class.java.methods
            .filter { it.declaringClass == PlayerListenerForwarder::class.java }

        assertEquals(callbacks.map(::signature).sorted(), forwardedCallbacks.map(::signature).sorted())

        forwarder.onIsPlayingChanged(false)
        forwarder.onPlaybackStateChanged(Player.STATE_BUFFERING)
        forwarder.onPlayWhenReadyChanged(false, 0)
        forwarder.onAvailableCommandsChanged(Player.Commands.EMPTY)
        forwarder.onEvents(playerProxy(), Player.Events(androidx.media3.common.FlagSet.Builder().build()))

        assertEquals(
            listOf(
                "onIsPlayingChanged",
                "onPlaybackStateChanged",
                "onPlayWhenReadyChanged",
                "onAvailableCommandsChanged",
                "onEvents",
            ),
            delivered,
        )
        assertTrue(eventPlayers.single() === sessionPlayer)
    }

    @Test
    fun registryRemovesAndReaddsAnIdentityListenerWithANewForwarder() {
        val registry = PlayerListenerForwarderRegistry({ playerProxy() }, { Player.Commands.EMPTY })
        val listener = listenerProxy { _, _ -> }

        val first = registry.add(listener)
        assertNotNull(first)
        assertNull(registry.add(listener))
        assertEquals(first, registry.remove(listener))

        val second = registry.add(listener)
        assertNotNull(second)
        org.junit.Assert.assertNotSame(first, second)
    }

    private fun listenerProxy(onCallback: (String, Array<out Any?>?) -> Unit): Player.Listener = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(Player.Listener::class.java),
    ) { _, method, args ->
        if (method.declaringClass == Player.Listener::class.java) onCallback(method.name, args)
        null
    } as Player.Listener

    private fun playerProxy(): Player = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(Player::class.java),
    ) { _, _, _ -> null } as Player

    private fun signature(method: java.lang.reflect.Method): String =
        method.name + method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }

}
