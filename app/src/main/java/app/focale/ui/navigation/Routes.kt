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
