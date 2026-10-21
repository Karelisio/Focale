package app.focale.ui.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.media3.common.Player

/** Sens d'un saut dans une vidéo : la moitié gauche recule, la droite avance. */
internal enum class SeekDirection { BACKWARD, FORWARD }

/** Calculs des sauts dans une vidéo, sans état : côté touché et position visée. */
internal object VideoSeekMath {
    /** Saut d'un double appui, puis de chaque appui rapproché qui le suit. */
    const val STEP_MS = 10_000L

    /** [STEP_MS] en secondes, pour les libellés. */
    const val STEP_SECONDS = (STEP_MS / 1_000L).toInt()

    /**
     * Écart maximal entre deux appuis d'une même série, d'un relâchement au suivant, et durée
     * d'affichage du cumul : de quoi enchaîner les appuis à son rythme (le double appui en laisse
     * 300 ms) sans retarder longtemps l'appui simple qui affiche les commandes.
     */
    const val SERIES_TIMEOUT_MS = 700L

    /**
     * Côté touché à l'abscisse [x] d'une surface large de [width] : la moitié droite avance,
     * même en écriture de droite à gauche, comme l'avance rapide des lecteurs vidéo.
     */
    fun direction(x: Float, width: Float): SeekDirection =
        if (x < width / 2f) SeekDirection.BACKWARD else SeekDirection.FORWARD

    /**
     * Position visée en sautant de [offsetMs] depuis [positionMs] : jamais avant le début, ni
     * après la fin ([durationMs] ; négative tant qu'elle est inconnue, seul le début borne alors).
     */
    fun target(positionMs: Long, offsetMs: Long, durationMs: Long): Long {
        val target = (positionMs + offsetMs).coerceAtLeast(0L)
        return if (durationMs >= 0L) target.coerceAtMost(durationMs) else target
    }
}

/**
 * Série de sauts d'une vidéo (pure, testable). Le double appui l'ouvre (10 s), puis chaque appui
 * qui suit le précédent de moins de [VideoSeekMath.SERIES_TIMEOUT_MS] saute à son tour, sans
 * attendre de nouveau double appui : +10, +20, +30 s… du même côté ; de l'autre côté, le cumul
 * repart de 10 s dans l'autre sens. Un appui qui ne déplacerait rien (lecture déjà au début ou à
 * la fin) n'ajoute rien au cumul, mais garde la série ouverte.
 */
internal class SeekSeries {
    /** Sens de la série ; null avant le premier saut et après [end]. */
    var direction: SeekDirection? = null
        private set

    /** Sauts effectués dans [direction] depuis l'ouverture de la série ou le dernier changement de côté. */
    var steps = 0
        private set

    /** Instant (horloge des évènements tactiles) du dernier appui de la série. */
    private var lastTime = 0L

    /** Cumul affiché, en secondes, dans [direction]. */
    val seconds: Int get() = steps * VideoSeekMath.STEP_SECONDS

    /** Vrai si un appui fini à [time] prolonge la série : un appui simple suffit alors. */
    fun continues(time: Long): Boolean = direction != null && time - lastTime in 0..VideoSeekMath.SERIES_TIMEOUT_MS

    /**
     * Saut vers [direction] d'un appui fini à [time], depuis [positionMs] d'une vidéo de
     * [durationMs] (négative si inconnue) : position visée, ou null si la lecture est déjà au
     * bout dans ce sens.
     */
    fun seek(direction: SeekDirection, positionMs: Long, durationMs: Long, time: Long): Long? {
        val open = continues(time)
        val offset = if (direction == SeekDirection.FORWARD) VideoSeekMath.STEP_MS else -VideoSeekMath.STEP_MS
        val target = VideoSeekMath.target(positionMs, offset, durationMs)
        if (target == positionMs) {
            if (open && direction == this.direction) lastTime = time
            return null
        }
        if (!open || direction != this.direction) steps = 0
        this.direction = direction
        steps++
        lastTime = time
        return target
    }

    /** Fin de la série : l'appui suivant redevient un appui simple, ou le début d'un double appui. */
    fun end() {
        direction = null
        steps = 0
    }
}

/** Cumul affiché d'une série : sens, secondes, et numéro d'appui (chaque appui repousse l'effacement). */
@Immutable
internal data class SeekFeedback(val direction: SeekDirection, val seconds: Int, val tap: Int = 0)

