package app.focale.panorama

import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Angles de la sphère, en degrés. */
object SphereMath {

    /** [degrees] ramené dans [-180, 180[. */
    fun wrap(degrees: Double): Double {
        val turned = (degrees + 180) % SphereArea.FULL_TURN
        return (if (turned < 0) turned + SphereArea.FULL_TURN else turned) - 180
    }

    /**
     * Longitude [yaw] ramenée dans la zone : sur un tour complet, simplement dans [-180, 180[ ;
     * sinon, au tour le plus proche du centre de la zone, puis bornée à ses bords.
     */
    fun clampYaw(yaw: Double, area: SphereArea): Double {
        if (area.wrapsAround) return wrap(yaw)
        val center = area.centerLongitude
        return (center + wrap(yaw - center)).coerceIn(area.west, area.east)
    }

    /** Champ (degrés) le long du côté de longueur [to] d'un champ [fov] le long du côté [from]. */
    fun convertFov(fov: Double, from: Double, to: Double): Double =
        Math.toDegrees(2 * atan(tan(Math.toRadians(fov) / 2) * to / from))
}

/**
 * Matrices 4 × 4 d'OpenGL (colonnes à la suite) et 3 × 3 des capteurs (lignes à la suite), en
 * Kotlin pur : la géométrie se vérifie sans appareil.
 */
object Mat4 {

    fun identity(): FloatArray = FloatArray(16).also { for (i in 0 until 4) it[i * 5] = 1f }

    /** Perspective de champ vertical [fovY] (degrés). */
    fun perspective(fovY: Double, aspect: Double, near: Float, far: Float): FloatArray {
        val f = (1 / tan(Math.toRadians(fovY) / 2)).toFloat()
        return FloatArray(16).apply {
            this[0] = f / aspect.toFloat()
            this[5] = f
            this[10] = (far + near) / (near - far)
            this[11] = -1f
            this[14] = 2 * far * near / (near - far)
        }
    }

    /** Rotation de [degrees] autour de l'axe X (sens direct). */
    fun rotationX(degrees: Double): FloatArray {
        val c = cos(Math.toRadians(degrees)).toFloat()
        val s = sin(Math.toRadians(degrees)).toFloat()
        return identity().apply {
            this[5] = c
            this[6] = s
            this[9] = -s
            this[10] = c
        }
    }

    /** Rotation de [degrees] autour de l'axe Y (sens direct). */
    fun rotationY(degrees: Double): FloatArray {
        val c = cos(Math.toRadians(degrees)).toFloat()
        val s = sin(Math.toRadians(degrees)).toFloat()
        return identity().apply {
            this[0] = c
            this[2] = -s
            this[8] = s
            this[10] = c
        }
    }

    fun multiply(a: FloatArray, b: FloatArray): FloatArray = FloatArray(16).also { out ->
        for (column in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[k * 4 + row] * b[column * 4 + k]
                out[column * 4 + row] = sum
            }
        }
    }

    /** Transposée de la matrice 3 × 3 [m] (lignes à la suite), en 4 × 4 : la vue d'une caméra d'orientation [m]. */
    fun transposed3(m: DoubleArray): FloatArray = identity().also { out ->
        // Colonne c de la transposée = ligne c de m.
        for (column in 0 until 3) for (row in 0 until 3) out[column * 4 + row] = m[column * 3 + row].toFloat()
    }

    /** Produit de deux matrices 3 × 3 (lignes à la suite). */
    fun multiply3(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9).also { out ->
        for (row in 0 until 3) for (column in 0 until 3) {
            var sum = 0.0
            for (k in 0 until 3) sum += a[row * 3 + k] * b[k * 3 + column]
            out[row * 3 + column] = sum
        }
    }

    /** Rotation 3 × 3 de [degrees] autour de l'axe Y (sens direct, lignes à la suite). */
    fun rotationY3(degrees: Double): DoubleArray {
        val c = cos(Math.toRadians(degrees))
        val s = sin(Math.toRadians(degrees))
        return doubleArrayOf(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c)
    }

    /** [m] × (x, y, z, w). */
    fun transform(m: FloatArray, x: Float, y: Float, z: Float, w: Float = 1f): FloatArray =
        FloatArray(4) { row -> m[row] * x + m[4 + row] * y + m[8 + row] * z + m[12 + row] * w }
}

