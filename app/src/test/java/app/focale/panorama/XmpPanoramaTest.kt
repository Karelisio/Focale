package app.focale.panorama

import app.focale.video.MotionPhotoFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lecture des propriétés GPano : paquet XMP, puis en-tête d'un JPEG, d'un WebP et d'un HEIF. */
class XmpPanoramaTest {

    private fun parse(xmp: String) = XmpPanorama.parse(xmp.toByteArray(Charsets.UTF_8))

    @Test
    fun `sphère complète de l'appareil photo Google`() {
        val gpano = parse(PanoramaFixtures.photoSphereXmp)!!
        assertEquals("equirectangular", gpano.projectionType)
        assertTrue(gpano.isEquirectangular)
        assertEquals(true, gpano.usePanoramaViewer)
        assertEquals(8704, gpano.croppedWidth)
        assertEquals(4352, gpano.croppedHeight)
        assertEquals(0, gpano.croppedLeft)
        assertEquals(0, gpano.croppedTop)
        assertEquals(8704, gpano.fullWidth)
        assertEquals(4352, gpano.fullHeight)
        assertEquals(350.0, gpano.poseHeadingDegrees!!, 1e-9)
        assertEquals(90.0, gpano.initialViewHeadingDegrees!!, 1e-9)
        assertEquals(0.0, gpano.initialViewPitchDegrees!!, 1e-9)
        assertEquals(75.0, gpano.initialHorizontalFovDegrees!!, 1e-9)
    }

    @Test
    fun `sphère partielle en éléments, préfixe déclaré autrement`() {
        val gpano = parse(PanoramaFixtures.partialSphereXmp)!!
        assertEquals(5856, gpano.croppedWidth)
        assertEquals(2752, gpano.croppedHeight)
        assertEquals(1424, gpano.croppedLeft)
        assertEquals(800, gpano.croppedTop)
        assertEquals(8704, gpano.fullWidth)
        assertEquals(4352, gpano.fullHeight)
        assertNull(gpano.initialViewHeadingDegrees)
    }

    @Test
    fun `panorama d'un Pixel et projection cylindrique`() {
        val pixel = parse(PanoramaFixtures.pixelPanoramaXmp)!!
        assertEquals(9440, pixel.croppedWidth)
        assertEquals(17128, pixel.fullWidth)
        val cylindrical = parse(PanoramaFixtures.cylindricalXmp)!!
        assertFalse(cylindrical.isEquirectangular)
        assertNull(cylindrical.fullWidth)
    }

    @Test
    fun `pas de GPano`() {
        assertNull(parse(PanoramaFixtures.plainXmp))
        assertNull(XmpPanorama.parse(ByteArray(0)))
        assertNull(parse("pas du XML"))
    }

    @Test
    fun `préfixe usuel sans déclaration, faux et XMP tronqué`() {
        val undeclared = """<x:xmpmeta><rdf:RDF><rdf:Description GPano:UsePanoramaViewer="False" GPano:ProjectionType="equirectangular"/></rdf:RDF></x:xmpmeta>"""
        val gpano = parse(undeclared)!!
        assertEquals(false, gpano.usePanoramaViewer)
        // Coupé au milieu : ce qui précède la coupure est gardé.
        val truncated = PanoramaFixtures.photoSphereXmp.substringBefore("GPano:FullPanoWidthPixels")
        val partial = parse(truncated)
        assertNull(partial)
        val cut = parse(PanoramaFixtures.partialSphereXmp.substringBefore("<pano:FullPanoHeightPixels>"))!!
        assertEquals(8704, cut.fullWidth)
        assertNull(cut.fullHeight)
    }

    @Test
    fun `aucune entité externe n'est chargée`() {
        val hostile = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]>""" +
            """<x:xmpmeta xmlns:GPano="http://ns.google.com/photos/1.0/panorama/"><GPano:ProjectionType>&e;</GPano:ProjectionType></x:xmpmeta>"""
        assertNull(parse(hostile)?.projectionType?.takeIf { it.contains("root") })
    }

    @Test
    fun `en-tête JPEG, après l'EXIF`() {
        val jpeg = MotionPhotoFixtures.jpeg(xmp = PanoramaFixtures.photoSphereXmp, exifSize = 300)
        val gpano = PanoramaHeaders.gpano(PanoramaFixtures.reader(jpeg))
        assertEquals(8704, gpano?.fullWidth)
        assertNull(PanoramaHeaders.gpano(PanoramaFixtures.reader(MotionPhotoFixtures.jpeg(xmp = PanoramaFixtures.plainXmp))))
        assertNull(PanoramaHeaders.gpano(PanoramaFixtures.reader(MotionPhotoFixtures.jpeg())))
    }

    @Test
    fun `bloc XMP d'un WebP`() {
        val gpano = PanoramaHeaders.gpano(PanoramaFixtures.reader(PanoramaFixtures.webp(PanoramaFixtures.pixelPanoramaXmp)))
        assertEquals(9440, gpano?.croppedWidth)
    }

    @Test
    fun `élément XMP d'un HEIF, dans mdat ou dans idat, et d'un AVIF`() {
        for (heif in listOf(
            PanoramaFixtures.heif(PanoramaFixtures.partialSphereXmp),
            PanoramaFixtures.heif(PanoramaFixtures.partialSphereXmp, inIdat = true),
            PanoramaFixtures.heif(PanoramaFixtures.partialSphereXmp, brand = "avif"),
        )) {
            val located = HeifXmp.locate(PanoramaFixtures.reader(heif))
            assertEquals(1, located.size)
            val gpano = PanoramaHeaders.gpano(PanoramaFixtures.reader(heif))
            assertNotNull(gpano)
            assertEquals(1424, gpano!!.croppedLeft)
        }
        assertNull(PanoramaHeaders.gpano(PanoramaFixtures.reader(PanoramaFixtures.heif(PanoramaFixtures.plainXmp))))
    }

    @Test
    fun `fichiers d'autres formats ou tronqués`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64)
        assertNull(PanoramaHeaders.gpano(PanoramaFixtures.reader(png)))
        val heif = PanoramaFixtures.heif(PanoramaFixtures.photoSphereXmp)
        assertTrue(HeifXmp.locate(PanoramaFixtures.reader(heif.copyOf(60))).isEmpty())
        assertNull(PanoramaHeaders.gpano(PanoramaFixtures.reader(ByteArray(3))))
    }
}
