package app.focale.cast

import androidx.compose.runtime.Immutable
import app.focale.BuildConfig
import app.focale.cast.display.ExternalDisplay
import app.focale.cast.dlna.DiscoveryProblem
import app.focale.cast.dlna.DlnaRenderer

/** Appareil qui reçoit la diffusion. */
@Immutable
sealed interface CastTarget {
    /** Écran externe : la visionneuse y affiche le média en cours (API `Presentation`). */
    data class Screen(val display: ExternalDisplay) : CastTarget

    /** TV du réseau local (DLNA) : elle lit le média que Focale lui sert. */
    data class Tv(val renderer: DlnaRenderer) : CastTarget
}

/** Étape de la diffusion vers une TV. */
enum class TvStatus {
    /** Serveur local en route, premier média en préparation. */
    CONNECTING,

    /** La TV montre le média en cours (ou attend le prochain). */
    READY,
}

/** Tout ce que montrent le bouton « Diffuser » et sa feuille. */
@Immutable
data class CastState(
    val screens: List<ExternalDisplay> = emptyList(),
    val tvs: List<DlnaRenderer> = emptyList(),
    val searching: Boolean = false,
    val problem: DiscoveryProblem? = null,
    val target: CastTarget? = null,
    val tvStatus: TvStatus? = null,
    /** Volume de la TV (0 à 100), null si elle ne le donne pas. */
    val volume: Int? = null,
    /** Variante standard : la recherche des TV existe. */
    val tvSupported: Boolean = BuildConfig.TV_CAST_AVAILABLE,
) {
    /** Bouton visible : un écran ou une TV à proposer, une recherche en cours ou une diffusion. */
    val available: Boolean get() = target != null || screens.isNotEmpty() || tvs.isNotEmpty() || searching
}

/** Lecture d'une vidéo sur la TV, pour les commandes du téléphone ([RemotePlayer]). */
@Immutable
data class TvPlayback(
    val mediaId: Long,
    /** Média envoyé, la TV ne joue pas encore. */
    val loading: Boolean = true,
    val playing: Boolean = false,
    /** Lecture arrêtée sur la TV (fin de la vidéo, télécommande). */
    val stopped: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** Moment de la mesure de [positionMs] (`elapsedRealtime`). */
    val measuredAt: Long = 0L,
)

/** Messages ponctuels de la diffusion, affichés par l'écran en cours. */
sealed interface CastEvent {
    /** La TV refuse ce média (format vidéo, taille…). */
    data class Refused(val video: Boolean) : CastEvent

    /** La TV ne répond plus : la diffusion s'est arrêtée. */
    data class Lost(val name: String) : CastEvent

    /** Ni Wi-Fi ni Ethernet : impossible de joindre la TV. */
    data object NoNetwork : CastEvent

    /** Échec d'envoi (média illisible, TV qui refuse la commande). */
    data object Failed : CastEvent
}
