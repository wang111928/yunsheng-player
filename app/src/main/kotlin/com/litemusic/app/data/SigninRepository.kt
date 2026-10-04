package com.litemusic.app.data

import com.litemusic.data.db.SigninHistoryDao
import com.litemusic.data.db.SigninHistoryEntity
import com.litemusic.data.prefs.SettingsStore
import com.litemusic.shared.api.NMApi
import com.litemusic.shared.model.YunbeiSigninResponse
import com.litemusic.shared.model.YunbeiTask
import com.litemusic.shared.model.YunbeiTaskListResponse
import com.litemusic.shared.model.YunbeiTaskResponse
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun isSuccessfulSignin(result: AppResult<com.litemusic.shared.model.SigninResult>): Boolean =
    result is AppResult.Success && result.data.code == 200

/**
 * A successful write only proves that the endpoint accepted the request.  We show
 * a successful cloud sign-in only after the account and reward snapshots have
 * both been read back and a reward field actually changed.
 */
internal data class YunbeiRemoteSnapshot(
    val days: Int = 0,
    val signedShells: Int = 0,
    val todayShells: Int = 0,
)

internal data class YunbeiSigninDecision(
    val accepted: Boolean,
    val message: String,
)

internal data class ModernYunbeiRemoteSnapshot(
    val signed: Boolean? = null,
    val yunbei: Int? = null,
    val completedTaskIds: Set<Long> = emptySet(),
    val tasks: List<YunbeiTask> = emptyList(),
)

/**
 * A modern response's `data.sign=true` is explicit server confirmation.  Older
 * response variants must prove the write via a fresh cloud-shell/task snapshot.
 */
internal fun decideModernYunbeiSignin(
    signCode: Int,
    responseSigned: Boolean?,
    accountBefore: Long,
    accountAfter: Long,
    before: ModernYunbeiRemoteSnapshot?,
    after: ModernYunbeiRemoteSnapshot?,
): YunbeiSigninDecision {
    if (signCode != 200) return YunbeiSigninDecision(false, "云贝签到失败($signCode)")
    if (accountBefore <= 0L || accountBefore != accountAfter) {
        return YunbeiSigninDecision(false, "账号状态已变化，签到结果未写入")
    }
    if (responseSigned == true) return YunbeiSigninDecision(true, "云贝签到已由网易云确认")
    if (before == null || after == null) return YunbeiSigninDecision(false, "远端签到状态读取失败")
    val rewardChanged = before.yunbei != null && after.yunbei != null && before.yunbei != after.yunbei
    val taskChanged = after.completedTaskIds - before.completedTaskIds
    val signedReadBack = after.signed == true && before.signed != true
    return when {
        signedReadBack -> YunbeiSigninDecision(true, "云贝签到已由网易云状态确认")
        rewardChanged -> YunbeiSigninDecision(true, "云贝签到后云贝余额已更新")
        taskChanged.isNotEmpty() -> YunbeiSigninDecision(true, "云贝签到后任务状态已更新")
        else -> YunbeiSigninDecision(false, "云贝签到后远端状态未变化，无法确认")
    }
}

/** Only explicit endpoint-not-supported business responses can use the legacy fallback. */
internal fun isModernYunbeiEndpointUnsupported(result: AppResult<YunbeiSigninResponse>): Boolean =
    result is AppResult.Success && result.data.code in setOf(404, 405, 501)

internal fun decideYunbeiSignin(
    signCode: Int,
    accountBefore: Long,
    accountAfter: Long,
    before: YunbeiRemoteSnapshot?,
    after: YunbeiRemoteSnapshot?,
): YunbeiSigninDecision {
    if (signCode != 200) return YunbeiSigninDecision(false, "云贝签到失败($signCode)")
    if (accountBefore <= 0L || accountBefore != accountAfter) {
        return YunbeiSigninDecision(false, "账号状态已变化，签到结果未写入")
    }
    if (before == null || after == null) return YunbeiSigninDecision(false, "远端签到状态读取失败")
    val changed = before.days != after.days || before.signedShells != after.signedShells || before.todayShells != after.todayShells
    return if (changed) {
        YunbeiSigninDecision(true, "云贝签到接口成功，远端奖励字段已更新")
    } else {
        YunbeiSigninDecision(false, "云贝签到后远端奖励状态未变化，无法确认")
    }
}

