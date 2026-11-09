package com.fitcoach.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fitcoach.app.FitCoachApp
import com.fitcoach.app.data.HealthConnectRepository.Availability
import com.fitcoach.app.data.HealthSyncRepository
import com.fitcoach.app.work.DailyAdviceWorker
import com.fitcoach.engine.DailyMetrics
import com.fitcoach.engine.SampleData
import com.fitcoach.engine.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed interface SyncStatus {
    /** 正在后台同步，界面上先显示的是缓存数据算出的结果。 */
    data object Syncing : SyncStatus
    data class Done(val mode: HealthSyncRepository.Mode, val changedDays: Int, val durationMs: Long) : SyncStatus
    data class Failed(val message: String) : SyncStatus
}

/** 自评卡片要问的问题。早上问"今天感觉如何"和"昨天练得多累"；下午以后问"今天练得多累"。 */
data class FeedbackPrompt(
    /** 为 null 时不问早晨状态（建议是给明天的，今天早上的状态已经不影响了）。 */
    val wellnessDate: LocalDate?,
    val wellness: Int?,
    val rpeDate: LocalDate,
    val rpe: Int?,
)

sealed interface UiState {
    data class Loading(val message: String? = null) : UiState
    data class HealthConnectMissing(val updateRequired: Boolean) : UiState
    data object NeedsPermission : UiState
    data class Ready(
        val advice: FitCoachApp.Advice,
        /** null 表示真实数据，否则为演示场景。 */
        val demoScenario: SampleData.Scenario?,
        val sync: SyncStatus? = null,
        val feedback: FeedbackPrompt? = null,
    ) : UiState
    data class Error(val message: String) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val fitApp = app as FitCoachApp
    val healthConnect get() = fitApp.healthConnect

    private val _profile = MutableStateFlow(fitApp.profiles.load())
    val profile: StateFlow<UserProfile> = _profile.asStateFlow()

    /** 首次打开（还没填年龄）时自动弹出资料设置。 */
    private val _showProfileDialog = MutableStateFlow(_profile.value.age == null)
    val showProfileDialog: StateFlow<Boolean> = _showProfileDialog.asStateFlow()

    private val _morningNotification = MutableStateFlow(fitApp.settings.morningNotification)
    val morningNotification: StateFlow<Boolean> = _morningNotification.asStateFlow()

    private val _state = MutableStateFlow<UiState>(UiState.Loading())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 最近一次读到的真实历史数据（用于改资料、填自评后立即重算，不必重新同步）。 */
    private var history: List<DailyMetrics>? = null
    private var forDate: LocalDate = FitCoachApp.targetDate()
    private var demoScenario: SampleData.Scenario? = null
    private var refreshJob: Job? = null

    init {
        refresh()
    }

    // ---------------- 资料 / 设置 ----------------

    fun openProfileDialog() {
        _showProfileDialog.value = true
    }

    fun dismissProfileDialog() {
        _showProfileDialog.value = false
    }

    fun saveProfile(profile: UserProfile) {
        fitApp.profiles.save(profile)
        _profile.value = profile
        _showProfileDialog.value = false
        recompute()
    }

    fun setMorningNotification(enabled: Boolean) {
        fitApp.settings.morningNotification = enabled
        _morningNotification.value = enabled
        if (enabled) DailyAdviceWorker.schedule(fitApp) else DailyAdviceWorker.cancel(fitApp)
    }

    // ---------------- 自评 ----------------

    fun setWellness(value: Int) {
        val date = (state.value as? UiState.Ready)?.feedback?.wellnessDate ?: return
        fitApp.feedback.setWellness(date, value)
        recompute()
    }

    fun setRpe(value: Int) {
        val date = (state.value as? UiState.Ready)?.feedback?.rpeDate ?: return
        fitApp.feedback.setRpe(date, value)
        recompute()
    }

    private fun feedbackPrompt(forDate: LocalDate): FeedbackPrompt {
        val today = LocalDate.now()
        val morning = forDate == today
        val rpeDate = if (morning) today.minusDays(1) else today
        return FeedbackPrompt(
            wellnessDate = if (morning) today else null,
            wellness = if (morning) fitApp.feedback.get(today).wellness else null,
            rpeDate = rpeDate,
            rpe = fitApp.feedback.get(rpeDate).rpe,
        )
    }

    // ---------------- 数据 ----------------

    /**
     * 先用本地缓存立即算出结果显示出来，再在后台同步 Health Connect 的新数据，同步完更新界面。
     * [forceFull] 为 true 时丢弃增量状态，重新完整导入。
     */
    fun refresh(forceFull: Boolean = false) {
        demoScenario = null
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try {
                when (val availability = healthConnect.availability()) {
                    Availability.NOT_INSTALLED, Availability.UPDATE_REQUIRED -> {
                        _state.value = UiState.HealthConnectMissing(availability == Availability.UPDATE_REQUIRED)
                        return@launch
                    }
                    Availability.AVAILABLE -> Unit
                }
                if (!healthConnect.grantedPermissions().containsAll(healthConnect.minimumPermissions)) {
                    _state.value = UiState.NeedsPermission
                    return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: e.javaClass.simpleName)
                return@launch
            }

            forDate = FitCoachApp.targetDate()
            val (start, end) = FitCoachApp.historyRange(forDate)

            val cached = if (forceFull) null else fitApp.sync.cachedHistory(start, end)
            if (cached != null) {
                history = cached
                publish(cached, SyncStatus.Syncing)
            } else {
                _state.value = UiState.Loading("首次导入最近 30 天的数据，需要一点时间，之后只会更新新增的部分")
            }

            try {
                val result = fitApp.sync.sync(start, end, forceFull)
                history = result.history
                publish(result.history, SyncStatus.Done(result.mode, result.changedDays, result.durationMs))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                if (cached != null) publish(cached, SyncStatus.Failed(message))
                else _state.value = UiState.Error(message)
            }
        }
    }

    private fun publish(history: List<DailyMetrics>, sync: SyncStatus?) {
        val advice = fitApp.computeAdvice(history, forDate, _profile.value)
        fitApp.publishLatest(advice.recommendation)
        _state.value = UiState.Ready(advice, demoScenario = null, sync = sync, feedback = feedbackPrompt(forDate))
    }

    /** 资料或自评变化后只需重新计算，不必重新同步。 */
    private fun recompute() {
        demoScenario?.let { return loadDemo(it) }
        val current = _state.value as? UiState.Ready ?: return refresh()
        val data = history ?: return refresh()
        publish(data, current.sync)
    }

    fun loadDemo(scenario: SampleData.Scenario) {
        refreshJob?.cancel()
        demoScenario = scenario
        val today = LocalDate.now()
        val demoHistory = SampleData.generate(scenario, endDate = today, days = FitCoachApp.HISTORY_DAYS.toInt())
        val advice = fitApp.computeAdvice(demoHistory, today.plusDays(1), _profile.value, includeFeedback = false)
        _state.value = UiState.Ready(advice, scenario)
    }
}
