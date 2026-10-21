package app.focale.vault

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.focale.core.di.VaultStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Réglage et état des sauvegardes automatiques, dans le DataStore du coffre (exclu des
 * sauvegardes Android) : dossier, fréquence, dernière sauvegarde, dernier échec, et de quoi
 * sauter une sauvegarde inutile (empreinte du coffre sauvegardé, document écrit).
 */
@Singleton
class VaultAutoBackupPrefs @Inject constructor(
    @VaultStore private val store: DataStore<Preferences>,
) {
    val settings: Flow<AutoBackupSettings> = store.data.map { it.settings() }.distinctUntilChanged()

    /** Réglage, empreinte et document de la dernière sauvegarde. */
    internal data class State(val settings: AutoBackupSettings, val fingerprint: String?, val lastDocument: String?)

    internal suspend fun state(): State = store.data.first().let { State(it.settings(), it[FINGERPRINT], it[LAST_DOCUMENT]) }

    /** Active les sauvegardes dans [folder] : aucune n'y est encore connue. */
    internal suspend fun enable(folder: Uri, folderName: String?, frequency: AutoBackupFrequency) {
        store.edit { prefs ->
            prefs[FREQUENCY] = frequency.name
            prefs.setFolder(folder, folderName)
        }
    }

    internal suspend fun setFrequency(frequency: AutoBackupFrequency) {
        store.edit { it[FREQUENCY] = frequency.name }
    }

    /** Nouveau dossier (ou le même, choisi de nouveau) : la sauvegarde suivante y est écrite. */
    internal suspend fun changeFolder(folder: Uri, folderName: String?) {
        store.edit { it.setFolder(folder, folderName) }
    }

    /**
     * Sauvegarde réussie dans [folder] : sa date, son empreinte (contenu et clés) et son document.
     * Rien n'est noté si le réglage a changé entre-temps (désactivée, autre dossier).
     */
    internal suspend fun recordSuccess(folder: Uri, at: Long, fingerprint: String, document: String) {
        store.edit { prefs ->
            if (prefs[FOLDER] != folder.toString()) return@edit
            prefs[LAST_AT] = at
            prefs[FINGERPRINT] = fingerprint
            prefs[LAST_DOCUMENT] = document
            prefs.remove(ERROR)
            prefs.remove(ERROR_AT)
        }
    }

    /** Dossier accessible, rien à écrire : l'échec précédent n'a plus cours. */
    internal suspend fun clearError(folder: Uri) {
        store.edit { prefs ->
            if (prefs[FOLDER] != folder.toString()) return@edit
            prefs.remove(ERROR)
            prefs.remove(ERROR_AT)
        }
    }

    /** Note l'échec [error] dans [folder] ; faux si le réglage a changé entre-temps. */
    internal suspend fun recordError(folder: Uri, error: AutoBackupError, at: Long): Boolean {
        var recorded = false
        store.edit { prefs ->
            if (prefs[FOLDER] != folder.toString()) return@edit
            prefs[ERROR] = error.name
            prefs[ERROR_AT] = at
            recorded = true
        }
        return recorded
    }

    /** Oublie tout le réglage (désactivation). */
    internal suspend fun clear() {
        store.edit { prefs ->
            prefs.forgetFolder()
            listOf(FREQUENCY, FOLDER, FOLDER_NAME).forEach { prefs.remove(it) }
        }
    }

    /** Dossier des sauvegardes, dont aucune n'est encore connue. */
    private fun MutablePreferences.setFolder(folder: Uri, folderName: String?) {
        forgetFolder()
        this[FOLDER] = folder.toString()
        if (folderName != null) this[FOLDER_NAME] = folderName else remove(FOLDER_NAME)
    }

    /** Ce qui ne vaut que pour le dossier courant : dernière sauvegarde écrite, dernier échec. */
    private fun MutablePreferences.forgetFolder() {
        listOf(FINGERPRINT, LAST_DOCUMENT, ERROR).forEach { remove(it) }
        remove(LAST_AT)
        remove(ERROR_AT)
    }

    private fun Preferences.settings(): AutoBackupSettings {
        val folder = this[FOLDER]?.let(Uri::parse)
        return AutoBackupSettings(
            frequency = this[FREQUENCY]?.let { name -> AutoBackupFrequency.entries.firstOrNull { it.name == name } }
                ?.takeIf { folder != null }
                ?: AutoBackupFrequency.OFF,
            folder = folder,
            folderName = this[FOLDER_NAME],
            lastBackupAt = this[LAST_AT],
            error = this[ERROR]?.let { name -> AutoBackupError.entries.firstOrNull { it.name == name } },
            errorAt = this[ERROR_AT],
        )
    }

    private companion object {
        val FREQUENCY = stringPreferencesKey("auto_backup_frequency")
        /** Arbre de documents choisi par le sélecteur système, avec une autorisation persistante. */
        val FOLDER = stringPreferencesKey("auto_backup_folder")
        val FOLDER_NAME = stringPreferencesKey("auto_backup_folder_name")
        val LAST_AT = longPreferencesKey("auto_backup_last_at")
        val LAST_DOCUMENT = stringPreferencesKey("auto_backup_last_document")
        val FINGERPRINT = stringPreferencesKey("auto_backup_fingerprint")
        val ERROR = stringPreferencesKey("auto_backup_error")
        val ERROR_AT = longPreferencesKey("auto_backup_error_at")
    }
}

