package com.litemusic.app.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.litemusic.app.data.SearchRepository
import com.litemusic.shared.model.HotWord
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun shouldApplySearchResponse(
    responseGeneration: Long,
    activeGeneration: Long,
    responseKeyword: String,
    currentKeyword: String,
): Boolean = responseGeneration == activeGeneration && responseKeyword.trim() == currentKeyword.trim()

/**
 * 搜索状态：单曲 / 歌手 / 歌单 / 用户四类分组结果 + 热门 + 历史。
 *
 * 输入 400ms 防抖后一次性并发拉取四类（[SearchRepository.searchAll]），
 * 结果页按 [SearchSection] 分组展示，可逐组展开 / 收起。
 */
@OptIn(FlowPreview::class)
class SearchViewModel(
    private val repo: SearchRepository,
) : ViewModel() {

    data class UiState(
        val keyword: String = "",
        val searching: Boolean = false,
        val hots: List<HotWord> = emptyList(),
        val history: List<String> = emptyList(),
        val results: SearchRepository.SearchBundle? = null,
        val expanded: Set<SearchSection> = emptySet(),
        val loadingMore: Set<SearchSection> = emptySet(),
        val loadMoreError: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val query = MutableStateFlow("")
    private var queryGeneration = 0L

    init {
        viewModelScope.launch {
            query
                .debounce(400)
                .distinctUntilChanged()
                .collect { kw ->
                    if (kw.isBlank()) {
                        _state.update { it.copy(results = null, searching = false, error = null) }
                    } else {
                        doSearch(kw)
                    }
                }
        }
        loadHot()
        loadHistory()
    }

    fun setKeyword(kw: String) {
        if (_state.value.keyword != kw) queryGeneration += 1L
        _state.update { it.copy(keyword = kw) }
        query.value = kw
    }

    /** 展开 / 收起某个结果分组。 */
    fun toggleSection(section: SearchSection) {
        _state.update {
            val next = if (section in it.expanded) it.expanded - section else it.expanded + section
            it.copy(expanded = next)
        }
    }

    /** Requests the next server page for only the selected result category. */
    fun loadMore(section: SearchSection) {
        val snapshot = _state.value
        val keyword = snapshot.keyword.trim()
        val current = snapshot.results ?: return
        if (keyword.isBlank() || section !in snapshot.expanded || !sectionHasMore(current, section) ||
            section in snapshot.loadingMore
        ) return
        val offset = when (section) {
            SearchSection.SONG -> current.songs.size
            SearchSection.ARTIST -> current.artists.size
            SearchSection.PLAYLIST -> current.playlists.size
            SearchSection.USER -> current.users.size
        }
        val type = when (section) {
            SearchSection.SONG -> SearchRepository.Type.SONG
            SearchSection.ARTIST -> SearchRepository.Type.ARTIST
            SearchSection.PLAYLIST -> SearchRepository.Type.PLAYLIST
            SearchSection.USER -> SearchRepository.Type.USER
        }
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = it.loadingMore + section, loadMoreError = null) }
            when (val result = repo.search(keyword, type, offset)) {
                is AppResult.Success -> _state.update { state ->
                    if (state.keyword.trim() != keyword) state
                    else state.copy(
                        results = appendSearchPage(state.results ?: current, section, result.data),
                        loadingMore = state.loadingMore - section,
                    )
                }
                is AppResult.Failure -> _state.update { state ->
                    if (state.keyword.trim() != keyword) state
                    else state.copy(loadingMore = state.loadingMore - section, loadMoreError = result.message)
                }
            }
        }
    }

    private fun doSearch(kw: String) {
        val generation = queryGeneration
        viewModelScope.launch {
            _state.update {
                if (shouldApplySearchResponse(generation, queryGeneration, kw, it.keyword)) {
                    it.copy(searching = true, error = null)
                } else it
            }
            when (val r = repo.searchAll(kw)) {
                is AppResult.Success -> _state.update {
                    if (shouldApplySearchResponse(generation, queryGeneration, kw, it.keyword)) {
                        it.copy(
                            results = r.data,
                            searching = false,
                            error = null,
                            expanded = emptySet(),
                            loadingMore = emptySet(),
                        )
                    } else it
                }
                is AppResult.Failure -> _state.update {
                    if (shouldApplySearchResponse(generation, queryGeneration, kw, it.keyword)) {
                        it.copy(searching = false, error = r.message)
                    } else it
                }
            }
        }
    }

    fun commitKeyword() {
        val kw = _state.value.keyword.trim()
        if (kw.isBlank()) return
        viewModelScope.launch {
            repo.addHistory(kw)
            loadHistory()
        }
    }

    fun removeHistory(kw: String) {
        viewModelScope.launch {
            repo.removeHistory(kw)
            loadHistory()
        }
    }

    private fun loadHot() {
        viewModelScope.launch {
            val r = repo.hot()
            if (r is AppResult.Success) {
                _state.update { it.copy(hots = r.data.result?.hots ?: emptyList()) }
            }
        }
    }

    private fun loadHistory() {
        viewModelScope.launch {
            _state.update { it.copy(history = repo.history()) }
        }
    }
}
