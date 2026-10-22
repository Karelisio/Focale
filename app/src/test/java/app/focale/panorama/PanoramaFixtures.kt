package app.focale.panorama

import java.io.ByteArrayOutputStream

/**
 * Paquets XMP GPano tels qu'en écrivent les appareils (Photo Sphere de l'appareil photo Google,
 * mode Panorama d'un Pixel, assembleur cylindrique), et conteneurs synthétiques (WebP, HEIF)
 * qui les portent.
 */
internal object PanoramaFixtures {

    /** Sphère complète de l'appareil photo Google, forme attributs. */
    val photoSphereXmp = """
        <?xpacket begin="${'﻿'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
        <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about=""
                xmlns:GPano="http://ns.google.com/photos/1.0/panorama/"
              GPano:UsePanoramaViewer="True"
              GPano:CaptureSoftware="Photo Sphere"
              GPano:StitchingSoftware="Photo Sphere"
              GPano:ProjectionType="equirectangular"
              GPano:PoseHeadingDegrees="350.0"
              GPano:InitialViewHeadingDegrees="90"
              GPano:InitialViewPitchDegrees="0"
              GPano:InitialViewRollDegrees="0"
              GPano:InitialHorizontalFOVDegrees="75.0"
              GPano:FirstPhotoDate="2023-07-14T10:02:11.402Z"
              GPano:LastPhotoDate="2023-07-14T10:03:40.019Z"
              GPano:SourcePhotosCount="38"
              GPano:ExposureLockUsed="False"
              GPano:CroppedAreaImageWidthPixels="8704"
              GPano:CroppedAreaImageHeightPixels="4352"
              GPano:FullPanoWidthPixels="8704"
              GPano:FullPanoHeightPixels="4352"
              GPano:CroppedAreaLeftPixels="0"
              GPano:CroppedAreaTopPixels="0"/>
          </rdf:RDF>
        </x:xmpmeta>
        <?xpacket end="w"?>
    """.trimIndent()

    /**
     * Sphère inachevée (242° × 114°), propriétés en éléments sous un autre préfixe, à côté d'une
     * description Adobe ordinaire.
     */
    val partialSphereXmp = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:CreatorTool="Pixel 7"/>
            <rdf:Description rdf:about="" xmlns:pano="http://ns.google.com/photos/1.0/panorama/">
              <pano:ProjectionType>equirectangular</pano:ProjectionType>
              <pano:UsePanoramaViewer>True</pano:UsePanoramaViewer>
              <pano:CroppedAreaImageWidthPixels>5856</pano:CroppedAreaImageWidthPixels>
              <pano:CroppedAreaImageHeightPixels>2752</pano:CroppedAreaImageHeightPixels>
              <pano:FullPanoWidthPixels>8704</pano:FullPanoWidthPixels>
              <pano:FullPanoHeightPixels>4352</pano:FullPanoHeightPixels>
              <pano:CroppedAreaLeftPixels>1424</pano:CroppedAreaLeftPixels>
              <pano:CroppedAreaTopPixels>800</pano:CroppedAreaTopPixels>
            </rdf:Description>
          </rdf:RDF>
        </x:xmpmeta>
    """.trimIndent()

    /** Mode Panorama d'un Pixel : 198° × 52° d'une sphère, soit un panorama horizontal partiel. */
    val pixelPanoramaXmp = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about=""
                xmlns:GPano="http://ns.google.com/photos/1.0/panorama/"
              GPano:CroppedAreaImageHeightPixels="2470"
              GPano:CroppedAreaImageWidthPixels="9440"
              GPano:CroppedAreaLeftPixels="3844"
              GPano:CroppedAreaTopPixels="3046"
              GPano:FullPanoHeightPixels="8564"
              GPano:FullPanoWidthPixels="17128"
              GPano:ProjectionType="equirectangular"
              GPano:UsePanoramaViewer="True"
              GPano:SourcePhotosCount="17"/>
          </rdf:RDF>
        </x:xmpmeta>
    """.trimIndent()

    /** Assembleur de bureau, projection cylindrique : panorama à plat. */
    val cylindricalXmp = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/"
              GPano:ProjectionType="cylindrical"
              GPano:UsePanoramaViewer="True"
              GPano:StitchingSoftware="Hugin"/>
          </rdf:RDF>
        </x:xmpmeta>
    """.trimIndent()

    /** Photo ordinaire : XMP sans GPano. */
    val plainXmp = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:CreatorTool="Focale"/>
          </rdf:RDF>
        </x:xmpmeta>
    """.trimIndent()

