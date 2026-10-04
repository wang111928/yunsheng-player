package com.litemusic.shared.util

/** 统一结果包装：网络层/仓储层使用，UI 层展示错误信息 */
sealed class AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>()
    data class Failure(val code: Int, val message: String, val cause: Throwable? = null) : AppResult<Nothing>()

    fun <R> map(transform: (T) -> R): AppResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }
}

fun <T> T.asSuccess(): AppResult<T> = AppResult.Success(this)
fun Throwable.asFailure(code: Int = -1): AppResult<Nothing> =
    AppResult.Failure(code, message ?: "网络错误", this)
