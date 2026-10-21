package app.focale.ui.actions

import app.focale.core.model.Album
import app.focale.core.settings.SettingsRepository
import app.focale.data.AlbumRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Album de l'onglet Albums, tel qu'il passe en entier dans le coffre. */
val Album.source: AlbumSource
    get() = when (this) {
        is Album.User -> AlbumSource.User(id, name)
        is Album.Folder -> AlbumSource.Folder(bucketId, name)
    }

/**
 * Options d'un album entier de la galerie, communes au menu de l'album ouvert et à l'appui long
 * sur sa carte (onglet Albums) : renommer ou supprimer un album de l'application, masquer un
 * dossier de la timeline ou l'y rendre, passer l'album dans le coffre ([media] en demande la
 * confirmation, puis en suit l'import jusqu'au bilan). Propre à chaque ViewModel, qui lui
 * fournit sa portée.
 */
class AlbumActions(
    private val scope: CoroutineScope,
    private val albums: AlbumRepository,
    private val settings: SettingsRepository,
    private val media: MediaActions,
) {
    fun rename(albumId: Long, name: String) {
        scope.launch { albums.rename(albumId, name) }
    }

    /** Supprime l'album de l'application ; ses photos et vidéos restent sur l'appareil. */
    fun delete(albumId: Long, onDeleted: () -> Unit = {}) {
        scope.launch {
            albums.delete(albumId)
            onDeleted()
        }
    }

    /** Masque le dossier [bucketId] de la timeline, ou l'y rend ; il reste dans Albums. */
    fun setFolderHidden(bucketId: Long, hidden: Boolean) {
        scope.launch { settings.setFolderHidden(bucketId, hidden) }
    }

    /** Tout l'album, filtres et tri d'une grille mis à part. */
    fun moveToVault(album: AlbumSource) {
        media.requestMoveAlbumToVault(album)
    }
}
