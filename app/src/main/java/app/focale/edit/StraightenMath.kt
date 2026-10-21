package app.focale.edit

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.withSign

/** Retour haptique d'un changement d'angle : passage de 0°, d'un degré entier, ou rien. */
enum class StraightenTick { NONE, DEGREE, ZERO }

/**
 * Géométrie du redressement (pure, testable). L'image, déjà tournée par quarts de tour et
 * retournée s'il le faut, pivote de [EditState.straighten] degrés (sens horaire) autour du
 * centre de son cadre, qui garde ses dimensions `width` × `height` (en pixels ; seul leur
 * rapport compte). Le recadrage reste normalisé dans ce cadre, comme sans redressement, et
 * doit tenir dans l'image tournée : aucun coin vide.
 *
 * La zone permise, intersection du cadre et de l'image tournée, est convexe et symétrique
 * par rapport au centre : si un rectangle y tient quelque part, il y tient aussi centré. Le
 * plus grand rectangle d'une proportion donnée s'obtient donc centré, en forme close.
 */
object StraightenMath {

    const val MAX_ANGLE = 45f

    /**
     * Rotation des doigts ignorée en deçà, en degrés : rotations parasites d'un pincement. La
     * visionneuse attend 15° ([app.focale.ui.viewer.RotationMath.THRESHOLD]) avant un quart de
     * tour ; un redressement se joue à quelques degrés, d'où un seuil plus court, complété par
     * [PINCH_LOCK].
     */
    const val GESTURE_THRESHOLD = 5f

    /** Écartement relatif des doigts qui, avant le seuil de rotation, fait d'un geste un pincement. */
    const val PINCH_LOCK = 0.12f

    /** Écart (px) entre les bords de l'image redressée et les coins du recadrage, arrondis compris. */
    private const val BLEED = 3f

    /** Tolérance relative des comparaisons (arrondis du calcul en virgule flottante). */
    private const val EPSILON = 1e-4f

    /** Itérations de la dichotomie d'un geste (précision : 1/65 536 du mouvement). */
    private const val SEARCH_STEPS = 16

    /** Angle enregistré : borné à ±[MAX_ANGLE], arrondi au dixième de degré (jamais -0). */
    fun clamp(angle: Float): Float = (angle.coerceIn(-MAX_ANGLE, MAX_ANGLE) * 10f).roundToInt() / 10f

    /** Vrai si [rect] tient dans le cadre et dans l'image tournée de [angle] degrés. */
    fun fits(rect: NormRect, angle: Float, width: Float, height: Float): Boolean {
        if (rect.left < -EPSILON || rect.top < -EPSILON || rect.right > 1f + EPSILON || rect.bottom > 1f + EPSILON) return false
        if (angle == 0f) return true
        val (cos, sin) = rotation(angle)
        val halfWidth = width / 2f * (1f + EPSILON)
        val halfHeight = height / 2f * (1f + EPSILON)
        for (x in floatArrayOf(rect.left, rect.right)) {
            for (y in floatArrayOf(rect.top, rect.bottom)) {
                // Coin ramené dans le repère de l'image (rotation inverse) : il doit y rester.
                val px = (x - 0.5f) * width
                val py = (y - 0.5f) * height
                if (abs(px * cos + py * sin) > halfWidth || abs(py * cos - px * sin) > halfHeight) return false
            }
        }
        return true
    }

    /**
     * Recadrage [reference] ramené dans l'image tournée de [angle] degrés : rendu tel quel s'il
     * y tient ; sinon réduit dans sa proportion juste assez (plus grand rectangle inscrit), puis
     * rapproché du centre du cadre, sur la droite qui l'y relie, seulement si sa position l'exige.
     */
    fun fit(reference: NormRect, angle: Float, width: Float, height: Float): NormRect {
        if (fits(reference, angle, width, height)) return reference
        val (cos, sin) = rotation(angle)
        val c = abs(cos)
        val s = abs(sin)
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        // Demi-dimensions (px) de la référence, puis réduites : même échelle pour toute position.
        val a0 = reference.width * halfWidth
        val b0 = reference.height * halfHeight
        val scale = minOf(1f, halfWidth / a0, halfHeight / b0, halfWidth / (a0 * c + b0 * s), halfHeight / (a0 * s + b0 * c))
        val a = a0 * scale
        val b = b0 * scale
        // Décalage voulu du centre (px) ; chaque contrainte s'écrit |n·d| ≤ marge, d'où la part
        // du décalage gardée.
        val dx = ((reference.left + reference.right) / 2f - 0.5f) * width
        val dy = ((reference.top + reference.bottom) / 2f - 0.5f) * height
        var kept = 1f
        fun limit(projection: Float, room: Float) {
            // Marge jamais négative (arrondis) : un décalage nul est toujours permis.
            val margin = max(room, 0f)
            if (abs(projection) > margin) kept = min(kept, margin / abs(projection))
        }
        limit(dx, halfWidth - a)
        limit(dy, halfHeight - b)
        limit(dx * cos + dy * sin, halfWidth - a * c - b * s)
        limit(dy * cos - dx * sin, halfHeight - a * s - b * c)
        val x = dx * kept
        val y = dy * kept
        return NormRect(0.5f + (x - a) / width, 0.5f + (y - b) / height, 0.5f + (x + a) / width, 0.5f + (y + b) / height)
    }

