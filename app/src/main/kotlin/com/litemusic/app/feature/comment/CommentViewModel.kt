package com.litemusic.app.feature.comment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.litemusic.app.data.CommentRepository
import com.litemusic.shared.model.Comment
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CommentViewModel(
    private val repo: CommentRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val error: String? = null,
        val hot: List<Comment> = emptyList(),
        val newest: List<Comment> = emptyList(),
        val total: Long = 0,
        val hasMore: Boolean = false,
        val nextOffset: Int = 0,
        val loadingMore: Boolean = false,
        val moreError: String? = null,
        val input: String = "",
        val replyTo: Comment? = null,
        val submitting: Boolean = false,
        val toast: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var target: CommentTarget? = null
    private var generation = 0L

    fun load(target: CommentTarget) {
        val sameTarget = this.target == target
        this.target = target
        val requestGeneration = ++generation
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = true,
                    loadingMore = false,
                    error = null,
                    moreError = null,
                    hasMore = if (sameTarget) it.hasMore else false,
                    nextOffset = if (sameTarget) it.nextOffset else 0,
                    hot = if (sameTarget) it.hot else emptyList(),
                    newest = if (sameTarget) it.newest else emptyList(),
                    total = if (sameTarget) it.total else 0,
                    replyTo = if (sameTarget) it.replyTo else null,
                    input = if (sameTarget) it.input else "",
                    submitting = if (sameTarget) it.submitting else false,
                    toast = if (sameTarget) it.toast else null,
                )
            }
            when (val r = repo.loadThread(target.threadId)) {
                is AppResult.Success -> _state.update {
                    if (requestGeneration != generation) return@update it
                    it.copy(
                        loading = false,
                        hot = r.data.hot,
                        newest = r.data.newest,
                        total = r.data.total,
                        hasMore = r.data.hasMore && r.data.newest.isNotEmpty(),
                        nextOffset = r.data.newest.size,
                    )
                }
                is AppResult.Failure -> _state.update {
                    if (requestGeneration != generation) it else it.copy(loading = false, error = r.message)
                }
            }
        }
    }

    fun loadMore() {
        val current = _state.value
        val target = target ?: return
        if (current.loading || current.loadingMore || !current.hasMore || current.moreError != null) return
        val requestGeneration = generation
        val offset = current.nextOffset
        val requestedTarget = target
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            when (val result = repo.loadThread(requestedTarget.threadId, offset)) {
                is AppResult.Success -> _state.update { state ->
                    if (requestGeneration != generation) return@update state
                    val page = result.data.newest
                    val merged = (state.newest + page).distinctBy { it.commentId }
                    state.copy(
                        newest = merged,
                        total = result.data.total,
                        nextOffset = offset + page.size,
                        hasMore = result.data.hasMore && page.isNotEmpty() && merged.size > state.newest.size,
                        loadingMore = false,
                        moreError = null,
                    )
                }
                is AppResult.Failure -> _state.update { state ->
                    if (requestGeneration != generation) state else state.copy(loadingMore = false, moreError = result.message)
                }
            }
        }
    }

    fun retryMore() {
        _state.update { it.copy(moreError = null) }
        loadMore()
    }

    fun setInput(v: String) = _state.update { it.copy(input = v) }

    fun replyTo(comment: Comment) = _state.update { it.copy(replyTo = comment) }

    fun cancelReply() = _state.update { it.copy(replyTo = null) }

    fun post() {
        val content = _state.value.input.trim()
        val target = target ?: return
        if (content.isBlank() || _state.value.submitting) return
        val replyTo = _state.value.replyTo
        val submittedGeneration = generation
        _state.update { it.copy(submitting = true) }
        viewModelScope.launch {
            val result = if (replyTo == null) {
                repo.postThread(target.threadId, content)
            } else {
                repo.replyThread(target.threadId, replyTo.commentId, content)
            }
            if (submittedGeneration != generation || this@CommentViewModel.target != target) return@launch
            when (result) {
                is AppResult.Success -> {
                    if (result.data.code == 200) {
                        _state.update { it.copy(submitting = false, input = "", replyTo = null, toast = if (replyTo == null) "评论成功" else "回复成功") }
                        if (this@CommentViewModel.target == target) load(target)
                    } else {
                        _state.update { it.copy(submitting = false, toast = "${if (replyTo == null) "评论" else "回复"}失败(${result.data.code})") }
                    }
                }
                is AppResult.Failure -> _state.update {
                    it.copy(submitting = false, toast = result.message)
                }
            }
        }
    }

    fun like(comment: Comment) {
        val target = target ?: return
        viewModelScope.launch {
            repo.likeThread(target.threadId, comment.commentId, !comment.liked)
            // 乐观更新
            _state.update { s ->
                s.copy(
                    hot = s.hot.map { c -> if (c.commentId == comment.commentId) c.copy(liked = !c.liked, likedCount = c.likedCount + if (c.liked) -1 else 1) else c },
                    newest = s.newest.map { c -> if (c.commentId == comment.commentId) c.copy(liked = !c.liked, likedCount = c.likedCount + if (c.liked) -1 else 1) else c },
                )
            }
        }
    }

    fun toastShown() = _state.update { it.copy(toast = null) }

    fun isScam(comment: Comment): Boolean = repo.isScam(comment)
}