/**
 * Maillage de la zone couverte d'une sphère de rayon 1 vue de l'intérieur : seule la zone
 * [area] est construite, et toute la texture (la photo) s'y étend, de son coin haut gauche
 * (ouest, nord) à son coin bas droit (est, sud). Les longitudes croissent vers la droite pour un
 * observateur au centre qui regarde vers -Z, l'axe Y vers le zénith.
 *
 * @property positions x, y, z de chaque sommet.
 * @property texCoords u, v de chaque sommet (v = 0 en haut de l'image).
 * @property indices triangles, trois sommets chacun.
 */
class SphereMesh(val positions: FloatArray, val texCoords: FloatArray, val indices: ShortArray, val columns: Int, val rows: Int) {

    val vertexCount: Int get() = positions.size / 3

    companion object {
        /** Finesse d'une sphère complète : segments par tour et par demi-tour. */
        const val COLUMNS_PER_TURN = 96
        const val ROWS_PER_HALF_TURN = 48

        fun build(area: SphereArea): SphereMesh {
            val columns = ceil(area.width / SphereArea.FULL_TURN * COLUMNS_PER_TURN).toInt().coerceAtLeast(4)
            val rows = ceil(area.height / 180 * ROWS_PER_HALF_TURN).toInt().coerceAtLeast(2)
            val positions = FloatArray((columns + 1) * (rows + 1) * 3)
            val texCoords = FloatArray((columns + 1) * (rows + 1) * 2)
            var vertex = 0
            for (row in 0..rows) {
                val v = row.toDouble() / rows
                val latitude = Math.toRadians(area.north - v * area.height)
                for (column in 0..columns) {
                    val u = column.toDouble() / columns
                    val longitude = Math.toRadians(area.west + u * area.width)
                    positions[vertex * 3] = (cos(latitude) * sin(longitude)).toFloat()
                    positions[vertex * 3 + 1] = sin(latitude).toFloat()
                    positions[vertex * 3 + 2] = (-cos(latitude) * cos(longitude)).toFloat()
                    texCoords[vertex * 2] = u.toFloat()
                    texCoords[vertex * 2 + 1] = v.toFloat()
                    vertex++
                }
            }
            val indices = ShortArray(columns * rows * 6)
            var index = 0
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    val topLeft = row * (columns + 1) + column
                    val bottomLeft = topLeft + columns + 1
                    for (corner in intArrayOf(topLeft, bottomLeft, topLeft + 1, topLeft + 1, bottomLeft, bottomLeft + 1)) {
                        indices[index++] = corner.toShort()
                    }
                }
            }
            return SphereMesh(positions, texCoords, indices, columns, rows)
        }
    }
}

/**
 * Taille de la texture d'une photo 360° : puissances de deux (mipmaps permises par OpenGL ES 2),
 * la plus proche de chaque côté de l'image (à l'échelle logarithmique : pas de pixels inventés
 * au-delà de la moitié de l'écart), au plus [maxTextureSize] et [MAX_SIDE]. Un côté plafonné
 * entraîne l'autre (pas de détail vertical deux fois plus fin que l'horizontal), puis le tout
 * tient en [maxPixels] pixels : le côté le mieux servi est divisé par deux, la hauteur d'abord à
 * égalité (on regarde surtout autour de soi).
 */
object SphereTextureSize {

    const val MAX_SIDE = 8192

    fun choose(width: Int, height: Int, maxTextureSize: Int, maxPixels: Long): Pair<Int, Int> {
        val cap = Integer.highestOneBit(minOf(maxTextureSize, MAX_SIDE).coerceAtLeast(1))
        var w = nearestPowerOfTwo(width).coerceAtMost(cap)
        var h = nearestPowerOfTwo(height).coerceAtMost(cap)
        // Résolutions relatives (texture / image) écartées de plus de √2 : la plus fine est réduite.
        while (h > 1 && h.toDouble() / height > BALANCE * w / width) h /= 2
        while (w > 1 && w.toDouble() / width > BALANCE * h / height) w /= 2
        while (w.toLong() * h > maxPixels && (w > 1 || h > 1)) {
            val reduceWidth = h == 1 || (w > 1 && w.toDouble() / width > h.toDouble() / height)
            if (reduceWidth) w /= 2 else h /= 2
        }
        return w to h
    }

