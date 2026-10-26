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
 * Disposition des images d'une vidéo 360° : une seule, ou une par œil (stéréoscopie), dont seule
 * celle de l'œil gauche est montrée.
 */
enum class StereoMode {
    MONO,

    /** Œil gauche dans la moitié haute de l'image. */
    TOP_BOTTOM,

    /** Œil gauche dans la moitié gauche de l'image. */
    LEFT_RIGHT,

    /** Œil gauche dans la moitié droite de l'image. */
    RIGHT_LEFT,

    /**
     * Disposition propre au fichier (« stereo-custom » de Spherical Video V2) : seul un maillage
     * la décrit, par ses coordonnées de texture dans l'image entière.
     */
    CUSTOM,
}

/**
 * Redressement de l'horizon d'une sphère, en degrés : [pitch] positive, le centre de l'image est
 * au-dessus de l'horizon ; [roll] positif, l'horizon tourne dans le sens inverse des aiguilles
 * d'une montre dans l'image (`PosePitchDegrees` et `PoseRollDegrees` de GPano). La pose d'une
 * vidéo (`prhd`) se lit de la même façon, comme le fait FFmpeg : inclinaison positive, l'avant de
 * la sphère monte ; roulis positif, il penche vers la droite. Le cap, lui, ne fait que déplacer
 * la vue initiale.
 */
data class SpherePose(val pitch: Double = 0.0, val roll: Double = 0.0) {

    val isLevel: Boolean get() = pitch == 0.0 && roll == 0.0

    /**
     * Matrice 4 × 4 de l'image vers le monde (repère de [SphereMesh]) : roulis corrigé autour de
     * l'axe du centre de l'image, puis inclinaison autour de l'axe gauche-droite.
     */
    fun matrix(): FloatArray = Mat4.multiply(Mat4.rotationX(pitch), Mat4.rotationZ(-roll))

    companion object {
        val LEVEL = SpherePose()

        /** Pose lue dans un fichier : valeurs finies seulement, inclinaison bornée à ±90°. */
        fun of(pitch: Double?, roll: Double?): SpherePose = SpherePose(
            pitch = pitch?.takeIf { it.isFinite() }?.coerceIn(-90.0, 90.0) ?: 0.0,
            roll = roll?.takeIf { it.isFinite() }?.let(SphereMath::wrap) ?: 0.0,
        )
    }
}

/**
 * Projection de l'image (d'un œil) sur la sphère. Une photo est toujours équirectangulaire ; une
 * vidéo Spherical Video V2 peut aussi être un cube ou un maillage.
 */
sealed interface SphereProjection {

    /** Équirectangulaire : l'image couvre la zone [Panorama.area] d'un coin à l'autre ([SphereMesh]). */
    data object Equirectangular : SphereProjection

    /**
     * Cube de Spherical Video V2 (`cbmp`, disposition 0, [CubeMesh]) : six faces en 3 colonnes et
     * 2 lignes d'une image (d'un œil) de [width] × [height] pixels, chacune entourée d'une marge
     * de [padding] pixels, qui n'est pas affichée.
     */
    data class Cubemap(val padding: Int, val width: Int, val height: Int) : SphereProjection {
        companion object {
            /** Cube aux dimensions cohérentes (la marge laisse quelque chose de chaque face), ou null. */
            fun of(padding: Long, width: Int, height: Int): Cubemap? {
                if (width < 3 || height < 2 || padding < 0) return null
                if (2.0 * padding >= minOf(width / 3.0, height / 2.0)) return null
                return Cubemap(padding.toInt(), width, height)
            }
        }
    }

    /** Maillage du fichier (`mshp`, [MeshProjection]) : sommets et coordonnées dans l'image (d'un œil). */
    class Mesh(val mesh: TexturedMesh) : SphereProjection
}

/**
 * Photo panoramique, ou vidéo 360° ([SphericalVideos]), et vue à lui proposer.
 *
 * @property area zone couverte ; pour un panorama à plat sans GPano, couverture estimée.
 * @property initialYaw longitude (degrés) au centre de la vue initiale.
 * @property initialPitch latitude (degrés) au centre de la vue initiale.
 * @property initialHorizontalFov champ horizontal initial demandé par le fichier, s'il y en a un.
 * @property stereo images d'une vidéo stéréoscopique ; une photo n'en a qu'une.
 * @property projection image sur la sphère : équirectangulaire (photos), cube ou maillage (vidéos).
 * @property pose redressement de l'horizon d'une sphère ; vue initiale, glisser et capteur sont
 *   dans le repère redressé.
 */
data class Panorama(
    val mediaId: Long,
    val kind: PanoramaKind,
    val area: SphereArea,
    val initialYaw: Double,
    val initialPitch: Double,
    val initialHorizontalFov: Double? = null,
    val stereo: StereoMode = StereoMode.MONO,
    val projection: SphereProjection = SphereProjection.Equirectangular,
    val pose: SpherePose = SpherePose.LEVEL,
)

/** Maillage texturé de la sphère : zone couverte (équirectangulaire), cube ou maillage du fichier. */
internal fun Panorama.sphereMesh(): TexturedMesh = when (val shape = projection) {
    SphereProjection.Equirectangular -> SphereMesh.build(area)
    is SphereProjection.Cubemap -> CubeMesh.build(shape)
    is SphereProjection.Mesh -> shape.mesh
}

/**
 * Choix de la vue d'une photo d'après ses propriétés GPano et ses dimensions affichées
 * (orientation EXIF appliquée) :
 * - sphère : GPano équirectangulaire, visionneuse panoramique non refusée, couverture verticale
 *   d'au moins [SPHERE_MIN_HEIGHT] degrés (la moitié de la sphère) ; partielle, seule la zone
 *   couverte sera texturée ; horizon redressé d'après l'inclinaison et le roulis de la pose ;
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
                return Panorama(
                    mediaId = mediaId,
                    kind = PanoramaKind.SPHERE,
                    area = area,
                    initialYaw = yaw,
                    initialPitch = pitch,
                    initialHorizontalFov = usable.initialHorizontalFovDegrees?.takeIf { it > 0 },
                    pose = SpherePose.of(usable.posePitchDegrees, usable.poseRollDegrees),
                )
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
     * défaut, comme le fait Photo Sphere Viewer) : sa longitude est leur différence. GPano compte
     * en effet `InitialViewHeadingDegrees` depuis le nord réel, « pas depuis le centre du
     * panorama » (Pannellum, lui, le compte depuis le centre) ; l'inclinaison initiale, depuis
     * l'horizon réel, celui de la photo redressée ([SpherePose]).
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
