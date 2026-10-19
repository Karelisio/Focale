package app.focale.ui.navigation

import androidx.annotation.Keep
import app.focale.core.model.MediaQuery
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ── Destinations racine (plein écran, au-dessus des onglets) ─────────────────────

@Serializable
data object MainRoute

/** Visionneuse ; [query] est la [MediaQuery] encodée en JSON de la grille d'origine. */
@Serializable
data class ViewerRoute(val query: String, val mediaId: Long)

/** Éditeur photo. */
@Serializable
data class EditorRoute(val mediaId: Long)

/** Diaporama des médias de [query], à partir de [startId] (premier média si absent). */
@Serializable
data class SlideshowRoute(val query: String, val startId: Long? = null)

/** Coffre-fort : création du code, déverrouillage et contenu chiffré. */
@Serializable
data object VaultRoute

/** Visionneuse du coffre, ouverte sur l'élément [itemId]. */
@Serializable
data class VaultViewerRoute(val itemId: Long)

/** Choix des médias de la galerie à déplacer dans le coffre. */
@Serializable
data object VaultPickerRoute

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
