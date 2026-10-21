package app.focale.ui.actions

import android.content.Intent
import androidx.compose.runtime.Immutable
import app.focale.R
import app.focale.core.model.Album
import app.focale.core.model.FolderTarget
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaQuery
import app.focale.core.util.UiText
import app.focale.data.AlbumRepository
import app.focale.data.FavoriteRepository
import app.focale.data.MediaOperations
import app.focale.data.MediaRepository
import app.focale.data.MediaSharer
import app.focale.data.OperationResult
import app.focale.vault.VaultAlbum
import app.focale.vault.VaultContent
import app.focale.vault.VaultDestination
import app.focale.vault.VaultImportRequest
import app.focale.vault.VaultImportState
import app.focale.vault.VaultImports
import app.focale.vault.wholeAlbum
import app.focale.vault.VaultPinStore
import app.focale.vault.VaultRepository
import app.focale.vault.VaultSession
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Dialogue ouvert par une action (choix d'album, de dossier, renommage…). */
@Immutable
sealed interface ActionDialog {
    data class AddToAlbum(val ids: List<Long>) : ActionDialog
    data class CopyTo(val ids: List<Long>) : ActionDialog
    data class MoveTo(val ids: List<Long>) : ActionDialog
    data class Rename(val item: MediaItem) : ActionDialog

    /** Confirmation du déplacement vers le coffre, ou invitation à le configurer d'abord. */
    data class MoveToVault(val ids: List<Long>, val vaultConfigured: Boolean) : ActionDialog

    /** Confirmation du déplacement d'un album entier : [photos] et [videos] à chiffrer. */
    data class MoveAlbumToVault(
        val album: AlbumSource,
        val photos: Int,
        val videos: Int,
        val vaultConfigured: Boolean,
    ) : ActionDialog
}

/** Album de la galerie déplacé en entier dans le coffre. */
@Immutable
sealed interface AlbumSource {
    val name: String
    val query: MediaQuery

    /** Dossier de l'appareil : son album du coffre en reste lié, pour en recevoir les nouveaux médias. */
    data class Folder(val bucketId: Long, override val name: String) : AlbumSource {
        override val query: MediaQuery get() = MediaQuery(bucketId = bucketId)
    }

    /** Album de l'application. */
    data class User(val id: Long, override val name: String) : AlbumSource {
        override val query: MediaQuery get() = MediaQuery(albumId = id)
    }
}

/**
 * Opération longue en cours (chiffrement vers le coffre…). [background] : elle se poursuit
 * sans l'écran, qui peut cesser de l'attendre.
 */
@Immutable
data class ActionProgress(val label: UiText, val background: Boolean = false)

/** Événement ponctuel à traiter par l'interface. */
sealed interface ActionEvent {
    data class Share(val intent: Intent) : ActionEvent
    data class Message(val text: UiText, val undo: (() -> Unit)? = null) : ActionEvent

    /** L'action a abouti : l'écran peut quitter le mode sélection. */
    data object Done : ActionEvent

    /** Album entier passé dans le coffre : l'écran de l'album se ferme sur ce message. */
    data class AlbumMoved(val text: UiText) : ActionEvent
}

/**
 * Actions communes à toutes les grilles et à la visionneuse : partager, favoris,
 * albums, corbeille, copier, déplacer, renommer, coffre-fort.
 *
 * Instance propre à chaque ViewModel (non singleton) : elle s'exécute dans la portée
 * du ViewModel passée à [bind].
 */
class MediaActions @Inject constructor(
    private val operations: MediaOperations,
    private val albums: AlbumRepository,
    private val favorites: FavoriteRepository,
    private val media: MediaRepository,
    private val sharer: MediaSharer,
    private val vault: VaultRepository,
    private val vaultContent: VaultContent,
    private val vaultImports: VaultImports,
    private val vaultPins: VaultPinStore,
    private val vaultSession: VaultSession,
) {
    private lateinit var scope: CoroutineScope

    private val _dialog = MutableStateFlow<ActionDialog?>(null)
    val dialog: StateFlow<ActionDialog?> = _dialog.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _progress = MutableStateFlow<ActionProgress?>(null)
    val progress: StateFlow<ActionProgress?> = _progress.asStateFlow()

    private val channel = Channel<ActionEvent>(Channel.BUFFERED)
    val events: Flow<ActionEvent> = channel.receiveAsFlow()

    val userAlbums: Flow<List<Album.User>> get() = albums.observeUserAlbums()
    val folders: Flow<List<Album.Folder>> get() = albums.observeFolders()

    val vaultUnlocked: StateFlow<Boolean> get() = vaultSession.unlocked

    /** Albums du coffre, vides tant qu'il est verrouillé : leurs noms ne s'affichent pas autrement. */
    val vaultAlbums: Flow<List<VaultAlbum>>
        get() = vaultSession.unlocked.flatMapLatest { unlocked -> if (unlocked) vaultContent.albums() else flowOf(emptyList()) }

    fun bind(scope: CoroutineScope) {
        this.scope = scope
    }

    fun dismissDialog() {
        _dialog.value = null
        relockIfTemporary()
    }

    /** Déverrouillé seulement pour choisir un album du coffre : reverrouillé une fois le choix fait. */
    private var relockVault = false

    /** « Déverrouiller pour choisir un album » : le coffre l'était-il déjà ? */
    fun unlockForAlbumChoice() {
        relockVault = relockVault || !vaultSession.unlocked.value
    }

    private fun relockIfTemporary() {
        if (relockVault) {
            relockVault = false
            vaultSession.lock()
        }
    }

    /** Le retrait des métadonnées peut prendre un moment (vidéos) : progression affichée. */
    fun share(ids: Collection<Long>) = run {
        _progress.value = ActionProgress(UiText.Res(R.string.share_preparing))
        try {
            sharer.shareIntent(media.getByIds(ids))?.let { channel.send(ActionEvent.Share(it)) }
        } finally {
            _progress.value = null
        }
    }

    /** Ajoute aux favoris, ou retire si tous le sont déjà. */
    fun toggleFavorite(ids: Collection<Long>) = run {
        val remove = favorites.allFavorite(ids)
        favorites.setFavorite(ids, !remove)
        channel.send(
            ActionEvent.Message(
                UiText.Plural(if (remove) R.plurals.message_unfavorited else R.plurals.message_favorited, ids.size),
            ),
        )
        channel.send(ActionEvent.Done)
    }

    fun trash(ids: Collection<Long>) = run(busy = true) {
        val items = media.getByIds(ids)
        val result = operations.trash(items)
        report(result, R.plurals.message_trashed, undo = if (operations.hasSystemTrash) ({ restore(items) }) else null)
    }

    fun restore(items: List<MediaItem>) = run(busy = true) {
        report(operations.restore(items), R.plurals.message_restored)
    }

    fun requestAddToAlbum(ids: Collection<Long>) {
        _dialog.value = ActionDialog.AddToAlbum(ids.toList())
    }

    fun addToAlbum(ids: List<Long>, album: Album.User) = run {
        _dialog.value = null
        albums.add(album.id, ids)
        channel.send(ActionEvent.Message(UiText.Plural(R.plurals.message_added_to_album, ids.size, listOf(ids.size, album.name))))
        channel.send(ActionEvent.Done)
    }

    fun createAlbumAndAdd(name: String, ids: List<Long>) = run {
        _dialog.value = null
        val albumId = albums.create(name)
        albums.add(albumId, ids)
        channel.send(ActionEvent.Message(UiText.Plural(R.plurals.message_added_to_album, ids.size, listOf(ids.size, name.trim()))))
        channel.send(ActionEvent.Done)
    }

    fun requestCopy(ids: Collection<Long>) {
        _dialog.value = ActionDialog.CopyTo(ids.toList())
    }

    fun requestMove(ids: Collection<Long>) {
        _dialog.value = ActionDialog.MoveTo(ids.toList())
    }

    fun copyTo(ids: List<Long>, target: FolderTarget) = run(busy = true) {
        _dialog.value = null
        report(operations.copyTo(media.getByIds(ids), target), R.plurals.message_copied, target.name)
    }

    fun moveTo(ids: List<Long>, target: FolderTarget) = run(busy = true) {
        _dialog.value = null
        report(operations.moveTo(media.getByIds(ids), target), R.plurals.message_moved, target.name)
    }

    fun requestRename(id: Long) = run {
        media.get(id)?.let { _dialog.value = ActionDialog.Rename(it) }
    }

    fun rename(item: MediaItem, newName: String) = run(busy = true) {
        _dialog.value = null
        when (operations.rename(item, newName)) {
            is OperationResult.Success -> {
                channel.send(ActionEvent.Message(UiText.Res(R.string.message_renamed)))
                channel.send(ActionEvent.Done)
            }
            OperationResult.Cancelled -> Unit
            else -> channel.send(ActionEvent.Message(UiText.Res(R.string.message_operation_failed)))
        }
    }

    fun requestMoveToVault(ids: Collection<Long>) = run {
        _dialog.value = ActionDialog.MoveToVault(ids.toList(), vaultConfigured = vaultPins.hasPin.first())
    }

    /** Chiffre les médias dans le coffre, dans l'album [destination], puis retire les originaux de la galerie. */
    fun moveToVault(ids: List<Long>, destination: VaultDestination = VaultDestination.None) = run(busy = true) {
        _dialog.value = null
        relockIfTemporary()
        val request = when (destination) {
            VaultDestination.None -> VaultImportRequest.Selection(ids, albumId = null)
            is VaultDestination.Album -> VaultImportRequest.Selection(ids, destination.id)
            is VaultDestination.NewAlbum -> vault.createAlbum(destination.name).let { VaultImportRequest.Selection(ids, it, createdAlbumId = it) }
        }
        followImport(request, ids.size)
    }

    fun requestMoveAlbumToVault(album: AlbumSource) = run {
        val items = media.list(album.query)
        if (items.isEmpty()) return@run
        val videos = items.count { it.isVideo }
        _dialog.value = ActionDialog.MoveAlbumToVault(album, items.size - videos, videos, vaultPins.hasPin.first())
    }

    /**
     * Déplace tout [album] dans un album du coffre du même nom. Un dossier y reste lié : ses
     * nouveaux médias seront proposés dans le coffre. Un album de l'application vidé est
     * supprimé : il ne resterait de lui, dans la galerie, que son nom.
     */
    fun moveAlbumToVault(album: AlbumSource, count: Int) = run(busy = true) {
        _dialog.value = null
        val request = when (album) {
            is AlbumSource.Folder -> VaultImportRequest.Folder(album.bucketId, album.name)
            is AlbumSource.User -> vault.createAlbum(album.name).let { VaultImportRequest.UserAlbum(album.id, it, createdAlbumId = it) }
        }
        followImport(request, count)
    }

    /** Ajoute à l'album du coffre [albumId] les médias arrivés dans son dossier d'origine depuis le dernier import. */
    fun moveNewToVault(albumId: Long, count: Int) = run(busy = true) {
        followImport(VaultImportRequest.NewFromSource(albumId), count)
    }

    /** Vrai une fois l'attente masquée (« Continuer en arrière-plan ») : l'import se poursuit sans elle. */
    private var importProgressHidden = false

    /** Cesse d'afficher la progression de l'import ; son bilan s'affichera tout de même. */
    fun hideProgress() {
        importProgressHidden = true
        _progress.value = null
    }

    /**
     * Confie l'import à WorkManager, qui le mène à terme même si l'écran se ferme, puis en suit
     * la progression et le bilan tant que l'écran est là.
     */
    private suspend fun followImport(request: VaultImportRequest, count: Int) {
        importProgressHidden = false
        importProgress(VaultImportState.Running(0, count))
        try {
            vaultImports.observe(vaultImports.enqueue(request)).collect { state ->
                if (state !is VaultImportState.Finished) {
                    importProgress(state)
                    return@collect
                }
                _progress.value = null
                val result = state.result
                if (request.wholeAlbum && result is OperationResult.Success && result.count > 0) {
                    // Hors du coffre (écran non protégé) : le nom de l'album n'apparaît pas.
                    channel.send(ActionEvent.AlbumMoved(UiText.Plural(R.plurals.vault_album_moved, result.count, listOf(result.count))))
                } else {
                    report(result, R.plurals.message_moved_to_vault)
                }
            }
        } finally {
            _progress.value = null
        }
    }

    private fun importProgress(state: VaultImportState) {
        if (importProgressHidden) return
        val label = when {
            state is VaultImportState.Queued -> UiText.Res(R.string.vault_import_waiting)
            state is VaultImportState.Running && state.confirming -> UiText.Res(R.string.vault_import_confirm)
            state is VaultImportState.Running -> UiText.Res(R.string.vault_progress, listOf(state.done, state.total))
            else -> return
        }
        _progress.value = ActionProgress(label, background = true)
    }

    /** Retire des médias d'un album virtuel (les fichiers ne sont pas touchés). */
    fun removeFromAlbum(albumId: Long, ids: Collection<Long>) = run {
        albums.remove(albumId, ids)
        channel.send(ActionEvent.Message(UiText.Plural(R.plurals.message_removed_from_album, ids.size)))
        channel.send(ActionEvent.Done)
    }

    private suspend fun report(
        result: OperationResult,
        pluralRes: Int,
        target: String? = null,
        undo: (() -> Unit)? = null,
    ) {
        val args: (Int) -> List<Any> = { count -> if (target != null) listOf(count, target) else listOf(count) }
        when (result) {
            is OperationResult.Success -> {
                channel.send(ActionEvent.Message(UiText.Plural(pluralRes, result.count, args(result.count)), undo))
                channel.send(ActionEvent.Done)
            }
            is OperationResult.Partial -> {
                channel.send(
                    ActionEvent.Message(
                        UiText.Res(R.string.message_partial, listOf(result.succeeded, result.succeeded + result.failed)),
                    ),
                )
                channel.send(ActionEvent.Done)
            }
            OperationResult.Cancelled -> Unit
            is OperationResult.Failure -> channel.send(ActionEvent.Message(UiText.Res(R.string.message_operation_failed)))
        }
    }

    private fun run(busy: Boolean = false, block: suspend () -> Unit): Job = scope.launch {
        if (busy) _busy.value = true
        try {
            block()
        } finally {
            if (busy) _busy.value = false
        }
    }
}
