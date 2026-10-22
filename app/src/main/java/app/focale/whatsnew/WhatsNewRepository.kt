package app.focale.whatsnew

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.focale.BuildConfig
import app.focale.core.di.IoDispatcher
import app.focale.core.di.StateStore
import app.focale.core.locale.AppLocales
import app.focale.update.AppVersion
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * « Nouveautés » : au premier lancement après une mise à jour, les sections du journal
 * plus récentes que la version installée auparavant ; rien après une première installation.
 * Le journal est celui de la langue de Focale ([AppLocales]) : français, sinon anglais.
 */
@Singleton
class WhatsNewRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @StateStore private val stateStore: DataStore<Preferences>,
    private val locales: AppLocales,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val current: String = BuildConfig.VERSION_NAME

    /** Toutes les versions du journal (écran À propos), dans la langue de Focale. */
    suspend fun all(): List<Changelog.Release> = withContext(io) {
        // Journal de la langue illisible : celui en français, toujours embarqué.
        listOf(Changelog.asset(locales.locale.language), Changelog.ASSET).distinct().firstNotNullOfOrNull { asset ->
            try {
                context.assets.open(asset).bufferedReader().use { Changelog.parse(it.readText()) }
            } catch (error: IOException) {
                Log.w(TAG, "Journal des versions illisible : $asset", error)
                null
            }
        }.orEmpty()
    }

    /** Versions à présenter maintenant (une seule fois : voir [markSeen]). */
    suspend fun pending(): List<Changelog.Release> {
        val lastSeen = stateStore.data.first()[KEY_LAST_SEEN]
        val previous = when {
            lastSeen != null -> lastSeen.takeIf { AppVersion.isNewer(current, it) } ?: return emptyList()
            // Version d'avant ce suivi : on présente la version installée, sauf première installation.
            isUpdate() -> PREVIOUS_UNKNOWN
            else -> {
                markSeen()
                return emptyList()
            }
        }
        val releases = Changelog.since(all(), previous, current)
        return if (previous == PREVIOUS_UNKNOWN) releases.take(1) else releases
    }

    suspend fun markSeen() {
        stateStore.edit { it[KEY_LAST_SEEN] = current }
    }

    private fun isUpdate(): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.lastUpdateTime > info.firstInstallTime
    }.getOrDefault(false)

    private companion object {
        const val TAG = "WhatsNewRepository"
        const val PREVIOUS_UNKNOWN = "0.0.0"
        val KEY_LAST_SEEN = stringPreferencesKey("whats_new_last_seen")
    }
}
