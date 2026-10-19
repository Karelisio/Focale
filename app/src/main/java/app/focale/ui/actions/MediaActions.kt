package app.focale.ui.actions

import android.content.Intent
import androidx.compose.runtime.Immutable
import app.focale.R
import app.focale.core.model.Album
import app.focale.core.model.FolderTarget
import app.focale.core.model.MediaItem
import app.focale.core.util.UiText
import app.focale.data.AlbumRepository
import app.focale.data.FavoriteRepository
import app.focale.data.MediaOperations
import app.focale.data.MediaRepository
import app.focale.data.MediaSharer
import app.focale.data.OperationResult
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Dialogue ouvert par une action (choix d'album, de dossier, renommage…). */
@Immutable
sealed interface ActionDialog {
    data class AddToAlbum(val ids: List<Long>) : ActionDialog
    data class CopyTo(val ids: List<Long>) : ActionDialog
    data class MoveTo(val ids: List<Long>) : ActionDialog
    data class Rename(val item: MediaItem) : ActionDialog
}

/** Événement ponctuel à traiter par l'interface. */
sealed interface ActionEvent {
    data class Share(val intent: Intent) : ActionEvent
    data class Message(val text: UiText, val undo: (() -> Unit)? = null) : ActionEvent

    /** L'action a abouti : l'écran peut quitter le mode sélection. */
    data object Done : ActionEvent
}

/**
 * Actions communes à toutes les grilles et à la visionneuse : partager, favoris,
 * albums, corbeille, copier, déplacer, renommer.
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
) {
    private lateinit var scope: CoroutineScope

    private val _dialog = MutableStateFlow<ActionDialog?>(null)
    val dialog: StateFlow<ActionDialog?> = _dialog.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val channel = Channel<ActionEvent>(Channel.BUFFERED)
    val events: Flow<ActionEvent> = channel.receiveAsFlow()

    val userAlbums: Flow<List<Album.User>> get() = albums.observeUserAlbums()
    val folders: Flow<List<Album.Folder>> get() = albums.observeFolders()

    fun bind(scope: CoroutineScope) {
        this.scope = scope
    }

    fun dismissDialog() {
        _dialog.value = null
    }

    fun share(ids: Collection<Long>) = run {
        sharer.shareIntent(media.getByIds(ids))?.let { channel.send(ActionEvent.Share(it)) }
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
