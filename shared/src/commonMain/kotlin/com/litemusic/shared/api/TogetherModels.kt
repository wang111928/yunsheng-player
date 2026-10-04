package com.litemusic.shared.api

import kotlinx.serialization.Serializable

@Serializable
data class TogetherStatusResponse(
    val code: Int = 0,
    val message: String = "",
    val data: TogetherStatusData? = null,
)

@Serializable
data class TogetherStatusData(
    val inRoom: Boolean = false,
    val roomInfo: TogetherRoomInfo? = null,
    val anotherDeviceInfo: TogetherDeviceInfo? = null,
    val anotherFollowStatus: Boolean? = null,
    val status: String = "",
)

@Serializable
data class TogetherRoomInfo(
    val creatorId: Long = 0,
    val roomId: String = "",
    val chatRoomId: String = "",
    val agoraChannelId: String = "",
    val roomUsers: List<TogetherRoomUser> = emptyList(),
    val roomRTCType: String = "",
    val roomType: String = "",
)

@Serializable
data class TogetherRoomUser(
    val userId: Long = 0,
    val nickname: String = "",
    val avatarUrl: String? = null,
)

@Serializable
data class TogetherDeviceInfo(
    val antherUserId: Long = 0,
    val osType: String = "",
    val appVersion: String = "",
)

@Serializable
data class TogetherActionResponse(
    val code: Int = 0,
    val message: String = "",
    val data: TogetherActionData? = null,
)

@Serializable
data class TogetherActionData(
    val result: Boolean? = null,
)
