package app.focale.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.focale.core.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Renouvellement périodique du widget, planifié tant qu'une instance est posée. */
object WidgetRefresh {
    private const val WORK_NAME = "memory_widget_refresh"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(WidgetPhotoPicker.SLOT_HOURS, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        MemoryWidget().updateAll(applicationContext)
        return Result.success()
    }
}

/**
 * Mise à jour immédiate du widget, sans attendre la période suivante : une photo
 * déplacée dans le coffre ne doit pas rester affichée sur l'écran d'accueil.
 */
@Singleton
class WidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun refresh() {
        scope.launch { runCatching { MemoryWidget().updateAll(context) } }
    }
}
