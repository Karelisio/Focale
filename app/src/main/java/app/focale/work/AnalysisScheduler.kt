package app.focale.work

import androidx.compose.runtime.Immutable
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.focale.core.database.AnalysisDao
import app.focale.core.database.MediaDao
import app.focale.core.di.ApplicationScope
import app.focale.core.mediastore.MediaSyncManager
import app.focale.core.settings.SettingsRepository
import app.focale.ml.ImageAnalyzer
import dagger.Lazy
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Avancement de l'analyse intelligente, affiché dans l'onglet Recherche. */
@Immutable
data class AnalysisProgress(val analyzed: Int, val total: Int, val running: Boolean) {
    val done: Boolean get() = total > 0 && analyzed >= total
    val fraction: Float get() = if (total == 0) 1f else analyzed.toFloat() / total
}

/**
 * Planifie l'analyse : au démarrage, après chaque synchronisation qui ajoute des
 * médias, et chaque nuit (en charge) pour rattraper le retard.
 */
@Singleton
class AnalysisScheduler @Inject constructor(
    private val workManager: Lazy<WorkManager>,
    private val settings: SettingsRepository,
    private val analysisDao: AnalysisDao,
    private val mediaDao: MediaDao,
    private val syncManager: MediaSyncManager,
    @ApplicationScope private val scope: CoroutineScope,
) {

    fun start() {
        schedule(ExistingWorkPolicy.KEEP)
        schedulePeriodic()
        scope.launch { syncManager.changes.collect { schedule(ExistingWorkPolicy.KEEP) } }
        // Un changement de réglage (activation, « en charge uniquement ») remplace la tâche.
        scope.launch {
            settings.settings
                .map { it.analysisEnabled to it.analysisOnlyWhileCharging }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    schedule(ExistingWorkPolicy.REPLACE)
                    schedulePeriodic(ExistingPeriodicWorkPolicy.UPDATE)
                }
        }
    }

    /** Continuation d'un passage interrompu faute de temps. */
    fun continueLater() = schedule(ExistingWorkPolicy.APPEND_OR_REPLACE)

    val progress: Flow<AnalysisProgress> = combine(
        mediaDao.observeTotalCount(),
        analysisDao.observePendingCount(ImageAnalyzer.VERSION),
        workManager.get().getWorkInfosForUniqueWorkFlow(NAME),
    ) { total, pending, infos ->
        AnalysisProgress(
            analyzed = (total - pending).coerceAtLeast(0),
            total = total,
            running = infos.any { it.state == WorkInfo.State.RUNNING },
        )
    }

    private fun constraints(): Constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .setRequiresCharging(settings.current.value.analysisOnlyWhileCharging)
        .build()

    private fun schedule(policy: ExistingWorkPolicy) {
        if (!settings.current.value.analysisEnabled) {
            workManager.get().cancelUniqueWork(NAME)
            return
        }
        val request = OneTimeWorkRequestBuilder<AnalysisWorker>()
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager.get().enqueueUniqueWork(NAME, policy, request)
    }

    private fun schedulePeriodic(policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP) {
        if (!settings.current.value.analysisEnabled) {
            workManager.get().cancelUniqueWork(PERIODIC_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<AnalysisWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).build(),
            )
            .build()
        workManager.get().enqueueUniquePeriodicWork(PERIODIC_NAME, policy, request)
    }

    private companion object {
        const val NAME = "media-analysis"
        const val PERIODIC_NAME = "media-analysis-nightly"
    }
}
