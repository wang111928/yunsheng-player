package com.litemusic.shared.util

import androidx.compose.runtime.Immutable

/** 音质档位：标准 128K / 较高 192K / 极高 320K / 无损 FLAC */
@Immutable
enum class Quality(val label: String, val br: Int, val code: String) {
    STANDARD("标准", 128000, "128K"),
    HIGH("较高", 192000, "192K"),
    EXHIGH("极高", 320000, "320K"),
    LOSSLESS("无损", 999000, "FLAC");

    fun degrade(): Quality? = when (this) {
        LOSSLESS -> EXHIGH
        EXHIGH -> HIGH
        HIGH -> STANDARD
        STANDARD -> null
    }

    companion object {
        fun maxAvailable(isVip: Boolean): Quality = if (isVip) LOSSLESS else EXHIGH
    }
}

