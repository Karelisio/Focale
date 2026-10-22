package app.focale.panorama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Choix de la vue (sphère, à plat, aucune) d'après les GPano et les dimensions affichées. */
class PanoramaRulesTest {

    private fun gpano(xmp: String) = XmpPanorama.parse(xmp.toByteArray(Charsets.UTF_8))

    @Test
    fun `sphère complète, tout le tour, cap initial sur la boussole du fichier`() {
        val panorama = PanoramaRules.classify(7, gpano(PanoramaFixtures.photoSphereXmp), 8704, 4352)!!
        assertEquals(7L, panorama.mediaId)
        assertEquals(PanoramaKind.SPHERE, panorama.kind)
        assertEquals(SphereArea.FULL, panorama.area)
        assertTrue(panorama.area.wrapsAround)
        // Vue initiale au cap 90, centre du panorama au cap 350 : 100° à droite du centre.
        assertEquals(100.0, panorama.initialYaw, 1e-9)
        assertEquals(0.0, panorama.initialPitch, 1e-9)
        assertEquals(75.0, panorama.initialHorizontalFov!!, 1e-9)
    }

    @Test
    fun `même sphère réduite à l'export, seules les proportions comptent`() {
        val panorama = PanoramaRules.classify(1, gpano(PanoramaFixtures.photoSphereXmp), 4096, 2048)!!
        assertEquals(PanoramaKind.SPHERE, panorama.kind)
        assertEquals(SphereArea.FULL, panorama.area)
    }

    @Test
    fun `sphère partielle, seule la zone couverte, vue au centre et à l'horizon`() {
        val panorama = PanoramaRules.classify(2, gpano(PanoramaFixtures.partialSphereXmp), 5856, 2752)!!
        assertEquals(PanoramaKind.SPHERE, panorama.kind)
        val area = panorama.area
        assertEquals(1424.0 / 8704 * 360 - 180, area.west, 1e-9)
        assertEquals(5856.0 / 8704 * 360, area.width, 1e-9)
        assertEquals(90 - 800.0 / 4352 * 180, area.north, 1e-9)
        assertEquals(2752.0 / 4352 * 180, area.height, 1e-9)
        assertEquals(false, area.wrapsAround)
        assertEquals(area.centerLongitude, panorama.initialYaw, 1e-9)
        assertEquals(0.0, panorama.initialPitch, 1e-9)
    }

    @Test
    fun `panorama horizontal partiel d'un Pixel, vue à plat, couverture du fichier`() {
        val panorama = PanoramaRules.classify(3, gpano(PanoramaFixtures.pixelPanoramaXmp), 9440, 2470)!!
        assertEquals(PanoramaKind.FLAT, panorama.kind)
        assertEquals(9440.0 / 17128 * 360, panorama.area.width, 1e-9)
        assertTrue(panorama.area.height < PanoramaRules.SPHERE_MIN_HEIGHT)
    }

    @Test
    fun `GPano non sphérique, vue à plat dès que l'image est plus large que haute`() {
        val cylindrical = gpano(PanoramaFixtures.cylindricalXmp)
        assertEquals(PanoramaKind.FLAT, PanoramaRules.classify(4, cylindrical, 3000, 1500)?.kind)
        assertNull(PanoramaRules.classify(4, cylindrical, 1500, 3000))
    }

    @Test
    fun `sans GPano, vue à plat à partir de 2,5 pour 1, couverture estimée`() {
        assertNull(PanoramaRules.classify(5, null, 4000, 3000))
        assertNull(PanoramaRules.classify(5, null, 4000, 2000))
        assertNull(PanoramaRules.classify(5, null, 2400, 1000))
        val wide = PanoramaRules.classify(5, null, 5000, 2000)!!
        assertEquals(PanoramaKind.FLAT, wide.kind)
        assertEquals(2.5 * PanoramaRules.ASSUMED_FLAT_HEIGHT, wide.area.width, 1e-9)
        // Très large : jamais plus d'un tour.
        assertEquals(360.0, PanoramaRules.classify(5, null, 20000, 1000)!!.area.width, 1e-9)
        // Panorama vertical : rien.
        assertNull(PanoramaRules.classify(5, null, 1000, 5000))
        assertNull(PanoramaRules.classify(5, null, 0, 0))
    }

    @Test
    fun `visionneuse panoramique refusée par le fichier, règle des proportions seule`() {
        val refused = gpano(PanoramaFixtures.photoSphereXmp.replace("UsePanoramaViewer=\"True\"", "UsePanoramaViewer=\"False\""))
        assertNull(PanoramaRules.classify(6, refused, 8704, 4352))
        assertEquals(PanoramaKind.FLAT, PanoramaRules.classify(6, refused, 9000, 3000)?.kind)
    }

    @Test
    fun `GPano qui ne correspond plus à l'image recadrée, ignoré`() {
        assertNull(PanoramaRules.classify(8, gpano(PanoramaFixtures.photoSphereXmp), 4000, 3000))
    }

    @Test
    fun `GPano sans tailles, sphère complète au format 2 pour 1, sinon à plat`() {
        val bare = gpano(
            """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
                """<rdf:Description xmlns:GPano="http://ns.google.com/photos/1.0/panorama/" GPano:ProjectionType="equirectangular" GPano:UsePanoramaViewer="True"/>""" +
                """</rdf:RDF></x:xmpmeta>""",
        )
        val sphere = PanoramaRules.classify(9, bare, 6000, 3000)!!
        assertEquals(PanoramaKind.SPHERE, sphere.kind)
        assertEquals(SphereArea.FULL, sphere.area)
        val flat = PanoramaRules.classify(9, bare, 6000, 2000)!!
        assertEquals(PanoramaKind.FLAT, flat.kind)
        assertEquals(180.0, flat.area.width, 1e-9)
    }

    @Test
    fun `vue initiale ramenée dans une sphère partielle, cap sans boussole`() {
        val area = SphereArea(west = -60.0, east = 60.0, north = 70.0, south = -40.0)
        // Sans cap du centre, les caps se comptent depuis le centre.
        val (yaw, pitch) = PanoramaRules.initialView(GPano(initialViewHeadingDegrees = 30.0, initialViewPitchDegrees = 80.0), area)
        assertEquals(30.0, yaw, 1e-9)
        assertEquals(70.0, pitch, 1e-9)
        val (outside, _) = PanoramaRules.initialView(GPano(initialViewHeadingDegrees = 200.0), area)
        assertEquals(-60.0, outside, 1e-9)
        // Horizon hors de la zone : centre vertical.
        val (_, high) = PanoramaRules.initialView(GPano(), SphereArea(-90.0, 90.0, 90.0, 20.0))
        assertEquals(55.0, high, 1e-9)
    }
}
