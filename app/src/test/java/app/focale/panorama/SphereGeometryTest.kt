package app.focale.panorama

import app.focale.ui.viewer.panoramaShift
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Maillage de la sphère, caméra (doigt, pincement, capteur), taille de texture et défilement à plat. */
class SphereGeometryTest {

    /** Direction (x, y, z) d'un cap et d'une inclinaison, dans le repère du maillage. */
    private fun direction(yaw: Double, pitch: Double): FloatArray {
        val y = Math.toRadians(yaw)
        val p = Math.toRadians(pitch)
        return floatArrayOf((cos(p) * sin(y)).toFloat(), sin(p).toFloat(), (-cos(p) * cos(y)).toFloat())
    }

    /** Position à l'écran (coordonnées normalisées, -1 à 1) d'une direction vue par [camera]. */
    private fun project(camera: SphereCamera, d: FloatArray): Pair<Float, Float> {
        val clip = Mat4.transform(camera.viewProjection(), d[0], d[1], d[2])
        assertTrue("derrière la caméra", clip[3] > 0)
        return clip[0] / clip[3] to clip[1] / clip[3]
    }

    @Test
    fun `sphère complète, sommets sur la sphère, texture des coins aux coins`() {
        val mesh = SphereMesh.build(SphereArea.FULL)
        assertEquals(SphereMesh.COLUMNS_PER_TURN, mesh.columns)
        assertEquals(SphereMesh.ROWS_PER_HALF_TURN, mesh.rows)
        assertEquals((mesh.columns + 1) * (mesh.rows + 1), mesh.vertexCount)
        assertEquals(mesh.columns * mesh.rows * 6, mesh.indices.size)
        assertTrue(mesh.vertexCount < 65_536)
        for (vertex in 0 until mesh.vertexCount) {
            val (x, y, z) = Triple(mesh.positions[vertex * 3], mesh.positions[vertex * 3 + 1], mesh.positions[vertex * 3 + 2])
            assertEquals(1f, sqrt(x * x + y * y + z * z), 1e-5f)
        }
        // Centre de l'image : droit devant (-Z), au milieu de la texture.
        val center = (mesh.rows / 2) * (mesh.columns + 1) + mesh.columns / 2
        assertEquals(0f, mesh.positions[center * 3], 1e-6f)
        assertEquals(0f, mesh.positions[center * 3 + 1], 1e-6f)
        assertEquals(-1f, mesh.positions[center * 3 + 2], 1e-6f)
        assertEquals(0.5f, mesh.texCoords[center * 2], 1e-6f)
        assertEquals(0.5f, mesh.texCoords[center * 2 + 1], 1e-6f)
        // Premier sommet : coin haut gauche de l'image, au zénith.
        assertEquals(0f, mesh.texCoords[0], 0f)
        assertEquals(0f, mesh.texCoords[1], 0f)
        assertEquals(1f, mesh.positions[1], 1e-6f)
        assertTrue(mesh.indices.all { it.toInt() and 0xFFFF < mesh.vertexCount })
    }

    @Test
    fun `sphère partielle, seule la zone couverte, finesse proportionnelle`() {
        val area = SphereArea(west = -121.0, east = 121.0, north = 57.0, south = -57.0)
        val mesh = SphereMesh.build(area)
        assertEquals(kotlin.math.ceil(242.0 / 360 * SphereMesh.COLUMNS_PER_TURN).toInt(), mesh.columns)
        // Coin bas droit de l'image : (est, sud).
        val last = mesh.vertexCount - 1
        val expected = direction(121.0, -57.0)
        for (axis in 0 until 3) assertEquals(expected[axis], mesh.positions[last * 3 + axis], 1e-5f)
        assertEquals(1f, mesh.texCoords[last * 2], 0f)
        assertEquals(1f, mesh.texCoords[last * 2 + 1], 0f)
        // Petite zone : jamais moins de 4 × 2 segments.
        val tiny = SphereMesh.build(SphereArea(-2.0, 2.0, 1.0, -1.0))
        assertEquals(4, tiny.columns)
        assertEquals(2, tiny.rows)
    }

