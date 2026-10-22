package app.focale.ui.navigation

import androidx.annotation.Keep
import app.focale.core.model.MediaQuery
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ── Destinations racine (plein écran, au-dessus des onglets) ─────────────────────

@Serializable
data object MainRoute

/**
 * Visionneuse ; [query] est la [MediaQuery] encodée en JSON de la grille d'origine. [closeOnBack] :
 * ouverte pour une autre application, le retour referme l'activité (voir [ExternalMediaRoute]).
 */
@Serializable
data class ViewerRoute(val query: String, val mediaId: Long, val closeOnBack: Boolean = false)

/**
 * Photo ou vidéo [uri] reçue d'une autre application : un média de l'index rejoint la visionneuse,
 * un autre fichier s'affiche seul. [closeOnBack] : l'activité a été lancée pour cette ouverture, le
 * retour ramène à l'application appelante plutôt qu'à la timeline.
 */
@Serializable
data class ExternalMediaRoute(val uri: String, val mimeType: String? = null, val closeOnBack: Boolean = false)

/** Éditeur photo. */
@Serializable
data class EditorRoute(val mediaId: Long)

/** Montage vidéo simple : coupe du début et de la fin, son coupé, enregistré en copie. */
@Serializable
data class VideoEditorRoute(val mediaId: Long)

/**
 * Collage des photos [mediaIds] (2 à 9, dans l'ordre de la sélection). [truncated] : la sélection
 * en comptait davantage, l'écran le signale.
 */
@Serializable
data class CollageRoute(val mediaIds: List<Long>, val truncated: Boolean = false)

/** Diaporama des médias de [query], à partir de [startId] (premier média si absent). */
@Serializable
data class SlideshowRoute(val query: String, val startId: Long? = null)

/** Coffre-fort : création du code, déverrouillage et contenu chiffré. */
@Serializable
data object VaultRoute

/** Visionneuse du coffre, ouverte sur l'élément [itemId] : parcourt l'album [albumId], ou tout le coffre. */
@Serializable
data class VaultViewerRoute(val itemId: Long, val albumId: Long? = null)

/** Choix des médias de la galerie à déplacer dans le coffre, dans l'album [albumId] le cas échéant. */
@Serializable
data class VaultPickerRoute(val albumId: Long? = null)

/** Album du coffre. */
@Serializable
data class VaultAlbumRoute(val albumId: Long)

/** Corbeille du coffre. */
@Serializable
data object VaultTrashRoute

/** Déverrouillage du coffre seul, depuis la galerie : retour à l'écran d'origine une fois fait. */
@Serializable
data object VaultUnlockRoute

// ── Destinations des onglets ─────────────────────────────────────────────────────

@Serializable
data object PhotosRoute

@Serializable
data object AlbumsRoute

@Serializable
data object SearchRoute

@Serializable
data object MoreRoute

/** Nature d'une collection affichée par l'écran générique de grille. */
@Keep
@Serializable
enum class CollectionKind { QUERY, FOLDER, USER_ALBUM, FAVORITES }

/**
 * Grille générique (album, favoris, catégorie, résultats…).
 * @param query [MediaQuery] encodée en JSON.
 */
@Serializable
data class CollectionRoute(
    val title: String,
    val query: String,
    val kind: CollectionKind = CollectionKind.QUERY,
    val albumId: Long? = null,
)

/** Toutes les personnes (visages regroupés sur l'appareil). */
@Serializable
data object PeopleRoute

/** Photos d'une personne, avec ses actions (nom, vignette, fusion…). */
@Serializable
data class PersonRoute(val personId: Long)

/** Revue des visages d'une personne, les moins ressemblants d'abord (retrait, déplacement). */
@Serializable
data class PersonFacesRoute(val personId: Long)

@Serializable
data object TrashRoute

@Serializable
data object CleanupRoute

@Serializable
data object MapRoute

@Serializable
data object SettingsRoute

@Serializable
data object AboutRoute

/** Sérialisation compacte des requêtes passées en argument de navigation. */
object RouteCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(query: MediaQuery): String = json.encodeToString(MediaQuery.serializer(), query)

    fun decode(value: String): MediaQuery =
        runCatching { json.decodeFromString(MediaQuery.serializer(), value) }.getOrDefault(MediaQuery())
}
