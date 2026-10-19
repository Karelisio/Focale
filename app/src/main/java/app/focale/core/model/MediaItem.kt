package app.focale.core.model

import android.net.Uri
import androidx.compose.runtime.Immutable

/** Nature d'un média indexé. */
enum class MediaType { IMAGE, VIDEO }

/**
 * Média tel que manipulé par l'interface : une ligne de l'index MediaStore enrichie
 * de son URI de contenu.
 *
 * Les dates sont exprimées en millisecondes depuis l'époque Unix.
 */
@Immutable
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val type: MediaType,
    val mimeType: String,
    val displayName: String,
    val relativePath: String,
    val bucketId: Long,
    val bucketName: String,
    val dateTaken: Long,
    val dateModified: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val durationMs: Long,
    val isGif: Boolean,
    val isScreenshot: Boolean,
) {
    val isVideo: Boolean get() = type == MediaType.VIDEO

    /** Largeur et hauteur une fois l'orientation EXIF appliquée. */
    val displayWidth: Int get() = if (orientation % 180 == 0) width else height
    val displayHeight: Int get() = if (orientation % 180 == 0) height else width

    /** Rapport largeur / hauteur affiché, 1 si les dimensions sont inconnues. */
    val aspectRatio: Float
        get() = if (displayWidth > 0 && displayHeight > 0) {
            displayWidth.toFloat() / displayHeight
        } else {
            1f
        }
}