    @Test
    fun `la direction regardée est au centre de l'écran`() {
        val camera = SphereCamera(SphereArea.FULL, initialYaw = 30.0, initialPitch = 10.0, initialHorizontalFov = null)
        camera.setViewport(1080, 2340)
        val (x, y) = project(camera, direction(30.0, 10.0))
        assertEquals(0f, x, 1e-5f)
        assertEquals(0f, y, 1e-5f)
        // Un peu plus à droite et plus haut : à droite et en haut de l'écran.
        val (right, up) = project(camera, direction(40.0, 15.0))
        assertTrue(right > 0 && up > 0)
    }

    @Test
    fun `champ le long du petit côté, en portrait comme en paysage`() {
        val portrait = SphereCamera(SphereArea.FULL, 0.0, 0.0, null).apply { setViewport(1000, 2000) }
        assertEquals(SphereCamera.DEFAULT_FOV, portrait.fov, 1e-9)
        assertEquals(SphereMath.convertFov(SphereCamera.DEFAULT_FOV, 1000.0, 2000.0), portrait.verticalFov(), 1e-9)
        // Bord droit de l'écran à la moitié du champ horizontal.
        val (edge, _) = project(portrait, direction(SphereCamera.DEFAULT_FOV / 2, 0.0))
        assertEquals(1f, edge, 1e-4f)
        val landscape = SphereCamera(SphereArea.FULL, 0.0, 0.0, null).apply { setViewport(2000, 1000) }
        assertEquals(SphereCamera.DEFAULT_FOV, landscape.verticalFov(), 1e-9)
        // Champ horizontal du fichier (75°) : en paysage, ramené à la hauteur.
        val fromFile = SphereCamera(SphereArea.FULL, 0.0, 0.0, initialHorizontalFov = 75.0).apply { setViewport(2000, 1000) }
        assertEquals(SphereMath.convertFov(75.0, 2000.0, 1000.0), fromFile.fov, 1e-9)
        val (fileEdge, _) = project(fromFile, direction(37.5, 0.0))
        assertEquals(1f, fileEdge, 1e-4f)
    }

    @Test
    fun `glisser, l'image suit le doigt`() {
        val camera = SphereCamera(SphereArea.FULL, 0.0, 0.0, null).apply { setViewport(1080, 2340) }
        val before = direction(0.0, 0.0)
        camera.drag(dx = 12f, dy = 0f)
        assertTrue(camera.yaw < 0)
        val (x, _) = project(camera, before)
        // 12 px à droite, en coordonnées normalisées.
        assertEquals(2 * 12f / 1080, x, 1e-4f)
        camera.drag(dx = 0f, dy = 15f)
        assertTrue(camera.pitch > 0)
        // Jamais au-delà du zénith ; un tour complet se referme.
        repeat(100) { camera.drag(0f, 2000f) }
        assertEquals(90.0, camera.pitch, 1e-9)
        repeat(50) { camera.drag(-3000f, 0f) }
        assertTrue(camera.yaw >= -180 && camera.yaw < 180)
    }

    @Test
    fun `sphère partielle, le centre de la vue reste dans la zone`() {
        val area = SphereArea(west = -60.0, east = 60.0, north = 40.0, south = -30.0)
        val camera = SphereCamera(area, 0.0, 0.0, null).apply { setViewport(1000, 2000) }
        repeat(40) { camera.drag(2000f, 0f) }
        assertEquals(-60.0, camera.yaw, 1e-9)
        repeat(40) { camera.drag(-2000f, -2000f) }
        assertEquals(60.0, camera.yaw, 1e-9)
        assertEquals(-30.0, camera.pitch, 1e-9)
    }

