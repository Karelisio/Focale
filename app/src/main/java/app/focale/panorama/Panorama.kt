package app.focale.panorama

import kotlin.math.abs
import kotlin.math.min

/** Vue proposée pour une photo panoramique. */
enum class PanoramaKind {
    /** Photo 360° (sphère complète ou partielle) : vue immersive, on regarde autour de soi. */
    SPHERE,

    /** Panorama à plat : l'image remplit la hauteur de l'écran et se parcourt de gauche à droite. */
    FLAT,
}

/**
 * Partie de la sphère couverte par la photo, en degrés. Longitudes comptées depuis le centre du
 * panorama complet, croissantes vers la droite ; [east] peut dépasser 180 quand la photo
 * enjambe la couture. Latitudes : +90 au zénith, -90 au nadir.
 */
data class SphereArea(val west: Double, val east: Double, val north: Double, val south: Double) {
    /** Couverture horizontale, en degrés. */
    val width: Double get() = east - west

    /** Couverture verticale, en degrés. */
    val height: Double get() = north - south

    /** Tour complet : pas de bord à gauche ni à droite. */
    val wrapsAround: Boolean get() = width >= FULL_TURN - 0.5

    val centerLongitude: Double get() = (west + east) / 2

    companion object {
        const val FULL_TURN = 360.0
        val FULL = SphereArea(west = -180.0, east = 180.0, north = 90.0, south = -90.0)
    }
}

/**
 * Photo panoramique et vue à lui proposer.
 *
 * @property area zone couverte ; pour un panorama à plat sans GPano, couverture estimée.
 * @property initialYaw longitude (degrés) au centre de la vue initiale.
 * @property initialPitch latitude (degrés) au centre de la vue initiale.
 * @property initialHorizontalFov champ horizontal initial demandé par le fichier, s'il y en a un.
 */
data class Panorama(
    val mediaId: Long,
    val kind: PanoramaKind,
    val area: SphereArea,
    val initialYaw: Double,
    val initialPitch: Double,
    val initialHorizontalFov: Double? = null,
)

/**
 * Choix de la vue d'une photo d'après ses propriétés GPano et ses dimensions affichées
 * (orientation EXIF appliquée) :
 * - sphère : GPano équirectangulaire, visionneuse panoramique non refusée, couverture verticale
 *   d'au moins [SPHERE_MIN_HEIGHT] degrés (la moitié de la sphère) ; partielle, seule la zone
 *   couverte sera texturée ;
 * - à plat : GPano sur une image plus large que haute qui n'est pas une sphère (panorama
 *   horizontal partiel, projection cylindrique…), ou, sans GPano, photo d'au moins
 *   [FLAT_MIN_RATIO]:1 ;
 * - sinon, rien : photo ordinaire.
 *
 * Des GPano qui ne correspondent plus à l'image (proportions différentes de plus de
 * [RATIO_TOLERANCE] : photo recadrée par une application qui a gardé le XMP) sont ignorés.
 */
object PanoramaRules {

    const val SPHERE_MIN_HEIGHT = 90.0
    const val FLAT_MIN_RATIO = 2.5

    /** Hauteur supposée d'un panorama sans GPano, en degrés : champ vertical d'un téléphone en portrait. */
    const val ASSUMED_FLAT_HEIGHT = 60.0

    private const val RATIO_TOLERANCE = 0.05

    fun classify(mediaId: Long, gpano: GPano?, width: Int, height: Int): Panorama? {
        if (width <= 0 || height <= 0) return null
        val ratio = width.toDouble() / height
        val usable = gpano?.takeIf { it.usePanoramaViewer != false && !isStale(it, ratio) }
        val area = usable?.let { areaOf(it, width, height) }
        if (usable != null && area != null) {
            if (usable.isEquirectangular && area.height >= SPHERE_MIN_HEIGHT) {
                val (yaw, pitch) = initialView(usable, area)
                return Panorama(mediaId, PanoramaKind.SPHERE, area, yaw, pitch, usable.initialHorizontalFovDegrees?.takeIf { it > 0 })
            }
            if (ratio > 1) return Panorama(mediaId, PanoramaKind.FLAT, area, area.centerLongitude, 0.0)
        } else if (usable != null && ratio > 1) {
            // GPano sans tailles exploitables : panorama à plat, couverture estimée.
            return flat(mediaId, ratio)
        }
        return if (ratio >= FLAT_MIN_RATIO) flat(mediaId, ratio) else null
    }

