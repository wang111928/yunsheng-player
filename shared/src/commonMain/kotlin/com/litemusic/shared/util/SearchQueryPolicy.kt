package com.litemusic.shared.util

/**
 * 搜索输入的纯函数策略：输入框可以保留用户正在编辑的空格，网络请求只使用规范化后的词。
 * 放在 shared 便于单测，也避免 Compose 层在每次重组时重复做字符串处理。
 */
object SearchQueryPolicy {
    const val DEBOUNCE_MS = 350L

    fun normalize(value: String): String = value.trim().replace(Regex("\\s+"), " ")

    fun isSearchable(value: String): Boolean = normalize(value).isNotEmpty()
}