    /** WebP étendu : VP8X, bloc XMP, puis données d'image factices. */
    fun webp(xmp: String): ByteArray {
        val chunks = ByteArrayOutputStream().apply {
            chunk("VP8X", ByteArray(10).also { it[0] = 0x04 })
            chunk("XMP ", xmp.toByteArray(Charsets.UTF_8))
            chunk("VP8 ", ByteArray(31) { 0x11 })
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(ascii("RIFF"))
            write(int32le(4 + chunks.size))
            write(ascii("WEBP"))
            write(chunks)
        }.toByteArray()
    }

    /**
     * HEIF minimal : `ftyp`, `meta` (élément image 1, élément XMP 2 de type `mime`), puis `mdat`
     * qui porte l'image factice et le XMP. Avec [inIdat], le XMP est rangé dans la boîte `idat`
     * de `meta` (méthode de construction 1). Avec [brand] « avif », un AVIF.
     */
    fun heif(xmp: String, inIdat: Boolean = false, brand: String = "heic"): ByteArray {
        val xmpBytes = xmp.toByteArray(Charsets.UTF_8)
        val image = ByteArray(64) { 0x33 }
        val ftyp = box("ftyp", ascii(brand) + int32be(0) + ascii("mif1") + ascii(brand))

        fun meta(imageOffset: Long, xmpOffset: Long): ByteArray {
            val hdlr = fullBox("hdlr", 0, int32be(0) + ascii("pict") + ByteArray(12) + byteArrayOf(0))
            val pitm = fullBox("pitm", 0, int16be(1))
            val infeImage = fullBox("infe", 2, int16be(1) + int16be(0) + ascii("hvc1") + byteArrayOf(0))
            val infeXmp = fullBox("infe", 2, int16be(2) + int16be(0) + ascii("mime") + ascii("XMP") + byteArrayOf(0) + ascii("application/rdf+xml") + byteArrayOf(0))
            val iinf = fullBox("iinf", 0, int16be(2) + infeImage + infeXmp)
            // iloc version 1 : décalages et longueurs sur 4 octets, pas de base ni d'index.
            val iloc = fullBox(
                "iloc",
                1,
                byteArrayOf(0x44, 0x00) + int16be(2) +
                    int16be(1) + int16be(0) + int16be(0) + int16be(1) + int32be(imageOffset.toInt()) + int32be(image.size) +
                    int16be(2) + int16be(if (inIdat) 1 else 0) + int16be(0) + int16be(1) + int32be(xmpOffset.toInt()) + int32be(xmpBytes.size),
            )
            val idat = if (inIdat) box("idat", xmpBytes) else ByteArray(0)
            return fullBox("meta", 0, hdlr + pitm + iinf + iloc + idat)
        }

        // Première passe pour la taille de meta, seconde avec les vrais décalages.
        val metaSize = meta(0, 0).size
        val mdatStart = ftyp.size + metaSize
        val imageOffset = mdatStart + 8L
        val xmpOffset = if (inIdat) 0L else imageOffset + image.size
        val mdat = box("mdat", if (inIdat) image else image + xmpBytes)
        return ftyp + meta(imageOffset, xmpOffset) + mdat
    }

    /** Lecteur de [bytes] comme d'un fichier : null au-delà de la fin. */
    fun reader(bytes: ByteArray): (Long, Int) -> ByteArray? = { offset, length ->
        if (offset < 0 || length < 0 || offset + length > bytes.size) null else bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    private fun ByteArrayOutputStream.chunk(type: String, data: ByteArray) {
        write(ascii(type))
        write(int32le(data.size))
        write(data)
        if (data.size % 2 == 1) write(0)
    }

    private fun box(type: String, payload: ByteArray): ByteArray = int32be(8 + payload.size) + ascii(type) + payload

    private fun fullBox(type: String, version: Int, payload: ByteArray): ByteArray = box(type, byteArrayOf(version.toByte(), 0, 0, 0) + payload)

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    private fun int16be(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

    private fun int32be(value: Int) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    private fun int32le(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())
}
