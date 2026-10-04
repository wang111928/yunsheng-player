package com.litemusic.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Categories served by the official daily-style configuration endpoint.
 * `categorys` intentionally follows the server's spelling.
 */
@Serializable
data class DailyStyleConfigResponse(
    val code: Int = 0,
    val data: DailyStyleConfigData? = null,
    val traceId: String? = null,
)

@Serializable
data class DailyStyleConfigData(
    @SerialName("categorys") val categories: List<DailyStyleCategory> = emptyList(),
)

@Serializable
data class DailyStyleCategory(
    val categoryId: Long = 0,
    val categoryName: String = "",
    @SerialName("tagVOList") val tags: List<DailyStyleTag> = emptyList(),
)

@Serializable
data class DailyStyleTag(
    val tagId: Long = 0,
    val tagName: String = "",
    val categoryId: Long = 0,
    /** The daily-style song endpoint marks selections with this field. */
    val isChoose: Boolean = false,
)

/** Official `homepage/category/daily/song/list` response. */
@Serializable
data class DailyStyleSongsResponse(
    val code: Int = 0,
    val data: DailyStyleSongsData? = null,
    val traceId: String? = null,
)

@Serializable
data class DailyStyleSongsData(
    val dailySongs: List<Song> = emptyList(),
    /**
     * Unlike the configuration endpoint, the daily-song endpoint returns one selected
     * category object: { categoryId, categoryName, tagVOList }.  Modelling it as a list
     * makes kotlinx.serialization reject the whole response before songs can be shown.
     */
    val tags: DailyStyleCategory? = null,
)

@Serializable
data class DailyStyleSaveResponse(
    val code: Int = 0,
    val data: DailyStyleSaveData? = null,
)

@Serializable
data class DailyStyleSaveData(val saveSuccess: Boolean = false)
