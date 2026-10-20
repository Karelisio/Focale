package app.focale.work

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.focale.core.di.StateStore
import app.focale.ml.AnalysisKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Dernier échec global de chaque analyse (modèle indisponible…), gardé jusqu'à son
 * prochain lot réussi : l'avancement l'affiche au lieu de laisser croire à une attente.
 */
@Singleton
class AnalysisIssues @Inject constructor(@StateStore private val stateStore: DataStore<Preferences>) {

    val issues: Flow<Map<AnalysisKind, String>> = stateStore.data
        .map { prefs -> AnalysisKind.entries.mapNotNull { kind -> prefs[key(kind)]?.let { kind to it } }.toMap() }
        .distinctUntilChanged()

    suspend fun report(kind: AnalysisKind, error: Throwable) {
        val message = describe(error)
        stateStore.edit { it[key(kind)] = message }
    }

    suspend fun clear(kind: AnalysisKind) {
        if (stateStore.data.first()[key(kind)] == null) return
        stateStore.edit { it.remove(key(kind)) }
    }

    companion object {
        private const val MAX_LENGTH = 200

        private fun key(kind: AnalysisKind) = stringPreferencesKey("analysis_issue_${kind.name.lowercase()}")

        /** Cause première, la plus parlante (« MlKitException: … »), raccourcie. */
        fun describe(error: Throwable): String {
            var root = error
            while (true) root = root.cause?.takeIf { it !== root } ?: break
            val message = root.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
            val text = if (message.isEmpty()) root.javaClass.simpleName else "${root.javaClass.simpleName}: $message"
            return text.take(MAX_LENGTH)
        }
    }
}