    @Test
    fun `pincement borné, puis vue initiale`() {
        val camera = SphereCamera(SphereArea.FULL, 100.0, 5.0, initialHorizontalFov = 60.0).apply { setViewport(1000, 2000) }
        assertEquals(60.0, camera.fov, 1e-9)
        camera.zoom(1.5f)
        assertEquals(40.0, camera.fov, 1e-6)
        camera.zoom(10f)
        assertEquals(SphereCamera.MIN_FOV, camera.fov, 1e-9)
        camera.zoom(0.01f)
        assertEquals(SphereCamera.MAX_FOV, camera.fov, 1e-9)
        camera.drag(300f, 200f)
        camera.reset()
        assertEquals(100.0, camera.yaw, 1e-9)
        assertEquals(5.0, camera.pitch, 1e-9)
        assertEquals(60.0, camera.fov, 1e-9)
        // Restauré après recréation : la vue initiale ne l'écrase pas.
        val restored = SphereCamera(SphereArea.FULL, 100.0, 5.0, 60.0).apply {
            restore(-20.0, 12.0, 45.0)
            setViewport(1000, 2000)
        }
        assertEquals(-20.0, restored.yaw, 1e-9)
        assertEquals(45.0, restored.fov, 1e-9)
    }

    /** Téléphone debout, écran vers soi, objectif vers le cap [heading] (degrés vers l'est depuis le nord). */
    private fun uprightPortrait(heading: Double): FloatArray {
        val h = Math.toRadians(heading)
        // Colonnes : axes X (droite), Y (haut) et Z (vers soi) du téléphone dans le monde (est, nord, zénith).
        val x = doubleArrayOf(cos(h), -sin(h), 0.0)
        val y = doubleArrayOf(0.0, 0.0, 1.0)
        val z = doubleArrayOf(-sin(h), -cos(h), 0.0)
        return FloatArray(9) { index ->
            val row = index / 3
            when (index % 3) {
                0 -> x[row]
                1 -> y[row]
                else -> z[row]
            }.toFloat()
        }
    }

    @Test
    fun `bouger le téléphone, la vue en cours est reprise, puis suit le cap`() {
        val camera = SphereCamera(SphereArea.FULL, 40.0, 10.0, null).apply { setViewport(1000, 2000) }
        camera.setMotion(true)
        // Première mesure (téléphone vers le nord, à l'horizontale) : la vue ne saute pas en cap.
        camera.onDeviceRotation(uprightPortrait(0.0), displayQuarterTurns = 0)
        assertEquals(40.0, camera.yaw, 1e-6)
        assertEquals(0.0, camera.pitch, 1e-6)
        // Un quart de tour vers l'est : 90° de plus.
        camera.onDeviceRotation(uprightPortrait(90.0), 0)
        assertEquals(130.0, camera.yaw, 1e-4)
        // Le doigt ne change plus que le cap.
        camera.drag(dx = -50f, dy = 400f)
        assertTrue(camera.yaw > 130.0)
        assertEquals(0.0, camera.pitch, 1e-6)
        // La direction regardée est au centre de l'écran.
        val (x, y) = project(camera, direction(camera.yaw, camera.pitch))
        assertEquals(0f, x, 1e-4f)
        assertEquals(0f, y, 1e-4f)
        // Vue initiale : le cap du fichier, depuis la position du téléphone.
        camera.reset()
        assertEquals(40.0, camera.yaw, 1e-4)
        camera.setMotion(false)
        assertEquals(40.0, camera.yaw, 1e-4)
    }

    @Test
    fun `écran en paysage, le haut de l'image reste en haut`() {
        // Téléphone tourné d'un quart de tour à gauche (ROTATION_90), objectif vers le nord : son
        // côté droit (+X) pointe vers le zénith, son haut (+Y) vers l'ouest.
        val rotation = floatArrayOf(
            0f, -1f, 0f,
            0f, 0f, -1f,
            1f, 0f, 0f,
        )
        val orientation = SphereCamera.cameraToWorld(rotation, displayQuarterTurns = 1)
        assertEquals(0.0, SphereCamera.yawOf(orientation), 1e-9)
        assertEquals(0.0, SphereCamera.pitchOf(orientation), 1e-9)
        // Haut de l'écran (colonne Y) = zénith (Y du monde OpenGL) ; droite = est (+X).
        assertEquals(1.0, orientation[4], 1e-9)
        assertEquals(1.0, orientation[0], 1e-9)
        val camera = SphereCamera(SphereArea.FULL, 0.0, 0.0, null).apply {
            setViewport(2000, 1000)
            setMotion(true)
            onDeviceRotation(rotation, 1)
        }
        val (_, up) = project(camera, direction(0.0, 20.0))
        assertTrue(up > 0)
        val (right, _) = project(camera, direction(20.0, 0.0))
        assertTrue(right > 0)
    }

