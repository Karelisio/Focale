package app.focale.video

import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Plage conservée d'une vidéo, en millisecondes (logique pure, testable).
 *
 * Les poignées restent dans la vidéo et à au moins [MIN_LENGTH_MS] l'une de l'autre ; une vidéo
 * plus courte que cette durée ne peut pas être coupée et sa plage reste la vidéo entière.
 */
data class TrimRange(val durationMs: Long, val startMs: Long = 0, val endMs: Long = durationMs) {

    init {
        require(durationMs >= 0 && startMs in 0..endMs && endMs <= durationMs) {
            "Plage invalide : $startMs–$endMs sur $durationMs ms"
        }
    }

    val lengthMs: Long get() = endMs - startMs
    val trimsStart: Boolean get() = startMs > 0
    val trimsEnd: Boolean get() = endMs < durationMs

    /** Vidéo entière : rien à couper. */
    val isFull: Boolean get() = !trimsStart && !trimsEnd

    /** Vidéo assez longue pour être coupée. */
    val canTrim: Boolean get() = durationMs > MIN_LENGTH_MS

    /** Déplace le début, entre le début de la vidéo et la fin moins la durée minimale. */
    fun withStart(startMs: Long): TrimRange {
        if (!canTrim) return this
        return copy(startMs = startMs.coerceIn(0L, (endMs - MIN_LENGTH_MS).coerceAtLeast(0L)))
    }

    /** Déplace la fin, entre le début plus la durée minimale et la fin de la vidéo. */
    fun withEnd(endMs: Long): TrimRange {
        if (!canTrim) return this
        return copy(endMs = endMs.coerceIn(minOf(startMs + MIN_LENGTH_MS, durationMs), durationMs))
    }

    /**
     * Même plage pour une durée corrigée (celle mesurée par le lecteur, plus sûre que celle de
     * MediaStore) : une fin laissée au bout de la vidéo y reste.
     */
    fun withDuration(durationMs: Long): TrimRange = of(durationMs, startMs, if (trimsEnd) endMs else durationMs)

    /** [positionMs] ramenée dans la plage. */
    fun clamp(positionMs: Long): Long = positionMs.coerceIn(startMs, endMs)

    /** Position d'où lancer la lecture : le début de la plage si [positionMs] en sort ou en atteint la fin. */
    fun playbackStart(positionMs: Long): Long =
        if (positionMs < startMs || positionMs >= endMs - END_TOLERANCE_MS) startMs else positionMs

    companion object {
        /** Durée minimale conservée. */
        const val MIN_LENGTH_MS = 1_000L

        /** En deçà de la fin de plage, une lecture repart du début. */
        const val END_TOLERANCE_MS = 100L

        /** Plage bornée et valide la plus proche de [startMs]–[endMs]. */
        fun of(durationMs: Long, startMs: Long, endMs: Long): TrimRange =
            TrimRange(durationMs.coerceAtLeast(0L)).withStart(startMs).withEnd(endMs)
    }
}

/** Mise en forme des temps de coupe, au dixième de seconde (tronqué). */
object TrimFormat {

    /** Position : « 0:07,4 » (fr), « 0:07.4 » (en), « 1:02:05,4 » au-delà d'une heure. */
    fun position(ms: Long, locale: Locale): String {
        val tenths = ms.coerceAtLeast(0L) / 100
        return clock(tenths / 10) + separator(locale) + (tenths % 10)
    }

    /** Durée conservée : « 7,4 s » sous la minute, sinon « 1:05 » ou « 1:02:05 ». */
    fun length(ms: Long, locale: Locale): String {
        val tenths = ms.coerceAtLeast(0L) / 100
        return if (tenths < 600) "${tenths / 10}${separator(locale)}${tenths % 10} s" else clock(tenths / 10)
    }

    private fun clock(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = totalSeconds / 60 % 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    private fun separator(locale: Locale): Char = DecimalFormatSymbols.getInstance(locale).decimalSeparator
}
