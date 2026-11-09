package com.fitcoach.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fitcoach.app.data.CacheStore
import com.fitcoach.app.data.HealthConnectRepository
import com.fitcoach.app.data.HealthConnectRepository.Availability
import com.fitcoach.app.data.HealthSyncRepository
import com.fitcoach.app.data.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import com.fitcoach.engine.Recommendation
import com.fitcoach.engine.RecommendationEngine
import com.fitcoach.engine.SampleData
import com.fitcoach.engine.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

sealed interface SyncStatus {
    /** 正在后台同步，界面上先显示的是缓存数据算出的结果。 */
    data object Syncing : SyncStatus
    data class Done(val mode: HealthSyncRepository.Mode, val changedDays: Int, val durationMs: Long) : SyncStatus
    data class Failed(val message: String) : SyncStatus
}

sealed interface UiState {
    data class Loading(val message: String? = null) : UiState
    data class HealthConnectMissing(val updateRequired: Boolean) : UiState
    data object NeedsPermission : UiState
    data class Ready(
        val recommendation: Recommendation,
        /** null 表示真实数据，否则为演示场景。 */
        val demoScenario: SampleData.Scenario?,
        val sync: SyncStatus? = null,
    ) : UiState
    data class Error(val message: String) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val repository = HealthConnectRepository(app)
    private val syncRepository = HealthSyncRepository(repository, CacheStore(app))
    private val profileRepository = ProfileRepository(app)
    private val engine = RecommendationEngine()

    private val _profile = MutableStateFlow(profileRepository.load())
    val profile: StateFlow<UserProfile> = _profile.asStateFlow()

    /** 首次打开（还没填年龄）时自动弹出资料设置。 */
    private val _showProfileDialog = MutableStateFlow(_profile.value.age == null)
    val showProfileDialog: StateFlow<Boolean> = _showProfileDialog.asStateFlow()

    private val _state = MutableStateFlow<UiState>(UiState.Loading())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 当前正在看的演示场景；null 表示在看真实数据。 */
    private var demoScenario: SampleData.Scenario? = null

    init {
        refresh()
    }

    fun openProfileDialog() {
        _showProfileDialog.value = true
    }

    fun dismissProfileDialog() {
        _showProfileDialog.value = false
    }

    fun saveProfile(profile: UserProfile) {
        profileRepository.save(profile)
        _profile.value = profile
        _showProfileDialog.value = false
        // 资料变了，重新计算建议（用缓存数据，不重新同步）
        demoScenario?.let(::loadDemo) ?: recompute()
    }

    /** 中午之前给"今天"的建议（今天还没开始练），之后给"明天"的建议。 */
    private fun targetDate(): LocalDate =
        if (LocalTime.now().isBefore(LocalTime.NOON)) LocalDate.now() else LocalDate.now().plusDays(1)

    private var refreshJob: Job? = null

    /**
     * 先用本地缓存立即算出结果显示出来，再在后台同步 Health Connect 的新数据，同步完更新界面。
     * [forceFull] 为 true 时丢弃增量状态，重新完整导入。
     */
    fun refresh(forceFull: Boolean = false) {
        demoScenario = null
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try {
                when (val availability = repository.availability()) {
                    Availability.NOT_INSTALLED, Availability.UPDATE_REQUIRED -> {
                        _state.value = UiState.HealthConnectMissing(availability == Availability.UPDATE_REQUIRED)
                        return@launch
                    }
                    Availability.AVAILABLE -> Unit
                }
                if (!repository.grantedPermissions().containsAll(repository.minimumPermissions)) {
                    _state.value = UiState.NeedsPermission
                    return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: e.javaClass.simpleName)
                return@launch
            }

            val forDate = targetDate()
            // 恢复信号可能来自 forDate 当天（早上的睡眠 / 心率），所以读到 forDate 为止
            val start = forDate.minusDays(28)
            val end = minOf(forDate, LocalDate.now())

            val cached = if (forceFull) null else syncRepository.cachedHistory(start, end)
            _state.value = if (cached != null) {
                UiState.Ready(engine.recommend(cached, forDate, _profile.value), null, SyncStatus.Syncing)
            } else {
                UiState.Loading("首次导入最近 30 天的数据，需要一点时间，之后只会更新新增的部分")
            }

            _state.value = try {
                val result = syncRepository.sync(start, end, forceFull)
                UiState.Ready(
                    engine.recommend(result.history, forDate, _profile.value),
                    demoScenario = null,
                    sync = SyncStatus.Done(result.mode, result.changedDays, result.durationMs),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                if (cached != null) {
                    UiState.Ready(engine.recommend(cached, forDate, _profile.value), null, SyncStatus.Failed(message))
                } else {
                    UiState.Error(message)
                }
            }
        }
    }

    /** 资料变化后只需重新计算，不必重新同步。 */
    private fun recompute() {
        val current = _state.value as? UiState.Ready ?: return refresh()
        if (current.sync == SyncStatus.Syncing) return // 同步完成时会用新资料计算
        viewModelScope.launch {
            val forDate = current.recommendation.forDate
            val history = syncRepository.cachedHistory(forDate.minusDays(28), minOf(forDate, LocalDate.now()))
                ?: return@launch refresh()
            _state.value = current.copy(recommendation = engine.recommend(history, forDate, _profile.value))
        }
    }

    fun loadDemo(scenario: SampleData.Scenario) {
        refreshJob?.cancel()
        demoScenario = scenario
        val today = LocalDate.now()
        val history = SampleData.generate(scenario, endDate = today)
        _state.value = UiState.Ready(engine.recommend(history, today.plusDays(1), _profile.value), scenario)
    }
}
