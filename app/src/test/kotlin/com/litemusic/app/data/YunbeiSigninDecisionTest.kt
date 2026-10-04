package com.litemusic.app.data

import com.litemusic.shared.model.YunbeiSigninResponse
import com.litemusic.shared.util.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YunbeiSigninDecisionTest {
    private val before = YunbeiRemoteSnapshot(days = 0, signedShells = 1, todayShells = 0)

    @Test
    fun rejectsANon200ModernSigninResponse() {
        val decision = decideYunbeiSignin(
            signCode = 500,
            accountBefore = 7L,
            accountAfter = 7L,
            before = before,
            after = before,
        )

        assertFalse(decision.accepted)
    }

    @Test
    fun rejectsAResponseWhenTheAccountChangesDuringSubmission() {
        val decision = decideYunbeiSignin(
            signCode = 200,
            accountBefore = 7L,
            accountAfter = 8L,
            before = before,
            after = before,
        )

        assertFalse(decision.accepted)
        assertTrue(decision.message.contains("账号"))
    }

    @Test
    fun requiresBothRemoteStatusReadsAfterAnAcceptedResponse() {
        val decision = decideYunbeiSignin(
            signCode = 200,
            accountBefore = 7L,
            accountAfter = 7L,
            before = before,
            after = null,
        )

        assertFalse(decision.accepted)
        assertTrue(decision.message.contains("读取失败"))
    }

    @Test
    fun doesNotCallAmbiguousRemoteFieldsASignedInState() {
        val decision = decideYunbeiSignin(
            signCode = 200,
            accountBefore = 7L,
            accountAfter = 7L,
            before = before,
            after = before,
        )

        assertFalse(decision.accepted)
        assertEquals("云贝签到后远端奖励状态未变化，无法确认", decision.message)
    }

    @Test
    fun reportsOnlyThatTheRemoteRewardFieldsChanged() {
        val decision = decideYunbeiSignin(
            signCode = 200,
            accountBefore = 7L,
            accountAfter = 7L,
            before = before,
            after = before.copy(todayShells = 1),
        )

        assertTrue(decision.accepted)
        assertEquals("云贝签到接口成功，远端奖励字段已更新", decision.message)
    }

    @Test
    fun modernResponseWithSignTrueIsConfirmedWithoutARewardDelta() {
        val decision = decideModernYunbeiSignin(
            signCode = 200,
            responseSigned = true,
            accountBefore = 7L,
            accountAfter = 7L,
            before = ModernYunbeiRemoteSnapshot(yunbei = 10),
            after = ModernYunbeiRemoteSnapshot(yunbei = 10),
        )

        assertTrue(decision.accepted)
    }

    @Test
    fun modernResponseWithoutSignNeedsAReadbackChange() {
        val decision = decideModernYunbeiSignin(
            signCode = 200,
            responseSigned = false,
            accountBefore = 7L,
            accountAfter = 7L,
            before = ModernYunbeiRemoteSnapshot(yunbei = 10, completedTaskIds = setOf(1L)),
            after = ModernYunbeiRemoteSnapshot(yunbei = 10, completedTaskIds = setOf(1L)),
        )

        assertFalse(decision.accepted)
        assertTrue(decision.message.contains("无法确认"))
    }

    @Test
    fun modernReadbackCanConfirmThroughTaskCompletion() {
        val decision = decideModernYunbeiSignin(
            signCode = 200,
            responseSigned = null,
            accountBefore = 7L,
            accountAfter = 7L,
            before = ModernYunbeiRemoteSnapshot(yunbei = 10, completedTaskIds = setOf(1L)),
            after = ModernYunbeiRemoteSnapshot(yunbei = 10, completedTaskIds = setOf(1L, 2L)),
        )

        assertTrue(decision.accepted)
    }

    @Test
    fun onlyExplicitModernEndpointUnsupportedCodesMayUseTheLegacyFallback() {
        assertTrue(
            isModernYunbeiEndpointUnsupported(
                AppResult.Success(YunbeiSigninResponse(code = 501)),
            ),
        )
        assertFalse(
            isModernYunbeiEndpointUnsupported(
                AppResult.Success(YunbeiSigninResponse(code = 500)),
            ),
        )
        assertFalse(
            isModernYunbeiEndpointUnsupported(AppResult.Failure(-1, "网络超时")),
        )
    }
}
