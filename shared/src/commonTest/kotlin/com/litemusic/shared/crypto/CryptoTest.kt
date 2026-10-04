package com.litemusic.shared.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CryptoTest {

    @Test
    fun weapiProducesParamsAndKey() {
        val r = WeapiCrypto.encrypt("{\"a\":1}")
        assertTrue(r.params.isNotEmpty())
        assertTrue(r.encSecKey.length == 256, "RSA 1024-bit = 256 hex chars, got " + r.encSecKey.length)
    }

    @Test
    fun eapiIsDeterministic() {
        val url = "/api/playlist/manipulate/tracks"
        val a = EapiCrypto.encrypt(url, "{\"pid\":1,\"op\":\"add\"}")
        val b = EapiCrypto.encrypt(url, "{\"pid\":1,\"op\":\"add\"}")
        assertEquals(a, b)
        assertTrue(a.isNotBlank())
    }

    @Test
    fun md5KnownVector() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", CryptoProvider.md5("abc"))
    }

    @Test
    fun jsonTextEncodes() {
        assertEquals("{\"a\":1,\"b\":\"x\"}", JsonText.build(mapOf("a" to 1, "b" to "x")))
    }
}
