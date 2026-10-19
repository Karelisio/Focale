package app.focale.ui.navigation

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
