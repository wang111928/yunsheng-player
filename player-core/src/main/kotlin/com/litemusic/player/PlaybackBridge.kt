package com.litemusic.player

import com.litemusic.shared.player.PlayerStateMachine
import com.litemusic.shared.player.QueueItem
import kotlinx.coroutines.flow.StateFlow

/** 播放服务桥（App 装配）：URL 解析 + 状态机注入 */
interface PlaybackBridge {
    val stateMachine: PlayerStateMachine
    /** 解析播放地址；[forceRefresh] 为 true 时忽略并清除缓存，强制向服务端重取 */
    suspend fun resolveUrl(item: QueueItem, forceRefresh: Boolean = false): String
    val favoriteIds: StateFlow<Set<Long>>? get() = null
    /** User-initiated only. Local files have no server-side favourite identity. */
    suspend fun setFavorite(item: QueueItem, liked: Boolean): Boolean = false
}

/**
 * 锁屏歌词控制器（app 层实现，Service 在息屏/解锁时回调）。
 *
 * 为什么不用 MediaSession 元数据：已核实 Media3 1.6.1 与 Android 16(API 36) 的
 * MediaMetadata / MediaMetadataCompat 均无歌词字段，OriginOS 锁屏歌词由 vivo 私有协议
 * 提供：设备端 com.vivo.musicwidgetmix / com.vivo.musicmixcard 只接四家 AIDL
 * （netease ICMApi / tencent IQQMusicApi / kugou IKGMapApi / bili BiliMediaSessionController），
 * 且服务权限为 signature|privileged + 云端白名单，第三方包名无法接入。
 *
 * 因此锁屏歌词由 App 自绘，投递路径为**通知的全屏意图**（setFullScreenIntent）：
 * 由 system_server 自己发起 Activity 启动，不受 BAL 与 keyguard 拦截（来电/闹钟同通道）；
 * 悬浮窗（TYPE_APPLICATION_OVERLAY）与普通 startActivity 在 OriginOS 6 锁屏期均被系统压制（已实测）。
 */
interface LockScreenLyricController {
    /** 息屏时调用（是否真的显示由实现按开关判断） */
    fun onScreenOff() {}
    /** 亮屏时调用：锁屏未解开则投递全屏意图通知，让歌词页盖在锁屏上 */
    fun onScreenOn() {}
    /** 用户解锁/出现时调用 */
    fun onUserPresent() {}
}
