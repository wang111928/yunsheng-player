package com.litemusic.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MembershipInfoTest {
    private fun payload(code: Int = 200, uid: Long = 42, expiry: Long = 2000) = buildJsonObject {
        put("code", code)
        putJsonObject("data") {
            put("userId", uid); put("now", 1000); put("redVipLevel", 7)
            putJsonObject("associator") { put("expireTime", expiry) }
        }
    }
    @Test fun confirmsRemoteLevelAndExpiryWithoutGuessingTier() {
        val result = membershipDescription(payload(), 42, 1000)!!
        assertTrue(result.contains("会员等级 7")); assertTrue(result.contains("权益至"))
        assertFalse(result.contains("SVIP"))
    }
    @Test fun rejectsFailureAndWrongAccount() {
        assertNull(membershipDescription(payload(code = 301), 42, 1000))
        assertNull(membershipDescription(payload(uid = 99), 42, 1000))
    }
    @Test fun expiredRightsDoNotShowActiveMembership() {
        assertEquals("暂无有效会员权益", membershipDescription(payload(expiry = 500), 42, 1000))
    }
}
