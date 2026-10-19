package app.focale.ui.vault

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.navigation.toRoute
import app.focale.R
import app.focale.core.settings.AppSettings
import app.focale.core.settings.SettingsRepository
import app.focale.core.util.UiText
import app.focale.data.OperationResult
import app.focale.ui.navigation.VaultViewerRoute
import app.focale.vault.EncryptedFileDataSource
import app.focale.vault.VaultCrypto
import app.focale.vault.VaultItem
import app.focale.vault.VaultRepository
import app.focale.vault.VaultSession
import app.focale.vault.VaultStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Visionneuse du coffre : parcourt son contenu à partir de l'élément touché. */
@HiltViewModel
class VaultViewerViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repository: VaultRepository,
    private val crypto: VaultCrypto,
    private val storage: VaultStorage,
    session: VaultSession,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    /** Élément courant, restauré après recréation. */
    val startId: Long = savedStateHandle[KEY_CURRENT] ?: savedStateHandle.toRoute<VaultViewerRoute>().itemId

    val unlocked: StateFlow<Boolean> = session.unlocked

    val items: StateFlow<List<VaultItem>?> = session.unlocked
        .flatMapLatest { unlocked -> if (unlocked) repository.observeItems() else flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val settings: StateFlow<AppSettings> = settingsRepository.current

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val messages = Channel<UiText>(Channel.BUFFERED)
    val events: Flow<UiText> = messages.receiveAsFlow()

    fun onPageChanged(item: VaultItem) {
        savedStateHandle[KEY_CURRENT] = item.id
    }

    fun restore(item: VaultItem) = run(item, R.plurals.message_restored) { repository.restore(listOf(it)) }

    fun delete(item: VaultItem) = run(item, R.plurals.message_deleted) { repository.delete(listOf(it)) }

    /** Lecteur dont la source déchiffre la vidéo à la volée. */
    @OptIn(UnstableApi::class)
    fun createPlayer(context: Context): ExoPlayer =
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(EncryptedFileDataSource.Factory(crypto)))
            .build()

    fun mediaItemFor(item: VaultItem): MediaItem = MediaItem.Builder()
        .setUri(Uri.fromFile(storage.file(item.fileName)))
        .setMimeType(item.mimeType)
        .build()

    private fun run(item: VaultItem, plural: Int, action: suspend (VaultItem) -> OperationResult) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            when (action(item)) {
                is OperationResult.Success -> messages.send(UiText.Plural(plural, 1))
                OperationResult.Cancelled -> Unit
                else -> messages.send(UiText.Res(R.string.message_operation_failed))
            }
            _busy.value = false
        }
    }

    private companion object {
        const val KEY_CURRENT = "vault_current_id"
    }
}
