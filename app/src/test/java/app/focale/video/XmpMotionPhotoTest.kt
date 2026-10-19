package app.focale.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lecture des propriétés de photo animée d'un paquet XMP. */
class XmpMotionPhotoTest {

    private fun parse(xmp: String) = XmpMotionPhoto.parse(xmp.toByteArray(Charsets.UTF_8))

    @Test
    fun `le préfixe est résolu par sa déclaration xmlns`() {
        val info = parse(
            """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description xmlns:cam="http://ns.google.com/photos/1.0/camera/" cam:MotionPhoto="1"/>
            </rdf:RDF></x:xmpmeta>""",
        )

        assertEquals(1, info.motionPhoto)
    }

    @Test
    fun `une propriété homonyme d'un autre espace de noms est ignorée`() {
        val info = parse(
            """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description xmlns:GCamera="http://example.com/other/" GCamera:MotionPhoto="1"/>
            </rdf:RDF></x:xmpmeta>""",
        )

        assertNull(info.motionPhoto)
    }

    @Test
    fun `sans déclaration les préfixes usuels sont reconnus`() {
        val info = parse("""<rdf:Description GCamera:MicroVideo="1" GCamera:MicroVideoOffset="4242"/>""")

        assertEquals(1, info.microVideo)
        assertEquals(4242L, info.microVideoOffset)
    }

    @Test
    fun `un XMP tronqué garde ce qui précède l'erreur`() {
        val info = parse(
            """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" GCamera:MotionPhoto="1">
              <Container:Directory><rdf:Seq><rdf:li""",
        )

        assertEquals(1, info.motionPhoto)
        assertTrue(info.items.isEmpty())
    }

    @Test
    fun `les éléments du répertoire gardent leur ordre`() {
        val info = XmpMotionPhoto.parse(
            MotionPhotoFixtures.googleXmp(
                listOf(
                    MotionPhotoFixtures.Item("image/jpeg", "GainMap", 300),
                    MotionPhotoFixtures.Item("video/mp4", "MotionPhoto", 1_000, padding = 8),
                ),
            ).toByteArray(),
        )

        assertEquals(listOf("Primary", "GainMap", "MotionPhoto"), info.items.map { it.semantic })
        assertEquals(ContainerItem("video/mp4", "MotionPhoto", 1_000, 8), info.items.last())
    }

    @Test
    fun `aucune entité externe n'est chargée`() {
        val info = parse(
            """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]>
            <rdf:Description GCamera:MotionPhoto="1">&e;</rdf:Description>""",
        )

        assertTrue(info.items.isEmpty())
    }
}