    @Test
    fun `cap d'une mesure à l'autre, sans saut au nord`() {
        val tracker = HeadingTracker()
        assertEquals(0.0, tracker.update(uprightPortrait(350.0), 0), 1e-9)
        assertEquals(20.0, tracker.update(uprightPortrait(10.0), 0), 1e-6)
        assertEquals(-30.0, tracker.update(uprightPortrait(340.0), 0), 1e-6)
        tracker.reset()
        assertEquals(0.0, tracker.update(uprightPortrait(90.0), 0), 1e-9)
    }

    @Test
    fun `défilement à plat, un degré du panorama par degré`() {
        // Panorama de 180° large de 3600 px : 20 px par degré, l'image part à gauche quand on tourne à droite.
        assertEquals(-200f, panoramaShift(10.0, 3600f, 180.0), 1e-3f)
        assertEquals(100f, panoramaShift(-5.0, 3600f, 180.0), 1e-3f)
        assertEquals(0f, panoramaShift(10.0, 0f, 180.0), 0f)
    }

    @Test
    fun `taille de texture, puissances de deux, plafonds et budget`() {
        val big = 1L shl 25
        assertEquals(4096 to 2048, SphereTextureSize.choose(5376, 2688, 16384, big))
        assertEquals(8192 to 4096, SphereTextureSize.choose(6720, 3360, 16384, big))
        // 72 Mpx : jamais plus de 8192 de large.
        assertEquals(8192 to 4096, SphereTextureSize.choose(11968, 5984, 16384, big))
        // GL_MAX_TEXTURE_SIZE de 4096, puis 2048 : la hauteur suit la largeur plafonnée.
        assertEquals(4096 to 2048, SphereTextureSize.choose(6720, 3360, 4096, big))
        assertEquals(2048 to 1024, SphereTextureSize.choose(5376, 2688, 2048, big))
        // Budget de 8 Mpx.
        assertEquals(4096 to 2048, SphereTextureSize.choose(6720, 3360, 16384, 1L shl 23))
        // Sphère partielle : proportions de la zone, à peu près.
        assertEquals(4096 to 2048, SphereTextureSize.choose(5856, 2752, 16384, big))
        for ((w, h) in listOf(SphereTextureSize.choose(3001, 777, 8192, big), SphereTextureSize.choose(1, 1, 8192, big))) {
            assertEquals(Integer.highestOneBit(w), w)
            assertEquals(Integer.highestOneBit(h), h)
        }
        assertEquals(4, SphereTextureSize.nearestPowerOfTwo(3))
        assertEquals(4096, SphereTextureSize.nearestPowerOfTwo(5376))
        assertEquals(8192, SphereTextureSize.nearestPowerOfTwo(6000))
        assertEquals(1L shl 25, SphereTextureSize.pixelBudget(8L shl 30))
        assertEquals(1L shl 24, SphereTextureSize.pixelBudget(4L shl 30))
        assertEquals(1L shl 23, SphereTextureSize.pixelBudget(2L shl 30))
    }

    @Test
    fun `angles ramenés dans le tour et dans la zone`() {
        assertEquals(-180.0, SphereMath.wrap(180.0), 1e-9)
        assertEquals(170.0, SphereMath.wrap(-190.0), 1e-9)
        assertEquals(10.0, SphereMath.wrap(730.0), 1e-9)
        val seam = SphereArea(west = 100.0, east = 260.0, north = 30.0, south = -30.0)
        // -150° est 210° : dans une zone qui enjambe la couture.
        assertEquals(210.0, SphereMath.clampYaw(-150.0, seam), 1e-9)
        assertTrue(abs(SphereMath.clampYaw(0.0, seam) - 100.0) < 1e-9)
    }
}
