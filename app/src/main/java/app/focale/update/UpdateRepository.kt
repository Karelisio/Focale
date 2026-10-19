package app.focale.update

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.focale.BuildConfig
import app.focale.core.di.ApplicationScope
import app.focale.core.di.IoDispatcher
import app.focale.core.di.StateStore
import app.focale.core.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Étape du parcours de mise à jour, partagée par les réglages et l'onglet Plus. */
@Immutable
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AppUpdate) : UpdateState
    data class Downloading(val update: AppUpdate, val progress: Float) : UpdateState
    data class Installing(val update: AppUpdate) : UpdateState
    data class Failed(val error: UpdateError, val update: AppUpdate?) : UpdateState
}

enum class UpdateError { NETWORK, INTEGRITY, INSTALL }

/** Mise à jour proposée par l'état, s'il y en a une. */
val UpdateState.update: AppUpdate?
    get() = when (this) {
        is UpdateState.Available -> update
        is UpdateState.Downloading -> update
        is UpdateState.Installing -> update
        is UpdateState.Failed -> update
        else -> null
    }

/**
 * Mises à jour intégrées (variante standard) : la dernière version publiée sur GitHub
 * est comparée à la version installée ; l'APK de l'architecture de l'appareil est
 * téléchargé, contrôlé (taille et SHA-256), puis confié à l'installateur système, qui
 * vérifie en outre qu'il est signé avec la même clé que l'application installée.
 *
 * Seules requêtes : l'API publique des versions GitHub (au plus une fois par jour,
 * ou à la demande) et le téléchargement de l'APK choisi.
 */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val installer: UpdateInstaller,
    @StateStore private val stateStore: DataStore<Preferences>,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    /** Faux dans la variante strict (aucun accès réseau). */
    val supported: Boolean = BuildConfig.UPDATE_REPOSITORY.isNotBlank()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val confirmationChannel = Channel<Intent>(Channel.CONFLATED)

    /** Écrans de confirmation de l'installateur système, lancés par `MainActivity`. */
    val confirmations: Flow<Intent> = confirmationChannel.receiveAsFlow()

    private val startup = Mutex()
    private var restored = false

    private val downloads: File get() = File(context.cacheDir, DOWNLOAD_DIR)

    /** Recherche demandée depuis l'interface : le résultat ou l'erreur est affiché. */
    fun check() {
        if (!supported) return
        scope.launch { runCheck(manual = true) }
    }

    /**
     * Au passage au premier plan : restaure la mise à jour trouvée lors d'un lancement
     * précédent, puis recherche silencieusement au plus une fois par jour.
     */
    fun checkIfDue() {
        if (!supported) return
        scope.launch {
            startup.withLock {
                if (!restored) {
                    restored = true
                    restore()
                }
                if (!settings.settings.first().autoCheckUpdates) return@withLock
                val lastCheck = stateStore.data.first()[Keys.LAST_CHECK] ?: 0L
                if (System.currentTimeMillis() - lastCheck >= CHECK_INTERVAL_MS) runCheck(manual = false)
            }
        }
    }

    /** Télécharge puis installe [update] ; l'installateur système prend ensuite le relais. */
    fun install(update: AppUpdate) {
        val previous = _state.value
        if (previous is UpdateState.Downloading || previous is UpdateState.Installing) return
        if (!_state.compareAndSet(previous, UpdateState.Downloading(update, 0f))) return
        scope.launch {
            val apk = try {
                download(update)
            } catch (error: IntegrityException) {
                _state.value = UpdateState.Failed(UpdateError.INTEGRITY, update)
                return@launch
            } catch (error: IOException) {
                Log.w(TAG, "Téléchargement impossible", error)
                _state.value = UpdateState.Failed(UpdateError.NETWORK, update)
                return@launch
            }
            _state.value = UpdateState.Installing(update)
            val committed = withContext(io) {
                runCatching { installer.install(apk) }
                    .onFailure { Log.w(TAG, "Session d'installation impossible", it) }
                    .also { apk.delete() }
                    .isSuccess
            }
            if (!committed) _state.value = UpdateState.Failed(UpdateError.INSTALL, update)
        }
    }

    /** Android demande une confirmation (première installation, Android 10 et 11…). */
    fun onConfirmationRequired(intent: Intent) {
        confirmationChannel.trySend(intent)
    }

    /** Abandon par l'utilisateur, ou refus de l'installateur (signature différente, espace…). */
    fun onInstallFailed(aborted: Boolean) {
        val update = _state.value.update
        _state.value = when {
            !aborted -> UpdateState.Failed(UpdateError.INSTALL, update)
            update != null -> UpdateState.Available(update)
            else -> UpdateState.Idle
        }
    }

    private suspend fun restore() {
        withContext(io) { downloads.deleteRecursively() }
        val saved = stateStore.data.first()[Keys.AVAILABLE]
            ?.let { runCatching { json.decodeFromString(AppUpdate.serializer(), it) }.getOrNull() }
        if (saved != null && AppVersion.isNewer(saved.version, BuildConfig.VERSION_NAME)) {
            _state.compareAndSet(UpdateState.Idle, UpdateState.Available(saved))
        } else {
            stateStore.edit { it.remove(Keys.AVAILABLE) }
        }
    }

    private suspend fun runCheck(manual: Boolean) {
        val current = _state.value
        if (current == UpdateState.Checking || current is UpdateState.Downloading || current is UpdateState.Installing) return
        if (manual) _state.value = UpdateState.Checking
        val update = runCatching { withContext(io) { fetchLatest() } }.getOrElse { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "Recherche de mise à jour impossible", error)
            if (manual) _state.value = UpdateState.Failed(UpdateError.NETWORK, null)
            return
        }
        stateStore.edit { prefs ->
            prefs[Keys.LAST_CHECK] = System.currentTimeMillis()
            if (update != null) {
                prefs[Keys.AVAILABLE] = json.encodeToString(AppUpdate.serializer(), update)
            } else {
                prefs.remove(Keys.AVAILABLE)
            }
        }
        val next = when {
            update != null -> UpdateState.Available(update)
            manual -> UpdateState.UpToDate
            else -> UpdateState.Idle
        }
        // L'état a pu changer pendant la requête (téléchargement lancé…) : il n'est alors pas écrasé.
        _state.compareAndSet(if (manual) UpdateState.Checking else current, next)
    }

    /** Dernière version publiée, ou null si elle n'est pas plus récente (ou s'il n'y en a aucune). */
    private fun fetchLatest(): AppUpdate? {
        val connection = open("https://api.github.com/repos/${BuildConfig.UPDATE_REPOSITORY}/releases/latest", API_TYPE)
        try {
            val body = try {
                connection.inputStream.bufferedReader().use { it.readText() }
            } catch (noRelease: FileNotFoundException) {
                return null // 404 : aucune version publiée.
            }
            return ReleaseParser.updateFrom(ReleaseParser.parse(body), BuildConfig.VERSION_NAME, Build.SUPPORTED_ABIS.toList())
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun download(update: AppUpdate): File = withContext(io) {
        downloads.deleteRecursively()
        val target = File(downloads.apply { mkdirs() }, "focale-${update.version}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = open(update.apkUrl, APK_TYPE)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            val total = update.apkSize.takeIf { it > 0 } ?: connection.contentLengthLong
            var received = 0L
            var reported = 0f
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        received += read
                        val progress = if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else 0f
                        if (progress - reported >= PROGRESS_STEP) {
                            reported = progress
                            _state.value = UpdateState.Downloading(update, progress)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        val sizeMatches = update.apkSize <= 0 || target.length() == update.apkSize
        val hashMatches = update.sha256 == null || digest.digest().toHex() == update.sha256
        if (!sizeMatches || !hashMatches) {
            target.delete()
            throw IntegrityException()
        }
        target
    }

    private fun open(url: String, accept: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        setRequestProperty("Accept", accept)
        setRequestProperty("User-Agent", "Focale/${BuildConfig.VERSION_NAME}")
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private class IntegrityException : Exception()

    private object Keys {
        val LAST_CHECK = longPreferencesKey("update_last_check")
        val AVAILABLE = stringPreferencesKey("update_available")
    }

    private companion object {
        const val TAG = "UpdateRepository"
        const val DOWNLOAD_DIR = "updates"
        const val API_TYPE = "application/vnd.github+json"
        const val APK_TYPE = "application/octet-stream"
        const val BUFFER = 64 * 1024
        const val TIMEOUT_MS = 20_000
        const val PROGRESS_STEP = 0.01f
        val CHECK_INTERVAL_MS: Long = TimeUnit.DAYS.toMillis(1)
        val json = Json { ignoreUnknownKeys = true }
    }
}
