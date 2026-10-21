package app.focale.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Repérage de `tiff:Orientation` dans un paquet XMP (logique pure). */
class XmpOrientationTest {

    /** Paquet XMP complet autour de la description [description]. */
    private fun packet(description: String) = """
        <?xpacket begin="${'﻿'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
        <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.2">
         <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
          $description
         </rdf:RDF>
        </x:xmpmeta>
        <?xpacket end="w"?>
    """.trimIndent()

    /** Valeurs trouvées, lues aux positions renvoyées. */
    private fun values(xmp: String): List<Char> {
        val bytes = xmp.toByteArray(Charsets.UTF_8)
        return XmpOrientation.digits(bytes).map { bytes[it].toInt().toChar() }
    }

    @Test
    fun `attribut entre guillemets ou apostrophes, quel que soit le préfixe`() {
        assertEquals(
            listOf('6'),
            values(packet("""<rdf:Description rdf:about="" xmlns:tiff="http://ns.adobe.com/tiff/1.0/" tiff:Orientation="6"/>""")),
        )
        assertEquals(
            listOf('8'),
            values(packet("<rdf:Description rdf:about='' xmlns:t='http://ns.adobe.com/tiff/1.0/' t:Orientation = '8'></rdf:Description>")),
        )
    }

    @Test
    fun `élément, espaces autour de la valeur`() {
        val xmp = packet(
            """
            <rdf:Description rdf:about="" xmlns:exif="http://ns.adobe.com/exif/1.0/" xmlns:T="http://ns.adobe.com/tiff/1.0/">
             <exif:PixelXDimension>4000</exif:PixelXDimension>
             <T:Orientation>
               3
             </T:Orientation>
            </rdf:Description>
            """.trimIndent(),
        )
        assertEquals(listOf('3'), values(xmp))
    }

    @Test
    fun `texte accentué avant la propriété, positions comptées en octets`() {
        val xmp = packet(
            """
            <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:tiff="http://ns.adobe.com/tiff/1.0/">
             <dc:title><rdf:Alt><rdf:li xml:lang="x-default">Été à Noël — « soirée »</rdf:li></rdf:Alt></dc:title>
             <tiff:Orientation>1</tiff:Orientation>
            </rdf:Description>
            """.trimIndent(),
        )
        assertEquals(listOf('1'), values(xmp))
    }

    @Test
    fun `propriété imbriquée, texte et commentaire ignorés`() {
        val xmp = packet(
            """
            <rdf:Description rdf:about="" xmlns:tiff="http://ns.adobe.com/tiff/1.0/" xmlns:xmpMM="http://ns.adobe.com/xap/1.0/mm/"
              xmlns:dc="http://purl.org/dc/elements/1.1/">
             <!-- tiff:Orientation="5" -->
             <dc:description><rdf:Alt><rdf:li xml:lang="x-default">tiff:Orientation="7"</rdf:li></rdf:Alt></dc:description>
             <xmpMM:DerivedFrom rdf:parseType="Resource" tiff:Orientation="2">
              <tiff:Orientation>4</tiff:Orientation>
             </xmpMM:DerivedFrom>
            </rdf:Description>
            """.trimIndent(),
        )
        assertEquals(emptyList<Char>(), values(xmp))
    }

    @Test
    fun `préfixe tiff lié à un autre espace de noms ignoré, préfixe usuel non déclaré reconnu`() {
        assertEquals(
            emptyList<Char>(),
            values(packet("""<rdf:Description rdf:about="" xmlns:tiff="http://example.com/autre/" tiff:Orientation="6"/>""")),
        )
        assertEquals(listOf('6'), values(packet("""<rdf:Description rdf:about="" tiff:Orientation="6"/>""")))
    }

    @Test
    fun `valeur à plusieurs chiffres, entité ou structure laissée telle quelle`() {
        val xmp = packet(
            """
            <rdf:Description rdf:about="" xmlns:tiff="http://ns.adobe.com/tiff/1.0/" tiff:Orientation="10"/>
            <rdf:Description rdf:about="" xmlns:tiff="http://ns.adobe.com/tiff/1.0/" tiff:Orientation="&#54;"/>
            <rdf:Description rdf:about="" xmlns:tiff="http://ns.adobe.com/tiff/1.0/">
             <tiff:Orientation><rdf:value>6</rdf:value></tiff:Orientation>
            </rdf:Description>
            """.trimIndent(),
        )
        assertEquals(emptyList<Char>(), values(xmp))
    }

    @Test
    fun `plusieurs descriptions de premier niveau`() {
        val xmp = packet(
            """
            <rdf:Description rdf:about="" xmlns:tiff="http://ns.adobe.com/tiff/1.0/" tiff:Orientation="1"/>
            <rdf:Description rdf:about="" xmlns:tiff="http://ns.adobe.com/tiff/1.0/"><tiff:Orientation>1</tiff:Orientation></rdf:Description>
            """.trimIndent(),
        )
        assertEquals(listOf('1', '1'), values(xmp))
    }

    @Test
    fun `localisation masquée par des octets nuls`() {
        val masked = packet(
            "<rdf:Description rdf:about=\"\" xmlns:exif=\"http://ns.adobe.com/exif/1.0/\" \u0000\u0000\u0000\u0000\u0000\u0000\u0000" +
                " xmlns:tiff=\"http://ns.adobe.com/tiff/1.0/\" tiff:Orientation=\"6\"/>",
        )
        assertEquals(listOf('6'), values(masked))
    }

    @Test
    fun `paquet mal formé, lecture arrêtée sans erreur`() {
        assertEquals(emptyList<Char>(), values("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description tiff:Orientation=6/>"))
        assertEquals(emptyList<Char>(), values("<x:xmpmeta><rdf:RDF"))
        assertEquals(emptyList<Char>(), values(""))
    }
}
