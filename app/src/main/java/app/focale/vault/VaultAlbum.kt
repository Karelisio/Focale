package app.focale.vault

import androidx.compose.runtime.Immutable
import app.focale.core.database.VaultAlbumRow

/** Album du coffre-fort, tel qu'affiché une fois le coffre déverrouillé. */
@Immutable
data class VaultAlbum(
    val id: Long,
    val name: String,
    val count: Int,
    /** Miniature chiffrée de la couverture (élément le plus récent), null si aucune. */
    val coverThumbFileName: String?,
    /** Dossier d'origine (`RELATIVE_PATH`) d'un album-dossier, null sinon. */
    val sourcePath: String?,
) {
    /** Nom du dossier d'origine, comme dans l'onglet Albums. */
    val sourceName: String?
        get() = sourcePath?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
}

fun VaultAlbumRow.toModel(): VaultAlbum = VaultAlbum(
    id = album.id,
    name = album.name,
    count = count,
    coverThumbFileName = coverThumb,
    sourcePath = album.sourcePath.takeIf { album.sourceBucketId != null },
)

/** Album du coffre qui reçoit un import ou un déplacement. */
@Immutable
sealed interface VaultDestination {
    /** Hors album : « Tous les éléments » seulement. */
    data object None : VaultDestination

    data class Album(val id: Long) : VaultDestination

    /** Album créé pour l'occasion. */
    data class NewAlbum(val name: String) : VaultDestination
}
