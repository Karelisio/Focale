package app.focale.panorama

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Redressement de l'horizon ([SpherePose]) : matrices, sens de l'inclinaison et du roulis de GPano,
 * ordre des rotations, effet à l'écran ; vue initiale d'une photo comme celles des appareils.
 */
class SpherePoseTest {

    private fun direction(yaw: Double, pitch: Double): FloatArray {
        val y = Math.toRadians(yaw)
        val p = Math.toRadians(pitch)
        return floatArrayOf((cos(p) * sin(y)).toFloat(), sin(p).toFloat(), (-cos(p) * cos(y)).toFloat())
    }

    private fun rotate(m: FloatArray, d: FloatArray): FloatArray = Mat4.transform(m, d[0], d[1], d[2], 0f).copyOf(3)

    private fun yaw(d: FloatArray): Double = Math.toDegrees(atan2(d[0].toDouble(), -d[2].toDouble()))

    private fun pitch(d: FloatArray): Double = Math.toDegrees(asin(d[1].toDouble().coerceIn(-1.0, 1.0)))

    private fun assertDirection(expected: FloatArray, actual: FloatArray) {
        for (axis in 0 until 3) assertEquals("axe $axis", expected[axis], actual[axis], 1e-5f)
    }

    @Test
    fun `rotations élémentaires dans le sens direct`() {
        assertDirection(floatArrayOf(0f, 0f, 1f), rotate(Mat4.rotationX(90.0), floatArrayOf(0f, 1f, 0f)))
        assertDirection(floatArrayOf(1f, 0f, 0f), rotate(Mat4.rotationY(90.0), floatArrayOf(0f, 0f, 1f)))
        assertDirection(floatArrayOf(0f, 1f, 0f), rotate(Mat4.rotationZ(90.0), floatArrayOf(1f, 0f, 0f)))
        assertDirection(floatArrayOf(1f, 0f, 0f), rotate(SpherePose.LEVEL.matrix(), floatArrayOf(1f, 0f, 0f)))
    }

    @Test
    fun `inclinaison, le centre de l'image au-dessus de l'horizon réel`() {
        val pose = SpherePose(pitch = 12.0)
        assertDirection(direction(0.0, 12.0), rotate(pose.matrix(), direction(0.0, 0.0)))
        // L'arrière de l'image descend d'autant ; ses côtés restent sur l'horizon.
        assertEquals(-12.0, pitch(rotate(pose.matrix(), direction(180.0, 0.0))), 1e-4)
        assertEquals(0.0, pitch(rotate(pose.matrix(), direction(90.0, 0.0))), 1e-4)
    }

    @Test
    fun `roulis, l'horizon de l'image tourne dans le sens inverse des aiguilles d'une montre`() {
        val pose = SpherePose(roll = 10.0)
        // Horizon de l'image remonté à gauche : redressé, son côté droit passe sous l'horizon réel…
        assertEquals(-10.0, pitch(rotate(pose.matrix(), direction(90.0, 0.0))), 1e-4)
        assertEquals(10.0, pitch(rotate(pose.matrix(), direction(-90.0, 0.0))), 1e-4)
        // …et l'horizon réel, à droite, était dans l'image à 10° au-dessus de la sienne.
        val inverse = Mat4.rotationZ(10.0)
        assertEquals(10.0, pitch(rotate(inverse, direction(90.0, 0.0))), 1e-4)
        // Le centre de l'image ne bouge pas.
        assertDirection(direction(0.0, 0.0), rotate(pose.matrix(), direction(0.0, 0.0)))
    }

    @Test
    fun `roulis autour du centre de l'image, puis inclinaison`() {
        val matrix = SpherePose(pitch = 30.0, roll = 20.0).matrix()
        assertDirection(direction(0.0, 30.0), rotate(matrix, direction(0.0, 0.0)))
        // Le haut de l'image, au centre, penché de 20° vers la droite puis incliné de 30°.
        val r = Math.toRadians(20.0)
        val p = Math.toRadians(30.0)
        val up = floatArrayOf(sin(r).toFloat(), (cos(r) * cos(p)).toFloat(), (cos(r) * sin(p)).toFloat())
        assertDirection(up, rotate(matrix, floatArrayOf(0f, 1f, 0f)))
    }

    @Test
    fun `à l'écran, le centre d'une photo prise inclinée vers le haut apparaît au-dessus de l'horizon`() {
        val camera = SphereCamera(SphereArea.FULL, initialYaw = 0.0, initialPitch = 0.0, initialHorizontalFov = null)
        camera.setViewport(1080, 2340)
        val pose = SpherePose(pitch = 12.0)
        val clip = Mat4.multiply(camera.viewProjection(), pose.matrix()).let { Mat4.transform(it, 0f, 0f, -1f) }
        val y = clip[1] / clip[3]
        assertEquals(tan(Math.toRadians(12.0)) / tan(Math.toRadians(camera.verticalFov() / 2)), y.toDouble(), 1e-4)
        assertEquals(0f, clip[0] / clip[3], 1e-5f)
    }

    @Test
    fun `pose lue dans un fichier, bornée et finie`() {
        assertEquals(SpherePose(90.0, -90.0), SpherePose.of(120.0, 270.0))
        assertEquals(SpherePose.LEVEL, SpherePose.of(Double.NaN, Double.POSITIVE_INFINITY))
        assertEquals(SpherePose.LEVEL, SpherePose.of(null, null))
        assertTrue(SpherePose.of(0.0, 0.0).isLevel)
    }

    @Test
    fun `photo comme une RICOH THETA sans redressement, cap sans vue initiale, horizon redressé`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/spherical/photo-pose.jpg")).use { it.readBytes() }
        val gpano = requireNotNull(PanoramaHeaders.gpano(PanoramaFixtures.reader(bytes)))
        assertEquals(68.8, gpano.poseHeadingDegrees!!, 1e-9)
        assertEquals(12.0, gpano.posePitchDegrees!!, 1e-9)
        assertEquals(-7.0, gpano.poseRollDegrees!!, 1e-9)
        val panorama = requireNotNull(PanoramaRules.classify(31, gpano, 256, 128))
        assertEquals(PanoramaKind.SPHERE, panorama.kind)
        assertEquals(SpherePose(12.0, -7.0), panorama.pose)
        // Pas de vue initiale : le centre de l'image (l'objectif avant), quelle que soit la boussole.
        assertEquals(0.0, panorama.initialYaw, 1e-9)
        assertEquals(0.0, panorama.initialPitch, 1e-9)
    }

    @Test
    fun `cap initial sur la boussole du fichier, pas depuis le centre`() {
        // Centre au cap 190 (appareil tourné vers l'arrière), vue initiale au cap 180 : 10° à gauche
        // du centre ; Pannellum, qui compte depuis le centre, ferait un demi-tour.
        val gpano = GPano(projectionType = "equirectangular", poseHeadingDegrees = 190.0, initialViewHeadingDegrees = 180.0)
        val panorama = requireNotNull(PanoramaRules.classify(32, gpano, 4096, 2048))
        assertEquals(-10.0, panorama.initialYaw, 1e-9)
        // Les panoramas à plat ne sont pas redressés.
        val flat = requireNotNull(PanoramaRules.classify(33, GPano(projectionType = "cylindrical", posePitchDegrees = 20.0), 3000, 1000))
        assertTrue(flat.pose.isLevel)
    }
}
