package com.litemusic.shared.domain

import com.litemusic.shared.api.NMApi
import com.litemusic.shared.model.Song
import com.litemusic.shared.player.QueueItem
import com.litemusic.shared.util.AppResult
import com.litemusic.shared.util.Quality

/** 签到用例：移动端 + PC 端双签 */
class SigninUseCase(private val api: NMApi) {
    data class SigninOutcome(val mobileCode: Int, val pcCode: Int, val message: String)

    suspend fun signinBoth(): AppResult<SigninOutcome> {
        val mobile = api.signin(1)
        val pc = api.signin(0)
        val mOk = mobile is AppResult.Success
        val pOk = pc is AppResult.Success
        if (mOk || pOk) {
            val message = (mobile as? AppResult.Success)?.data?.message
                ?: (pc as? AppResult.Success)?.data?.message
                ?: "签到成功"
            return AppResult.Success(
                SigninOutcome(
                    mobileCode = (mobile as? AppResult.Success)?.data?.code ?: -1,
                    pcCode = (pc as? AppResult.Success)?.data?.code ?: -1,
                    message = message,
                )
            )
        }
        return AppResult.Failure(-1, "签到失败")
    }
}

/** 歌曲转队列项（在线） */
class QueueBuilder {
    fun toQueueItem(
        song: Song,
        quality: Quality,
        description: String? = null,
        commentThreadId: String? = null,
    ): QueueItem = QueueItem(
        id = song.id,
        title = song.name,
        artist = song.artistNames,
        album = song.albumName,
        coverUrl = song.coverUrl.ifEmpty { null },
        durationMs = song.dt,
        description = description?.trim()?.takeIf { it.isNotEmpty() },
        commentThreadId = commentThreadId?.trim()?.takeIf { it.isNotEmpty() },
        quality = quality,
    )

}

/** 音质路由：网速下降自动降一档、恢复后切回 */
class QualityRouter(private val isVip: () -> Boolean) {
    data class Route(val requested: Quality, val effective: Quality)

    fun initial(): Quality = Quality.maxAvailable(isVip())

    fun onWeakNetwork(current: Quality): Quality = current.degrade() ?: current

    fun onRecover(current: Quality, wanted: Quality): Quality = wanted
}
