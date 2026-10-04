package com.litemusic.app.feature.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.litemusic.app.data.SigninRepository
import com.litemusic.shared.model.YunbeiTask
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SigninViewModel(
    private val repo: SigninRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val state: SigninRepository.SigninUiState = SigninRepository.SigninUiState(),
        val tasks: List<YunbeiTask> = emptyList(),
        val signing: Boolean = false,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val s = repo.state()
            val tasks = repo.tasks()
            _state.update {
                it.copy(
                    loading = false,
                    state = s,
                    tasks = if (tasks is AppResult.Success) tasks.data.tasks else emptyList(),
                )
            }
        }
    }

    /** 手动一键双签（不做后台自动签到） */
    fun signin() {
        if (_state.value.signing || _state.value.state.todaySigned) return
        viewModelScope.launch {
            _state.update { it.copy(signing = true) }
            when (val r = repo.signinBoth()) {
                is AppResult.Success -> _state.update {
                    it.copy(signing = false, state = r.data, message = r.data.lastMessage.ifBlank { "签到成功" })
                }
                is AppResult.Failure -> _state.update { it.copy(signing = false, message = r.message) }
            }
        }
    }

    fun finishTask(task: YunbeiTask) {
        viewModelScope.launch {
            when (val r = repo.finishTask(task.userTaskId)) {
                is AppResult.Success -> {
                    _state.update { it.copy(message = "任务完成 +" + task.yunbei + " 云贝") }
                    refresh()
                }
                is AppResult.Failure -> _state.update { it.copy(message = r.message) }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}
