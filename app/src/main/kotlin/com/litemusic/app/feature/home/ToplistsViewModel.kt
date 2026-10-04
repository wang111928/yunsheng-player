package com.litemusic.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.litemusic.app.data.HomeRepository
import com.litemusic.shared.model.Playlist
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A dedicated chart request must not depend on the homepage aggregate response: that response
 * intentionally keeps only a small home rail and can fail when unrelated recommendations fail.
 */
class ToplistsViewModel(
    private val repository: HomeRepository,
) : ViewModel() {
    data class UiState(
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val charts: List<Playlist> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var request: Job? = null

    init {
        load()
    }

    fun load(forceRefresh: Boolean = false) {
        request?.cancel()
        request = viewModelScope.launch {
            _state.update { current ->
                current.copy(
                    loading = current.charts.isEmpty(),
                    refreshing = forceRefresh,
                    error = null,
                )
            }
            when (val result = repository.toplistDetail(forceRefresh)) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        charts = result.data.filter { chart -> chart.id > 0L }.distinctBy { chart -> chart.id },
                    )
                }
                is AppResult.Failure -> _state.update {
                    it.copy(loading = false, refreshing = false, error = result.message)
                }
            }
        }
    }
}