/** The cloud task list is the only persisted source we use to render today's state. */
internal fun isRemoteSigninTaskComplete(tasks: List<YunbeiTask>): Boolean =
    tasks.any { task -> task.done && task.name.contains("签到") }

/** Maps current task-list fields onto the long-lived task presentation model. */
internal fun normalizeModernYunbeiTask(task: YunbeiTask): YunbeiTask = task.copy(
    userTaskId = task.userTaskId.takeIf { it != 0L } ?: task.taskId,
    name = task.taskName.ifBlank { task.name },
    // `completed` is the current endpoint's authoritative flag.  Older
    // revisions have only done/status, where 2 represents a completed task.
    done = task.completed ?: (task.done || task.status == 2),
    yunbei = task.taskPoint ?: task.completedPoint ?: task.yunbei,
)

/**
 * Maps the current cloud-shell task response to the model used by the sign-in
 * screen.  A null result is reserved for explicit endpoint-not-supported
 * responses so network and server errors remain visible to the UI.
 */
internal fun modernYunbeiTasksForUi(
    result: AppResult<YunbeiTaskListResponse>,
): AppResult<YunbeiTaskResponse>? = when (result) {
    is AppResult.Failure -> result
    is AppResult.Success -> when {
        result.data.code in setOf(404, 405, 501) -> null
        result.data.code != 200 -> AppResult.Failure(
            result.data.code,
            "云贝任务列表读取失败(${result.data.code})",
        )
        else -> result.data.data?.let { data ->
            AppResult.Success(
                YunbeiTaskResponse(
                    code = result.data.code,
                    tasks = data.allTasks.map(::normalizeModernYunbeiTask),
                ),
            )
        } ?: AppResult.Failure(-1, "云贝任务列表数据缺失")
    }
}

/**
 * 签到：移动端 + PC 端双签（双倍经验），连续天数 / 云贝任务 / 历史记录。
 */