    /** Budget de pixels selon la mémoire de l'appareil : 32, 16 ou 8 Mpx. */
    fun pixelBudget(totalMemoryBytes: Long): Long = when {
        totalMemoryBytes >= 6L * GIB -> 1L shl 25
        totalMemoryBytes >= 3L * GIB -> 1L shl 24
        else -> 1L shl 23
    }

    internal fun nearestPowerOfTwo(value: Int): Int {
        if (value <= 1) return 1
        val low = Integer.highestOneBit(value).toLong()
        // value² < 2·low² : plus près de low que de 2·low à l'échelle logarithmique.
        return if (value.toLong() * value < 2 * low * low) low.toInt() else (low * 2).coerceAtMost(1L shl 30).toInt()
    }

    private const val GIB = 1L shl 30
    private val BALANCE = sqrt(2.0)
}

/**
 * Caméra au centre de la sphère : cap ([yaw]) et inclinaison ([pitch]) du centre de la vue,
 * champ [fov] le long du petit côté de l'écran (le même en portrait et en paysage). Au doigt,
 * l'image suit le doigt au centre de l'écran ; en mode « bouger le téléphone », l'orientation
 * vient du capteur de rotation, décalée en cap pour reprendre la vue en cours (le doigt ne
 * change plus alors que le cap).
 *
 * Appelée depuis le fil principal (gestes, capteur) et lue par le fil OpenGL : tout est
 * synchronisé.
 */
class SphereCamera(private val area: SphereArea, private val initialYaw: Double, private val initialPitch: Double, private val initialHorizontalFov: Double?) {

    var yaw: Double = SphereMath.clampYaw(initialYaw, area)
        @Synchronized get
        private set

    var pitch: Double = clampPitch(initialPitch)
        @Synchronized get
        private set

    var fov: Double = DEFAULT_FOV
        @Synchronized get
        private set

    /** Mode « bouger le téléphone ». */
    var motion: Boolean = false
        @Synchronized get
        private set

    private var width = 0
    private var height = 0
    private var fovResolved = false

    /** Orientation du téléphone (caméra → monde OpenGL), sans le décalage de cap. */
    private var device: DoubleArray? = null
    private var yawOffset = 0.0

    /** Taille de la vue, en pixels ; la première fixe le champ initial ([initialHorizontalFov] converti). */
    @Synchronized
    fun setViewport(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        this.width = width
        this.height = height
        if (!fovResolved) {
            fovResolved = true
            fov = initialFov()
        }
    }

    /** État restauré (recréation de l'activité) : remplace la vue initiale. */
    @Synchronized
    fun restore(yaw: Double, pitch: Double, fov: Double) {
        this.yaw = SphereMath.clampYaw(yaw, area)
        this.pitch = clampPitch(pitch)
        this.fov = fov.coerceIn(MIN_FOV, MAX_FOV)
        fovResolved = true
    }

    /** Glisser de ([dx], [dy]) pixels : l'image suit le doigt. */
    @Synchronized
    fun drag(dx: Float, dy: Float) {
        if (height <= 0) return
        val degreesPerPixel = Math.toDegrees(2 * tan(Math.toRadians(verticalFov()) / 2) / height)
        if (motion) {
            yawOffset -= dx * degreesPerPixel
            refreshFromDevice()
        } else {
            // Déjà dans la zone : bornée sans changer de tour, même après un grand geste.
            val moved = yaw - dx * degreesPerPixel
            yaw = if (area.wrapsAround) SphereMath.wrap(moved) else moved.coerceIn(area.west, area.east)
            pitch = clampPitch(pitch + dy * degreesPerPixel)
        }
    }

    /** Pincement d'un facteur [factor] (> 1 : on rapproche). */
    @Synchronized
    fun zoom(factor: Float) {
        if (factor <= 0f) return
        fov = (fov / factor).coerceIn(MIN_FOV, MAX_FOV)
    }

    /** Vue initiale : cap, inclinaison et champ du fichier ; en mode capteur, cap seulement. */
    @Synchronized
    fun reset() {
        fov = initialFov()
        val current = device
        if (motion && current != null) {
            yawOffset = SphereMath.clampYaw(initialYaw, area) - yawOf(current)
            refreshFromDevice()
        } else {
            yaw = SphereMath.clampYaw(initialYaw, area)
            pitch = clampPitch(initialPitch)
        }
    }

