package com.litemusic.shared.api

import com.litemusic.shared.model.YunbeiSigninResponse
import com.litemusic.shared.model.YunbeiTaskListResponse
import com.litemusic.shared.model.YunbeiUserInfoResponse
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class YunbeiSigninModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesModernSigninAndCurrentRemoteReadbackFields() {
        val signin = json.decodeFromString<YunbeiSigninResponse>(
            """{"code":200,"data":{"sign":true,"yunbeiNum":56},"message":"success"}""",
        )
        val user = json.decodeFromString<YunbeiUserInfoResponse>(
            """
            {
              "code": 200,
              "mobileSign": true,
              "pcSign": false,
              "userPoint": { "balance": 56 }
            }
            """.trimIndent(),
        )
        val tasks = json.decodeFromString<YunbeiTaskListResponse>(
            """
            {
              "code": 200,
              "data": [
                {
                  "taskId": 101,
                  "userTaskId": 9001,
                  "taskName": "每日签到",
                  "taskPoint": 5,
                  "completed": true,
                  "completedPoint": 5,
                  "status": 2
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(true, assertNotNull(signin.data).sign)
        assertEquals(true, user.mobileSign)
        assertEquals(false, user.pcSign)
        assertEquals(56, assertNotNull(user.userPoint).balance)
        val task = assertNotNull(tasks.data).allTasks.single()
        assertEquals(101L, task.taskId)
        assertEquals(9001L, task.userTaskId)
        assertEquals("每日签到", task.taskName)
        assertEquals(5, task.taskPoint)
        assertEquals(true, task.completed)
        assertEquals(5, task.completedPoint)
        assertEquals(2, task.status)
    }

    @Test
    fun keepsLegacyObjectTaskEnvelopeCompatible() {
        val tasks = json.decodeFromString<YunbeiTaskListResponse>(
            """{"code":200,"data":{"userTaskList":[{"userTaskId":101,"name":"旧任务","done":true}]}}""",
        )

        assertEquals(101L, assertNotNull(tasks.data).userTaskList.single().userTaskId)
        assertEquals("旧任务", tasks.data.allTasks.single().name)
    }
}