    /** Panorama à plat sans couverture connue : [ASSUMED_FLAT_HEIGHT] degrés de haut, centré. */
    private fun flat(mediaId: Long, ratio: Double): Panorama {
        val width = min(SphereArea.FULL_TURN, ratio * ASSUMED_FLAT_HEIGHT)
        val area = SphereArea(-width / 2, width / 2, ASSUMED_FLAT_HEIGHT / 2, -ASSUMED_FLAT_HEIGHT / 2)
        return Panorama(mediaId, PanoramaKind.FLAT, area, 0.0, 0.0)
    }

    /** Proportions de la zone découpée (à défaut, du panorama complet) trop éloignées de celles de l'image. */
    private fun isStale(gpano: GPano, ratio: Double): Boolean {
        val width = gpano.croppedWidth?.takeIf { it > 0 } ?: gpano.fullWidth?.takeIf { it > 0 } ?: return false
        val height = gpano.croppedHeight?.takeIf { it > 0 } ?: gpano.fullHeight?.takeIf { it > 0 } ?: return false
        return abs(width.toDouble() / height - ratio) / ratio > RATIO_TOLERANCE
    }

    /**
     * Zone couverte décrite par [gpano] pour une image de [width] × [height] ; null si elle est
     * incomplète.
     *
     * Tailles du panorama complet absentes : l'image est une sphère complète si elle est au
     * format 2:1, sinon la zone reste inconnue. Tailles de la zone découpée absentes : l'image
     * couvre tout le panorama. Position absente : zone centrée.
     */
    internal fun areaOf(gpano: GPano, width: Int, height: Int): SphereArea? {
        val ratio = width.toDouble() / height
        val fullWidth = gpano.fullWidth?.takeIf { it > 0 }
        val fullHeight = gpano.fullHeight?.takeIf { it > 0 }
        if (fullWidth == null || fullHeight == null) {
            return if (gpano.isEquirectangular && abs(ratio - 2.0) / 2.0 <= RATIO_TOLERANCE) SphereArea.FULL else null
        }
        val croppedWidth = (gpano.croppedWidth?.takeIf { it > 0 } ?: fullWidth).coerceAtMost(fullWidth).toDouble()
        val croppedHeight = (gpano.croppedHeight?.takeIf { it > 0 } ?: fullHeight).coerceAtMost(fullHeight).toDouble()
        val left = gpano.croppedLeft?.takeIf { it >= 0 }?.toDouble() ?: ((fullWidth - croppedWidth) / 2)
        val top = (gpano.croppedTop?.takeIf { it >= 0 }?.toDouble() ?: ((fullHeight - croppedHeight) / 2))
            .coerceAtMost(fullHeight - croppedHeight)
        val west = left / fullWidth * SphereArea.FULL_TURN - 180
        val north = 90 - top / fullHeight * 180
        return SphereArea(
            west = west,
            east = west + croppedWidth / fullWidth * SphereArea.FULL_TURN,
            north = north,
            south = north - croppedHeight / fullHeight * 180,
        )
    }

    /**
     * Vue initiale d'une sphère : cap et inclinaison GPano s'ils existent, sinon centre de la
     * zone, à l'horizon s'il est couvert ; ramenée dans la zone. Le cap initial se lit sur la
     * boussole du fichier, où le centre du panorama est à [GPano.poseHeadingDegrees] (0 à
     * défaut, comme le fait Photo Sphere Viewer) : sa longitude est leur différence.
     */
    internal fun initialView(gpano: GPano, area: SphereArea): Pair<Double, Double> {
        val heading = gpano.initialViewHeadingDegrees
        val yaw = if (heading != null) {
            SphereMath.wrap(heading - (gpano.poseHeadingDegrees ?: 0.0))
        } else {
            area.centerLongitude
        }
        val pitch = gpano.initialViewPitchDegrees ?: if (0.0 in area.south..area.north) 0.0 else (area.north + area.south) / 2
        return SphereMath.clampYaw(yaw, area) to pitch.coerceIn(area.south, area.north)
    }
}