/**
 * Clés des sauvegardes automatiques ([VaultArchiveKeys]) : le mot de passe n'est conservé nulle
 * part, seulement ce qui permet d'écrire des archives sans lui (sel, itérations, témoin et clé du
 * flux, tirés de PBKDF2 puis HKDF). Elles sont gardées dans le DataStore du coffre, exclu des
 * sauvegardes Android, chiffrées par une AEAD Tink (AES-256-GCM, données associées propres à cet
 * usage) dont le jeu de clés est lui-même chiffré par une clé non exportable de l'Android
 * Keystore, comme celui du coffre ([VaultCrypto]) : utilisables sans authentification, coffre
 * verrouillé, par une tâche de fond. L'appareil sait déjà déchiffrer le coffre : garder de quoi en
 * écrire les sauvegardes n'expose rien de plus, et ne livre pas le mot de passe.
 */
@Singleton
class VaultAutoBackupKeys(
    private val store: DataStore<Preferences>,
    primitive: () -> Aead,
) {
    @Inject
    constructor(
        @VaultStore store: DataStore<Preferences>,
        @ApplicationContext context: Context,
    ) : this(store, { keystoreBackedAead(context) })

    private val aead: Aead by lazy(primitive)

    /** Remplace les clés enregistrées ; [keys] ne sont pas effacées. */
    suspend fun save(keys: VaultArchiveKeys) {
        val plain = keys.encode()
        try {
            val sealed = aead.encrypt(plain, ASSOCIATED_DATA)
            store.edit { it[KEYS] = sealed }
        } finally {
            plain.fill(0)
        }
    }

    /**
     * Clés enregistrées, à effacer après usage ; null s'il n'y en a pas.
     *
     * @throws java.security.GeneralSecurityException clés illisibles (Keystore réinitialisé…).
     */
    suspend fun load(): VaultArchiveKeys? {
        val sealed = store.data.first()[KEYS] ?: return null
        val plain = aead.decrypt(sealed, ASSOCIATED_DATA)
        try {
            return VaultArchiveKeys.decode(plain)
        } finally {
            plain.fill(0)
        }
    }

    suspend fun clear() {
        store.edit { it.remove(KEYS) }
    }

    private companion object {
        val KEYS = byteArrayPreferencesKey("auto_backup_keys")
        /** Lie le chiffré à son usage : il ne peut pas passer pour une autre donnée. */
        val ASSOCIATED_DATA = "focale-vault-auto-backup/keys".toByteArray(Charsets.US_ASCII)
        const val KEYSET_NAME = "vault_backup_keyset"
        const val PREFS_NAME = "focale_vault_keyset"
        const val MASTER_KEY_URI = "android-keystore://focale_vault_backup_key"

        /** Jeu de clés créé au premier usage, chiffré par sa clé maîtresse du Keystore. */
        fun keystoreBackedAead(context: Context): Aead {
            AeadConfig.register()
            return AndroidKeysetManager.Builder()
                .withSharedPref(context, KEYSET_NAME, PREFS_NAME)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
                .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        }
    }
}