/**
 * Sauts de ±10 s d'une page vidéo, par double appui puis appuis rapprochés ([videoTapGestures])
 * ou par les actions TalkBack : la série en cours ([SeekSeries]) et son retour visuel
 * ([feedback], effacé par [hide]). Le lecteur est celui de la page courante, branché comme la
 * mémoire de lecture ([attach], [detach]).
 */
@Stable
internal class VideoSeekState {
    private var player: Player? = null
    private val series = SeekSeries()
    private var taps = 0

    var feedback: SeekFeedback? by mutableStateOf(null)
        private set

    fun attach(player: Player) {
        this.player = player
    }

    fun detach() {
        player = null
        hide()
    }

    /** Vrai si un appui fini à [time] prolonge la série en cours (voir [SeekSeries.continues]). */
    fun continues(time: Long): Boolean = player != null && series.continues(time)

    /** Saut vers [direction] à [time] (horloge des évènements tactiles) ; faux si rien n'a bougé. */
    fun seek(direction: SeekDirection, time: Long): Boolean {
        val player = player?.takeIf { it.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) } ?: return false
        val target = series.seek(direction, player.currentPosition, player.duration, time)
        if (target != null) player.seekTo(target)
        // Série ouverte (saut, ou appui de plus contre une borne) : cumul à jour, effacement repoussé.
        val shown = series.direction
        if (shown != null && series.continues(time)) feedback = SeekFeedback(shown, series.seconds, ++taps)
        return target != null
    }

    /** Série finie : le cumul s'efface. */
    fun hide() {
        series.end()
        feedback = null
    }
}

/**
 * Appuis sur une page vidéo. Un appui simple appelle [onTap] (commandes) une fois le délai du
 * double appui écoulé, comme sur une photo ; un double appui sur une moitié saute de 10 s, puis
 * chaque appui rapproché aussitôt ([VideoSeekState.seek]). Ne comptent pas : un doigt qui glisse
 * au-delà du seuil de déplacement (balayage du pager, glisser pour fermer, que les parents
 * gardent), un second doigt, ni un appui déjà pris par les commandes (boutons, curseur).
 */
internal fun Modifier.videoTapGestures(state: VideoSeekState, onTap: () -> Unit): Modifier =
    pointerInput(state) {
        awaitEachGesture {
            var down = awaitFirstDown()
            while (true) {
                down.consume()
                val up = awaitTap(down) ?: return@awaitEachGesture
                val direction = VideoSeekMath.direction(down.position.x, size.width.toFloat())
                // Série en cours : un appui simple saute aussitôt.
                if (state.continues(up.uptimeMillis)) {
                    state.seek(direction, up.uptimeMillis)
                    return@awaitEachGesture
                }
                val second = awaitSecondDown(up)
                when {
                    second == null -> onTap()
                    // Autre moitié : deux appuis simples, dont le second peut encore ouvrir un double appui.
                    VideoSeekMath.direction(second.position.x, size.width.toFloat()) != direction -> {
                        onTap()
                        down = second
                        continue
                    }
                    else -> {
                        second.consume()
                        // Second appui devenu glissement : le premier reste un appui simple.
                        val secondUp = awaitTap(second)
                        if (secondUp == null) onTap() else state.seek(direction, secondUp.uptimeMillis)
                    }
                }
                return@awaitEachGesture
            }
        }
    }

/**
 * Relâchement, consommé, de l'appui commencé par [down] ; null si le doigt a glissé au-delà du
 * seuil de déplacement, si un second doigt s'est posé ou si un parent a pris le geste.
 */
private suspend fun AwaitPointerEventScope.awaitTap(down: PointerInputChange): PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.singleOrNull()?.takeIf { it.id == down.id } ?: return null
        if (change.changedToUp()) {
            change.consume()
            return change
        }
        if (change.isConsumed || change.isOutOfBounds(size, extendedTouchPadding) ||
            (change.position - down.position).getDistance() > viewConfiguration.touchSlop
        ) {
            return null
        }
        // Mouvement pris ensuite par un parent (balayage, glisser pour fermer) : passe finale.
        if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) return null
    }
}

/** Second appui d'un double appui, posé dans le délai système après [firstUp] ; null sinon. */
private suspend fun AwaitPointerEventScope.awaitSecondDown(firstUp: PointerInputChange): PointerInputChange? =
    withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
        // Rebond du même appui, trop tôt pour un double appui : ignoré.
        val earliest = firstUp.uptimeMillis + viewConfiguration.doubleTapMinTimeMillis
        var change: PointerInputChange
        do {
            change = awaitFirstDown()
        } while (change.uptimeMillis < earliest)
        change
    }
