package com.litemusic.shared.crypto

import kotlinx.serialization.json.*
import kotlin.test.*

class JsonTextTest {
    @Test fun privateMessageSpecialCharactersRoundTrip() {
        val message = "你好\"朋友\"\n路径 C:\\Music\\\t完成"
        val obj = Json.parseToJsonElement(JsonText.build(mapOf("msg" to message, "userIds" to "[123]"))).jsonObject
        assertEquals(message, obj.getValue("msg").jsonPrimitive.content)
        assertEquals("[123]", obj.getValue("userIds").jsonPrimitive.content)
    }
    @Test fun nestedHeaderAndEscapedKeysStayValid() {
        val obj = Json.parseToJsonElement(JsonText.build(mapOf("header" to mapOf("deviceId" to "test\"device"), "a\"b" to true))).jsonObject
        assertEquals("test\"device", obj.getValue("header").jsonObject.getValue("deviceId").jsonPrimitive.content)
        assertTrue(obj.getValue("a\"b").jsonPrimitive.boolean)
    }
}
