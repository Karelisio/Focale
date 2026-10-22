package app.focale.vault

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import app.focale.backup.ArchiveFormat
import app.focale.backup.AutoBackupKeys
import app.focale.backup.AutoBackupPrefs
import app.focale.core.di.VaultStore
import com.google.crypto.tink.Aead
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Réglage et état des sauvegardes automatiques du coffre, dans le DataStore du coffre. */
@Singleton
class VaultAutoBackupPrefs @Inject constructor(@VaultStore store: DataStore<Preferences>) : AutoBackupPrefs(store)

/**
 * Clés des sauvegardes automatiques du coffre ([AutoBackupKeys]), dans le DataStore du coffre,
 * chiffrées par un jeu de clés propre à cet usage, protégé par le Keystore comme celui du coffre
 * ([VaultCrypto]) : utilisables coffre verrouillé.
 */
@Singleton
class VaultAutoBackupKeys(
    store: DataStore<Preferences>,
    primitive: () -> Aead,
) : AutoBackupKeys(store, KEYS, ASSOCIATED_DATA, ArchiveFormat.VAULT, primitive) {

    @Inject
    constructor(
        @VaultStore store: DataStore<Preferences>,
        @ApplicationContext context: Context,
    ) : this(store, { AutoBackupKeys.keystoreBackedAead(context, KEYSET_NAME, PREFS_NAME, MASTER_KEY_URI) })

    private companion object {
        val KEYS = byteArrayPreferencesKey("auto_backup_keys")
        /** Lie le chiffré à son usage : il ne peut pas passer pour une autre donnée. */
        val ASSOCIATED_DATA = "focale-vault-auto-backup/keys".toByteArray(Charsets.US_ASCII)
        const val KEYSET_NAME = "vault_backup_keyset"
        const val PREFS_NAME = "focale_vault_keyset"
        const val MASTER_KEY_URI = "android-keystore://focale_vault_backup_key"
    }
}
