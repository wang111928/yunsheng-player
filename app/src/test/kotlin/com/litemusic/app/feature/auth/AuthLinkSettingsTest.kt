package com.litemusic.app.feature.auth

import org.junit.Assert.*
import org.junit.Test

class AuthLinkSettingsTest {
    @Test fun nativeReturnRequiresBothUserPermissionAndDomainSelection() {
        assertFalse(allowsQqAuthReturn(false, 1))
        assertFalse(allowsQqAuthReturn(false, 2))
        assertFalse(allowsQqAuthReturn(true, 0))
        assertTrue(allowsQqAuthReturn(true, 1))
        assertTrue(allowsQqAuthReturn(true, 2))
    }
}
