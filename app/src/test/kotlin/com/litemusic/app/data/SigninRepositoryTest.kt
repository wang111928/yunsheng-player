package com.litemusic.app.data

import com.litemusic.shared.model.SigninResult
import com.litemusic.shared.model.YunbeiTask
import com.litemusic.shared.model.YunbeiTaskListData
import com.litemusic.shared.model.YunbeiTaskListResponse
import com.litemusic.shared.util.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SigninRepositoryTest {
    @Test
    fun onlyBusinessCode200CountsAsASuccessfulSignin() {
        assertTrue(isSuccessfulSignin(AppResult.Success(SigninResult(code = 200))))
        assertFalse(isSuccessfulSignin(AppResult.Success(SigninResult(code = 500, message = "已失败"))))
        assertFalse(isSuccessfulSignin(AppResult.Failure(500, "网络失败")))
    }

    @Test
    fun onlyCompletedRemoteSigninTasksMarkTodayAsSigned() {
        assertTrue(
            isRemoteSigninTaskComplete(
                listOf(YunbeiTask(name = "每日签到", done = true)),
            ),
        )
        assertFalse(
            isRemoteSigninTaskComplete(
                listOf(YunbeiTask(name = "每日签到", done = false)),
            ),
        )
        assertFalse(
            isRemoteSigninTaskComplete(
                listOf(YunbeiTask(name = "浏览推荐歌曲", done = true)),
            ),
        )
    }

    @Test
    fun normalizesCurrentTaskListFieldsForSigninState() {
        val task = normalizeModernYunbeiTask(
            YunbeiTask(
                taskId = 101L,
                taskName = "每日签到",
                taskPoint = 5,
                completed = true,
                completedPoint = 5,
            ),
        )

        assertTrue(task.done)
        assertTrue(isRemoteSigninTaskComplete(listOf(task)))
        assertTrue(task.userTaskId == 101L)
        assertTrue(task.name == "每日签到")
        assertTrue(task.yunbei == 5)
    }

    @Test
    fun mapsCurrentTaskListForTheSigninScreen() {
        val result = modernYunbeiTasksForUi(
            AppResult.Success(
                YunbeiTaskListResponse(
                    code = 200,
                    data = YunbeiTaskListData(
                        directTasks = listOf(
                            YunbeiTask(
                                taskId = 101L,
                                taskName = "每日签到",
                                taskPoint = 5,
                                completed = true,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val tasks = (result as AppResult.Success).data.tasks
        assertEquals(101L, tasks.single().userTaskId)
        assertEquals("每日签到", tasks.single().name)
        assertTrue(tasks.single().done)
        assertEquals(5, tasks.single().yunbei)
    }

    @Test
    fun onlyExplicitUnsupportedTaskListResponsesMayFallBackToLegacy() {
        assertNull(modernYunbeiTasksForUi(AppResult.Success(YunbeiTaskListResponse(code = 404))))
        assertNull(modernYunbeiTasksForUi(AppResult.Success(YunbeiTaskListResponse(code = 405))))
        assertNull(modernYunbeiTasksForUi(AppResult.Success(YunbeiTaskListResponse(code = 501))))
        assertTrue(
            modernYunbeiTasksForUi(AppResult.Success(YunbeiTaskListResponse(code = 500)))
                is AppResult.Failure,
        )
        assertTrue(
            modernYunbeiTasksForUi(AppResult.Failure(-1, "网络超时"))
                is AppResult.Failure,
        )
    }

    @Test
    fun doesNotPretendAMissingModernTaskPayloadIsAnEmptySuccessfulList() {
        assertTrue(
            modernYunbeiTasksForUi(AppResult.Success(YunbeiTaskListResponse(code = 200, data = null)))
                is AppResult.Failure,
        )
    }
}
