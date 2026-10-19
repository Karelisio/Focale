package app.focale.vault

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import app.focale.core.di.VaultStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Date de la dernière sauvegarde réussie du coffre, rappelée sur son écran. */
@Singleton
class VaultBackupPrefs @Inject constructor(
    @VaultStore private val store: DataStore<Preferences>,
) {
    /** Instant de la dernière sauvegarde (ms), null si aucune n'a été faite. */
    val lastBackupAt: Flow<Long?> = store.data.map { it[LAST_BACKUP_AT] }

    suspend fun setLastBackupAt(epochMs: Long) {
        store.edit { it[LAST_BACKUP_AT] = epochMs }
    }

    private companion object {
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
    }
}
