package com.litemusic.app.feature.home

/**
 * 首页顶部频道（对齐网易云手机端）：心动 / 推荐 / 音乐 / 播客 / 听书。
 * 频道分别读取心动智能续播、推荐歌曲与歌单、音乐榜单、播客电台及有声书。
 */
enum class HomeChannel(val label: String) {
    HEARTTHROB("心动"),
    RECOMMEND("推荐"),
    MUSIC("音乐"),
    PODCAST("播客"),
    AUDIOBOOK("听书"),
}

/** 顶部频道顺序（截图从左到右）。 */
fun homeChannels(): List<HomeChannel> = HomeChannel.entries.toList()

/** 默认选中频道：推荐。 */
fun defaultHomeChannel(): HomeChannel = HomeChannel.RECOMMEND

/** 该频道是否有真实数据源。 */
fun HomeChannel.hasRealFeed(): Boolean = true

/** 首页金刚区快捷分类。个人资料入口由底部「我的」承载，避免重复。 */
enum class HomeQuickEntry(val label: String) {
    DAILY("每日推荐"),
    PLAYLISTS("歌单广场"),
    TOPLIST("排行榜"),
    FM("私人FM"),
}

fun homeQuickEntries(): List<HomeQuickEntry> = HomeQuickEntry.entries.toList()

/**
 * 推荐歌单刷新节流：距上次刷新超过 [minIntervalMs] 才允许再次拉取，避免从二三级页
 * 返回时反复发请求。冷启动 [lastRefreshAtMs] = 0 时必然刷新（满足「重开重新加载」）。
 */
fun shouldRefreshPlaylists(
    lastRefreshAtMs: Long,
    nowMs: Long,
    minIntervalMs: Long = 60_000L,
    force: Boolean = false,
): Boolean = force || lastRefreshAtMs <= 0L || nowMs - lastRefreshAtMs >= minIntervalMs

/**
 * Tracks request generations so a response from an older pull-to-refresh cannot replace newer
 * content. Channel generations are deliberately independent: rapidly switching from podcast to
 * audiobook must not allow either channel's previous request to overwrite the other.
 */
class HomeRefreshGenerations {
    private var homeGeneration = 0L
    private var homeSongGeneration = 0L
    private val channelGenerations = mutableMapOf<HomeChannel, Long>()

    fun nextHomeRequest(): Long = ++homeGeneration

    fun isCurrentHomeRequest(generation: Long): Boolean = generation == homeGeneration

    fun nextHomeSongRequest(): Long = ++homeSongGeneration

    fun isCurrentHomeSongRequest(generation: Long): Boolean = generation == homeSongGeneration

    fun nextChannelRequest(channel: HomeChannel): Long {
        val next = (channelGenerations[channel] ?: 0L) + 1L
        channelGenerations[channel] = next
        return next
    }

    fun isCurrentChannelRequest(channel: HomeChannel, generation: Long): Boolean =
        channelGenerations[channel] == generation
}
