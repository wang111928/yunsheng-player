package com.litemusic.shared.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import com.litemusic.shared.model.Profile

class EventModelsTest {
    @Test
    fun `eapi session header accepts persisted ntes cookie name`() {
        assertEquals("session", eapiSessionCookie(mapOf("NTES_YD_SESS" to "session")))
        assertEquals("eapi", eapiSessionCookie(mapOf("NTES_YD_SESS" to "web", "ntes_sess" to "eapi")))
    }

    @Test
    fun `maps event json into a post with song and tags`() {
        val post = EventMapper.map(
            EventItem(
                id = 9L,
                user = Profile(userId = 7L, nickname = "云村用户", avatarUrl = "avatar"),
                eventTime = 1_700_000_000_000L,
                json = """
                    {
                      "msg":"我是你的几分之几？",
                      "tags":["今日份听歌打卡"],
                      "song":{"id":123,"name":"几分之几","ar":[{"name":"杜宣达"}],"al":{"picUrl":"cover"}}
                    }
                """.trimIndent(),
            ),
        )

        assertEquals("我是你的几分之几？", post?.content)
        assertEquals(listOf("今日份听歌打卡"), post?.tags)
        assertEquals(123L, post?.songId)
        assertEquals("杜宣达", post?.songArtist)
    }

    @Test
    fun `malformed event json is ignored without throwing`() {
        assertNull(
            EventMapper.map(
                EventItem(
                    id = 10L,
                    user = Profile(userId = 7L, nickname = "云村用户"),
                    json = "{",
                ),
            ),
        )
    }

    @Test
    fun `maps the followed relation from the event author`() {
        val post = EventMapper.map(
            EventItem(
                id = 11L,
                user = Profile(userId = 8L, nickname = "author", followed = true),
                json = "{\"msg\":\"hello\"}",
            ),
        )

        assertEquals(true, post?.followed)
    }

    @Test
    fun `maps legacy song album and artist fields for a song card`() {
        val post = EventMapper.map(
            EventItem(
                id = 12L,
                user = Profile(userId = 8L, nickname = "author"),
                json = """{"msg":"legacy","song":{"id":3,"artists":[{"name":"旧歌手"}],"album":{"picUrl":"legacy-cover"}}}""",
            ),
        )

        assertEquals("旧歌手", post?.songArtist)
        assertEquals("legacy-cover", post?.songCoverUrl)
    }

    @Test
    fun `uses nested dynamic info for engagement and its comment thread`() {
        val response = Json { ignoreUnknownKeys = true }.decodeFromString<EventResponse>(
            """
            {
              "events":[{
                "id":32953014,
                "user":{"userId":6559519868,"nickname":"动态作者"},
                "json":"{\"msg\":\"一条动态\"}",
                "info":{"liked":true,"likedCount":51,"commentCount":17,"threadId":"A_EV_2_6559519868_32953014"}
              }]
            }
            """.trimIndent(),
        )

        val post = EventMapper.map(response.allEvents.single())

        assertEquals(true, post?.liked)
        assertEquals(51, post?.likeCount)
        assertEquals(17, post?.commentCount)
        assertEquals("A_EV_2_6559519868_32953014", post?.commentThreadId)
        assertEquals(
            "A_EV_2_6559519868_32953014",
            eventCommentThreadId(eventId = 32953014, userId = 6559519868, serverThreadId = ""),
        )
    }

    @Test
    fun `keeps root engagement when a feed omits the optional info fields`() {
        val response = Json { ignoreUnknownKeys = true }.decodeFromString<EventResponse>(
            """
            {
              "events":[{
                "id":32953015,
                "user":{"userId":6559519868,"nickname":"动态作者"},
                "json":"{\"msg\":\"根字段动态\"}",
                "liked":true,
                "likedCount":18,
                "commentCount":9,
                "info":{"threadId":"A_EV_2_6559519868_32953015"}
              }]
            }
            """.trimIndent(),
        )

        val post = EventMapper.map(response.allEvents.single())

        assertEquals(true, post?.liked)
        assertEquals(18, post?.likeCount)
        assertEquals(9, post?.commentCount)
    }
}
