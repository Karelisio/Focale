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
import androidx.work.workDataOf
import app.focale.core.database.AnalysisDao
import app.focale.core.database.MediaDao
import app.focale.core.di.ApplicationScope
import app.focale.core.mediastore.MediaSyncManager
import app.focale.core.settings.SettingsRepository
import app.focale.ml.AnalysisKind
import app.focale.ml.AnalysisStep
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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Avancement de l'analyse intelligente, affiché dans l'onglet Recherche. */
@Immutable
data class AnalysisProgress(val analyzed: Int, val total: Int, val running: Boolean) {
    val done: Boolean get() = total > 0 && analyzed >= total
    val fraction: Float get() = if (total == 0) 1f else analyzed.toFloat() / total
}

/** Avancement d'une étape complémentaire (texte, visages, recherche par le sens). */
@Immutable
data class StepProgress(val kind: AnalysisKind, val done: Int, val total: Int) {
    val complete: Boolean get() = done >= total
    val fraction: Float get() = if (total == 0) 1f else done.toFloat() / total
}

/** État complet de l'analyse : principale (étiquettes, lieux…) puis étapes complémentaires. */
@Immutable
data class AnalysisStatus(
    val main: AnalysisProgress,
    val steps: List<StepProgress>,
    /** Passage en cours (sinon en attente de ses conditions : batterie, charge). */
    val running: Boolean,
    /** Réglage « uniquement en charge » : la cause probable d'une attente. */
    val onlyWhileCharging: Boolean,
) {
    val complete: Boolean get() = (main.total == 0 || main.done) && steps.all { it.complete }
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
    private val steps: Lazy<Set<@JvmSuppressWildcards AnalysisStep>>,
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

    /**
     * Continuation d'un passage interrompu faute de temps. Un passage lancé à la main
     * ([runNow]) le reste jusqu'à la fin de l'analyse.
     */
    fun continueLater(manual: Boolean = false) = schedule(ExistingWorkPolicy.APPEND_OR_REPLACE, manual)

    /**
     * « Analyser maintenant » : passage immédiat, sans attendre la charge même si le réglage
     * « uniquement en charge » est actif (la batterie ne doit pas être faible). Sans effet si
     * un passage est déjà en cours.
     */
    fun runNow() {
        scope.launch {
            val running = workManager.get().getWorkInfosForUniqueWorkFlow(NAME).first().any { it.state == WorkInfo.State.RUNNING }
            if (!running) schedule(ExistingWorkPolicy.REPLACE, manual = true)
        }
    }

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

    /** Avancement détaillé, mis à jour en direct (réglages, onglet Recherche). */
    val status: Flow<AnalysisStatus> = flow {
        // Étapes obtenues à la première observation : rien n'est construit au démarrage.
        val stepFlows = steps.get().sortedBy { it.order }
            .map { step -> step.observeProgress().map { StepProgress(step.kind, it.done, it.total) } }
        val stepsProgress = if (stepFlows.isEmpty()) flowOf(emptyList()) else combine(stepFlows) { it.toList() }
        emitAll(
            combine(
                progress,
                stepsProgress,
                settings.settings.map { it.analysisOnlyWhileCharging }.distinctUntilChanged(),
            ) { main, steps, onlyWhileCharging ->
                AnalysisStatus(main = main, steps = steps, running = main.running, onlyWhileCharging = onlyWhileCharging)
            },
        )
    }

    private fun constraints(manual: Boolean): Constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .setRequiresCharging(!manual && settings.current.value.analysisOnlyWhileCharging)
        .build()

    private fun schedule(policy: ExistingWorkPolicy, manual: Boolean = false) {
        if (!settings.current.value.analysisEnabled) {
            workManager.get().cancelUniqueWork(NAME)
            return
        }
        val request = OneTimeWorkRequestBuilder<AnalysisWorker>()
            .setConstraints(constraints(manual))
            .setInputData(workDataOf(AnalysisWorker.KEY_MANUAL to manual))
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