    @Synchronized
    fun setMotion(enabled: Boolean) {
        if (enabled == motion) return
        motion = enabled
        // Le cap sera aligné sur la vue en cours à la première mesure ; au doigt, la vue revient dans la zone.
        device = null
        if (!enabled) yaw = SphereMath.clampYaw(yaw, area)
    }

    /**
     * Mesure du capteur : [rotation] 3 × 3 (lignes à la suite) du repère du téléphone vers le
     * monde (X à l'est, Y au nord ou vers une direction horizontale fixe, Z au zénith), écran
     * tourné de [displayQuarterTurns] quarts de tour (`Display.getRotation()`).
     */
    @Synchronized
    fun onDeviceRotation(rotation: FloatArray, displayQuarterTurns: Int) {
        if (!motion) return
        val orientation = cameraToWorld(rotation, displayQuarterTurns)
        if (device == null) yawOffset = yaw - yawOf(orientation)
        device = orientation
        refreshFromDevice()
    }

    /** Projection × vue, pour le fil OpenGL. */
    @Synchronized
    fun viewProjection(): FloatArray {
        val aspect = if (width > 0 && height > 0) width.toDouble() / height else 1.0
        val projection = Mat4.perspective(verticalFov(), aspect, NEAR, FAR)
        return Mat4.multiply(projection, view())
    }

    /** Champ vertical, en degrés. */
    @Synchronized
    fun verticalFov(): Double = if (width in 1 until height) SphereMath.convertFov(fov, width.toDouble(), height.toDouble()) else fov

    private fun view(): FloatArray {
        val current = device
        return if (motion && current != null) {
            Mat4.transposed3(Mat4.multiply3(Mat4.rotationY3(-yawOffset), current))
        } else {
            Mat4.multiply(Mat4.rotationX(-pitch), Mat4.rotationY(yaw))
        }
    }

    /** Cap et inclinaison suivent le capteur : ils servent de point de départ s'il est coupé. */
    private fun refreshFromDevice() {
        val current = device ?: return
        val oriented = Mat4.multiply3(Mat4.rotationY3(-yawOffset), current)
        yaw = SphereMath.wrap(yawOf(oriented))
        pitch = clampPitch(pitchOf(oriented))
    }

    private fun initialFov(): Double {
        val requested = initialHorizontalFov ?: return DEFAULT_FOV
        // Champ horizontal du fichier, ramené au petit côté (la hauteur, en paysage).
        val short = if (width > height && height > 0) SphereMath.convertFov(requested, width.toDouble(), height.toDouble()) else requested
        return short.coerceIn(MIN_FOV, MAX_FOV)
    }

    private fun clampPitch(value: Double): Double = value.coerceIn(maxOf(-90.0, area.south), minOf(90.0, area.north))

    companion object {
        const val DEFAULT_FOV = 70.0
        const val MIN_FOV = 30.0
        const val MAX_FOV = 100.0
        private const val NEAR = 0.1f
        private const val FAR = 10f

        /** Monde des capteurs (Z au zénith) → monde OpenGL (Y au zénith, -Z vers le nord). */
        private val SENSOR_TO_GL = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, -1.0, 0.0)

        /**
         * Axes de l'écran (droite, haut, vers soi) dans le repère du téléphone, selon la rotation
         * de l'affichage : tourné d'un quart de tour à gauche (ROTATION_90), la droite de l'écran
         * est le bas du téléphone (-Y) et son haut, le côté droit (+X).
         */
        private val SCREEN_AXES = arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            doubleArrayOf(0.0, 1.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0, 1.0),
            doubleArrayOf(-1.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, 1.0),
            doubleArrayOf(0.0, -1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0),
        )

        /** Orientation de la caméra (repère de l'écran → monde OpenGL). */
        internal fun cameraToWorld(rotation: FloatArray, displayQuarterTurns: Int): DoubleArray {
            val device = DoubleArray(9) { rotation[it].toDouble() }
            return Mat4.multiply3(Mat4.multiply3(SENSOR_TO_GL, device), SCREEN_AXES[Math.floorMod(displayQuarterTurns, 4)])
        }

        /** Cap du regard (-Z de la caméra) de l'orientation [m]. */
        internal fun yawOf(m: DoubleArray): Double = Math.toDegrees(atan2(-m[2], m[8]))

        /** Inclinaison du regard de l'orientation [m]. */
        internal fun pitchOf(m: DoubleArray): Double = Math.toDegrees(asin((-m[5]).coerceIn(-1.0, 1.0)))
    }
}