    /**
     * [CropMath.drag] sans quitter l'image tournée : le mouvement est raccourci juste assez. Un
     * déplacement ou une poignée libre avancent axe par axe, pour glisser le long d'un bord
     * incliné ; avec un format imposé, les deux axes vont ensemble.
     */
    fun drag(
        rect: NormRect,
        handle: CropHandle,
        dx: Float,
        dy: Float,
        ratio: Float?,
        angle: Float,
        width: Float,
        height: Float,
    ): NormRect {
        val moved = CropMath.drag(rect, handle, dx, dy, ratio)
        if (fits(moved, angle, width, height)) return moved
        // Cadre de départ déjà hors de l'image (ne devrait pas arriver) : ramené dedans.
        if (!fits(rect, angle, width, height)) return fit(moved, angle, width, height)
        if (ratio != null && handle != CropHandle.MOVE) return limit(rect, handle, dx, dy, ratio, angle, width, height)
        val horizontal = limit(rect, handle, dx, 0f, ratio, angle, width, height)
        return limit(horizontal, handle, 0f, dy, ratio, angle, width, height)
    }

    /** Plus grande part de (dx, dy) qui garde dans l'image le cadre [rect], qui y est déjà. */
    private fun limit(
        rect: NormRect,
        handle: CropHandle,
        dx: Float,
        dy: Float,
        ratio: Float?,
        angle: Float,
        width: Float,
        height: Float,
    ): NormRect {
        val whole = CropMath.drag(rect, handle, dx, dy, ratio)
        if (fits(whole, angle, width, height)) return whole
        var low = 0f
        var high = 1f
        var best = rect
        repeat(SEARCH_STEPS) {
            val middle = (low + high) / 2f
            val candidate = CropMath.drag(rect, handle, dx * middle, dy * middle, ratio)
            if (fits(candidate, angle, width, height)) {
                low = middle
                best = candidate
            } else {
                high = middle
            }
        }
        return best
    }

    /**
     * Agrandissement de l'image redressée, autour du centre, qui écarte ses bords de [BLEED] px
     * des coins du recadrage : aucun pixel à demi couvert (bord lissé, arrondi au pixel) dans
     * les coins. Imperceptible : 0,4 % pour un aperçu de 1 600 px.
     */
    fun bleed(width: Float, height: Float): Float = 1f + 2f * BLEED / min(width, height)

    /** Angle ajouté par une rotation cumulée des doigts : nul jusqu'au seuil, puis il suit les doigts, sans à-coup. */
    fun gestureAngle(fingers: Float): Float =
        if (abs(fingers) <= GESTURE_THRESHOLD) 0f else fingers - GESTURE_THRESHOLD.withSign(fingers)

    /**
     * Retour haptique en passant de [previous] à [current] : arrivée sur 0° ou passage de 0°,
     * arrivée sur un degré entier ou passage d'un degré entier (quitter une graduation ne
     * compte pas : un aller-retour au même endroit ne vibre qu'une fois).
     */
    fun tick(previous: Float, current: Float): StraightenTick {
        if (current == previous) return StraightenTick.NONE
        val rising = current > previous
        val zero = if (rising) previous < 0f && current >= 0f else previous > 0f && current <= 0f
        val degree = if (rising) floor(current) > floor(previous) else ceil(current) < ceil(previous)
        return when {
            zero -> StraightenTick.ZERO
            degree -> StraightenTick.DEGREE
            else -> StraightenTick.NONE
        }
    }

    private fun rotation(angle: Float): Pair<Float, Float> {
        val radians = Math.toRadians(angle.toDouble())
        return cos(radians).toFloat() to sin(radians).toFloat()
    }
}

/**
 * Geste de redressement à deux doigts, évènement par évènement : la rotation n'est retenue
 * qu'au-delà de [StraightenMath.GESTURE_THRESHOLD], et jamais si les doigts se sont d'abord
 * écartés ou rapprochés de [StraightenMath.PINCH_LOCK] (pincement).
 */
class StraightenGesture {
    private var fingers = 0f
    private var spread = 1f

    var engaged = false
        private set

    var pinched = false
        private set

    /**
     * Ajoute la rotation ([degrees], sens horaire) et l'écartement ([zoom], rapport des
     * distances) d'un évènement ; renvoie l'angle à ajouter à celui du début du geste, ou null
     * tant que le geste n'est pas une rotation.
     */
    fun update(degrees: Float, zoom: Float): Float? {
        if (pinched) return null
        fingers += degrees
        spread *= zoom
        if (!engaged) {
            if (abs(spread - 1f) > StraightenMath.PINCH_LOCK) {
                pinched = true
                return null
            }
            if (StraightenMath.gestureAngle(fingers) == 0f) return null
            engaged = true
        }
        return StraightenMath.gestureAngle(fingers)
    }
}