class SigninRepository(
    private val api: NMApi,
    private val settings: SettingsStore,
    private val historyDao: SigninHistoryDao,
) {
    data class SigninUiState(
        /** This is derived from the current server task list, never local history. */
        val todaySigned: Boolean = false,
        val remoteStatusAvailable: Boolean = false,
        val streak: Int = 0,
        val total: Int = 0,
        val yunbei: Int = 0,
        val lastMessage: String = "",
    )

    suspend fun state(): SigninUiState {
        val remote = modernRemoteSnapshot()
        return SigninUiState(
            todaySigned = remote?.signed == true || remote?.tasks?.let(::isRemoteSigninTaskComplete) == true,
            remoteStatusAvailable = remote != null,
            streak = settings.signinStreak.first(),
            total = settings.totalSignin.first(),
            yunbei = remote?.yunbei ?: 0,
        )
    }

    /** Modern cloud-shell sign-in first; legacy double sign is only a server-declared fallback. */
    suspend fun signinBoth(): AppResult<SigninUiState> {
        val accountBefore = accountId()
        val before = modernRemoteSnapshot()
        val modern = api.modernYunbeiSignin()
        if (modern is AppResult.Success && modern.data.code == 200) {
            val decision = decideModernYunbeiSignin(
                signCode = modern.data.code,
                responseSigned = modern.data.data?.sign,
                accountBefore = accountBefore,
                accountAfter = accountId(),
                before = before,
                after = modernRemoteSnapshot(),
            )
            if (!decision.accepted) return AppResult.Failure(-1, decision.message)
            return persistConfirmedSignin(
                mobileCode = modern.data.code,
                pcCode = modern.data.code,
                points = 0,
                message = modern.data.message.ifBlank { decision.message },
            )
        }
        if (!isModernYunbeiEndpointUnsupported(modern)) {
            val message = when (modern) {
                is AppResult.Success -> modern.data.message.ifBlank { "云贝签到失败(${modern.data.code})" }
                is AppResult.Failure -> modern.message
            }
            return AppResult.Failure(-1, message)
        }

        // Some old servers do not publish the point-mall endpoint.  Do not use
        // this branch for network failures or ambiguous modern responses.
        return legacySigninBoth(accountBefore)
    }

    private suspend fun legacySigninBoth(accountBefore: Long): AppResult<SigninUiState> {
        val before = remoteSnapshot()
        val mobile = api.signin(1)
        val pc = api.signin(0)
        val mobileOk = isSuccessfulSignin(mobile)
        val pcOk = isSuccessfulSignin(pc)
        val ok = mobileOk || pcOk
        if (ok) {
            val decision = decideYunbeiSignin(
                signCode = 200,
                accountBefore = accountBefore,
                accountAfter = accountId(),
                before = before,
                after = remoteSnapshot(),
            )
            if (!decision.accepted) return AppResult.Failure(-1, decision.message)
            val points = (mobile as? AppResult.Success)?.data?.takeIf { it.code == 200 }?.point ?: 0
            val m = (mobile as? AppResult.Success)?.data?.takeIf { it.code == 200 }?.message
                ?: (pc as? AppResult.Success)?.data?.takeIf { it.code == 200 }?.message ?: "签到成功"
            return persistConfirmedSignin(
                mobileCode = (mobile as? AppResult.Success)?.data?.code ?: -1,
                pcCode = (pc as? AppResult.Success)?.data?.code ?: -1,
                points = points,
                message = m.ifBlank { decision.message },
            )
        }
        val mobileMessage = when (mobile) {
            is AppResult.Success -> mobile.data.message.ifBlank { "移动端签到失败(${mobile.data.code})" }
            is AppResult.Failure -> mobile.message
        }
        val pcMessage = when (pc) {
            is AppResult.Success -> pc.data.message.ifBlank { "PC 端签到失败(${pc.data.code})" }
            is AppResult.Failure -> pc.message
        }
        return AppResult.Failure(-1, mobileMessage.ifBlank { pcMessage.ifBlank { "签到失败" } })
    }

    suspend fun tasks(): AppResult<YunbeiTaskResponse> =
        modernYunbeiTasksForUi(api.yunbeiTaskListAll()) ?: api.yunbeiTasks()

    suspend fun finishTask(userTaskId: Long) = api.finishYunbeiTask(userTaskId)

    suspend fun history() = historyDao.recent(30)

    private suspend fun persistConfirmedSignin(
        mobileCode: Int,
        pcCode: Int,
        points: Int,
        message: String,
    ): AppResult<SigninUiState> {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        settings.recordSignin(today, points)
        historyDao.upsert(
            SigninHistoryEntity(
                date = today,
                mobileCode = mobileCode,
                pcCode = pcCode,
                points = points,
                ts = System.currentTimeMillis(),
            ),
        )
        return AppResult.Success(state().copy(todaySigned = true, lastMessage = message))
    }

    private suspend fun accountId(): Long =
        (api.getAccount() as? AppResult.Success)
            ?.data
            ?.takeIf { it.code == 200 }
            ?.account
            ?.id
            ?: 0L

    private suspend fun remoteSnapshot(): YunbeiRemoteSnapshot? {
        val balance = api.yunbeiBalance() as? AppResult.Success ?: return null
        val tasks = api.yunbeiTasks() as? AppResult.Success ?: return null
        if (balance.data.code != 200 || tasks.data.code != 200) return null
        val completed = tasks.data.tasks.filter { it.done }
        return YunbeiRemoteSnapshot(
            days = completed.size,
            signedShells = balance.data.yunbei,
            todayShells = completed.sumOf { it.yunbei },
        )
    }

    private suspend fun modernRemoteSnapshot(): ModernYunbeiRemoteSnapshot? {
        val info = api.yunbeiUserInfo() as? AppResult.Success ?: return null
        val tasks = api.yunbeiTaskListAll() as? AppResult.Success ?: return null
        if (info.data.code != 200 || tasks.data.code != 200) return null
        val taskList = tasks.data.data?.allTasks.orEmpty().map(::normalizeModernYunbeiTask)
        val rootHasSignFields = info.data.mobileSign != null || info.data.pcSign != null
        return ModernYunbeiRemoteSnapshot(
            signed = if (rootHasSignFields) {
                info.data.mobileSign == true || info.data.pcSign == true
            } else {
                info.data.data?.sign
            },
            yunbei = info.data.userPoint?.balance
                ?: info.data.data?.yunbeiNum
                ?: info.data.data?.yunbei,
            completedTaskIds = taskList.filter { it.done }.map { it.userTaskId }.toSet(),
            tasks = taskList,
        )
    }
}
