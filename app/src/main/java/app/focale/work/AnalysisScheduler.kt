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
import androidx.room.InvalidationTracker
import app.focale.core.database.AnalysisDao
import app.focale.core.database.MediaDao
import app.focale.core.database.sampledQuery
import app.focale.core.di.ApplicationScope
import app.focale.core.mediastore.MediaSyncManager
import app.focale.core.settings.SettingsRepository
import app.focale.ml.AnalysisKind
import app.focale.ml.AnalysisMilestones
import app.focale.ml.AnalysisStep
import app.focale.ml.ImageAnalyzer
import dagger.Lazy
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
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

/** Avancement de l'analyse principale (étiquettes, lieux, doublons, netteté). */
@Immutable
data class AnalysisProgress(val analyzed: Int, val total: Int) {
    val done: Boolean get() = total > 0 && analyzed >= total
    val fraction: Float get() = if (total == 0) 1f else analyzed.toFloat() / total
}

/** État de la tâche d'analyse dans WorkManager. */
enum class AnalysisPhase {
    RUNNING,

    /** Planifiée, en attente de ses conditions (batterie, charge) ou d'un passage précédent. */
    WAITING,

    /** Rien de planifié : terminée, interrompue par un échec, ou en attente d'un déclencheur. */
    IDLE,
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
    val phase: AnalysisPhase,
    /** Réglage « uniquement en charge » : la cause probable d'une attente. */
    val onlyWhileCharging: Boolean,
    /** Dernier échec de chaque analyse (voir [AnalysisIssues]). */
    val issues: Map<AnalysisKind, String> = emptyMap(),
) {
    val running: Boolean get() = phase == AnalysisPhase.RUNNING
    val complete: Boolean get() = mainComplete && steps.all { it.complete }

    /** Échecs des analyses encore inachevées : celui d'une analyse terminée ne compte plus. */
    val failures: Map<AnalysisKind, String>
        get() = issues.filterKeys { kind ->
            if (kind == AnalysisKind.LABELS) !mainComplete else steps.any { it.kind == kind && !it.complete }
        }

    private val mainComplete: Boolean get() = main.total == 0 || main.done
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
    private val issues: AnalysisIssues,
    @ApplicationScope private val scope: CoroutineScope,
    private val invalidationTracker: InvalidationTracker,
    private val milestones: AnalysisMilestones,
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

    /**
     * Compteur d'avancement : chaque lecture parcourt toute la bibliothèque, et chaque lot de
     * l'analyse en change les tables. Relu au plus toutes les [PROGRESS_INTERVAL] tant qu'il est
     * affiché, aussitôt à la fin d'une file ([AnalysisMilestones]) : la fin s'affiche sans délai.
     */
    private fun <T> progress(tables: Array<String>, read: suspend () -> T): Flow<T> =
        sampledQuery(invalidationTracker.createFlow(*tables), PROGRESS_INTERVAL, milestones.events, query = read)

    private val mainProgress: Flow<AnalysisProgress> = combine(
        mediaDao.observeTotalCount(),
        progress(arrayOf("media", "media_analysis")) { analysisDao.pendingCount(ImageAnalyzer.VERSION) },
    ) { total, pending -> AnalysisProgress(analyzed = (total - pending).coerceAtLeast(0), total = total) }

    private val phase: Flow<AnalysisPhase> = workManager.get().getWorkInfosForUniqueWorkFlow(NAME)
        .map { infos ->
            when {
                infos.any { it.state == WorkInfo.State.RUNNING } -> AnalysisPhase.RUNNING
                infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } -> AnalysisPhase.WAITING
                else -> AnalysisPhase.IDLE
            }
        }
        .distinctUntilChanged()

    /** Avancement détaillé, mis à jour en direct (réglages, onglet Recherche). */
    val status: Flow<AnalysisStatus> = flow {
        // Étapes obtenues à la première observation : rien n'est construit au démarrage.
        val stepFlows = steps.get().sortedBy { it.order }
            .map { step -> progress(step.progressTables) { step.progress() }.map { StepProgress(step.kind, it.done, it.total) } }
        val stepsProgress = if (stepFlows.isEmpty()) flowOf(emptyList()) else combine(stepFlows) { it.toList() }
        emitAll(
            combine(
                mainProgress,
                stepsProgress,
                phase,
                settings.settings.map { it.analysisOnlyWhileCharging }.distinctUntilChanged(),
                issues.issues,
            ) { main, steps, phase, onlyWhileCharging, issues ->
                AnalysisStatus(main = main, steps = steps, phase = phase, onlyWhileCharging = onlyWhileCharging, issues = issues)
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

        /** Intervalle minimal entre deux lectures d'un compteur d'avancement affiché. */
        val PROGRESS_INTERVAL = 3.seconds
    }
}
