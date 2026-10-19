package app.focale.core.model

import androidx.compose.runtime.Immutable

/** Album affiché dans l'onglet Albums. */
@Immutable
sealed interface Album {
    val name: String
    val count: Int
    val cover: MediaItem?

    /** Album dossier, issu du BUCKET_ID MediaStore. */
    @Immutable
    data class Folder(
        val bucketId: Long,
        override val name: String,
        val relativePath: String,
        override val count: Int,
        override val cover: MediaItem?,
    ) : Album

    /** Album virtuel, créé dans l'application (les fichiers ne bougent pas). */
    @Immutable
    data class User(
        val id: Long,
        override val name: String,
        override val count: Int,
        override val cover: MediaItem?,
    ) : Album
}

/** Dossier de destination pour copier / déplacer des médias. */
@Immutable
data class FolderTarget(val name: String, val relativePath: String)
