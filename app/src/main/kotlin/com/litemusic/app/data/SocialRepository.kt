package com.litemusic.app.data

import com.litemusic.shared.api.NMApi
import com.litemusic.shared.api.EventMapper
import com.litemusic.shared.api.EventPost
import com.litemusic.shared.api.EventResponse
import com.litemusic.shared.api.FollowListResponse
import com.litemusic.shared.model.Playlist
import com.litemusic.shared.model.Profile
import com.litemusic.shared.model.UserPlaylistResponse
import com.litemusic.shared.util.AppResult

data class SocialPage(
    val users: List<Profile>,
    val more: Boolean,
    val nextOffset: Int,
)

data class UserEventPage(
    val posts: List<EventPost>,
    val more: Boolean,
    val nextTime: Long,
)

/**
 * The API client can successfully decode a response whose business request failed.
 * Do not turn such responses into an empty social list: the screen must get a failure
 * and leave its already-visible rows intact.
 */
internal fun followsPageForUi(
    result: AppResult<FollowListResponse>,
    offset: Int,
): AppResult<SocialPage> = when (result) {
    is AppResult.Failure -> result
    is AppResult.Success -> {
        val response = result.data
        if (response.code != 200) {
            AppResult.Failure(response.code, "关注列表读取失败(${response.code})")
        } else {
            AppResult.Success(
                SocialPage(
                    users = response.follow,
                    more = response.more,
                    nextOffset = offset + response.follow.size,
                ),
            )
        }
    }
}

internal fun followedsPageForUi(
    result: AppResult<FollowListResponse>,
    offset: Int,
): AppResult<SocialPage> = when (result) {
    is AppResult.Failure -> result
    is AppResult.Success -> {
        val response = result.data
        if (response.code != 200) {
            AppResult.Failure(response.code, "粉丝列表读取失败(${response.code})")
        } else {
            AppResult.Success(
                SocialPage(
                    users = response.followeds,
                    more = response.more,
                    nextOffset = offset + response.followeds.size,
                ),
            )
        }
    }
}

internal fun userPlaylistsForUi(
    result: AppResult<UserPlaylistResponse>,
): AppResult<List<Playlist>> = when (result) {
    is AppResult.Failure -> result
    is AppResult.Success -> {
        val response = result.data
        if (response.code != 200) {
            AppResult.Failure(response.code, "用户歌单读取失败(${response.code})")
        } else {
            AppResult.Success(response.playlist)
        }
    }
}

internal fun userEventsForUi(
    result: AppResult<EventResponse>,
    requestedTime: Long = -1L,
): AppResult<UserEventPage> = when (result) {
    is AppResult.Failure -> result
    is AppResult.Success -> {
        val response = result.data
        if (response.code != 200) {
            AppResult.Failure(response.code, "用户动态读取失败(${response.code})")
        } else {
            AppResult.Success(
                UserEventPage(
                    posts = response.allEvents.mapNotNull(EventMapper::map),
                    more = response.more,
                    nextTime = eventPageCursor(response, requestedTime),
                ),
            )
        }
    }
}

/** 关注 / 粉丝 / 用户主页 */
class SocialRepository(
    private val api: NMApi,
    private val auth: AuthRepository,
) {
    suspend fun follows(uid: Long, offset: Int = 0, limit: Int = 30): AppResult<SocialPage> =
        followsPageForUi(api.getFollows(uid, offset, limit), offset)

    suspend fun followeds(uid: Long, offset: Int = 0, limit: Int = 30): AppResult<SocialPage> =
        followedsPageForUi(api.getFolloweds(uid, offset, limit), offset)

    suspend fun follow(uid: Long, follow: Boolean) = api.follow(uid, follow)

    suspend fun userDetail(uid: Long) = api.getUserDetail(uid)

    suspend fun userPlaylists(uid: Long, offset: Int = 0, limit: Int = 30) =
        userPlaylistsForUi(api.getUserPlaylists(uid, offset, limit))

    suspend fun userEvents(uid: Long, time: Long = -1L, limit: Int = 20): AppResult<UserEventPage> =
        userEventsForUi(api.userEvents(uid, time, limit), time)

    suspend fun me(): Long = auth.ensureUserId()
}
