package app.focale.video

import java.io.ByteArrayOutputStream

/** Fichiers synthétiques pour les tests : JPEG minimal, paquets XMP, MP4 factice, remorque SEF. */
internal object MotionPhotoFixtures {

    /** Élément secondaire d'un répertoire `Container:Directory`. */
    data class Item(val mime: String, val semantic: String, val length: Long, val padding: Long = 0)

    /** MP4 factice : boîte `ftyp` de 24 octets puis boîte `mdat` de [payload] octets. */
    fun mp4(payload: Int = 2_000): ByteArray = ByteArrayOutputStream().apply {
        write(int32be(24))
        write(ascii("ftypisom"))
        write(int32be(0x200))
        write(ascii("isomiso2"))
        write(int32be(8 + payload))
        write(ascii("mdat"))
        write(ByteArray(payload) { (it * 31 + 7).toByte() })
    }.toByteArray()

    /**
     * JPEG minimal : SOI, JFIF, EXIF facultatif de [exifSize] octets, [xmp] facultatif (suivi
     * d'octets nuls, comme souvent), table de quantification, scan factice et EOI.
     */
    fun jpeg(xmp: String? = null, exifSize: Int = 0, fillByte: Boolean = false): ByteArray =
        ByteArrayOutputStream().apply {
            write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
            segment(0xE0, ascii("JFIF\u0000") + byteArrayOf(1, 1, 0, 0, 1, 0, 1, 0, 0))
            if (exifSize > 0) segment(0xE1, ascii("Exif\u0000\u0000") + ByteArray(exifSize) { 0x2A })
            if (fillByte) write(0xFF)
            if (xmp != null) {
                segment(0xE1, ascii("http://ns.adobe.com/xap/1.0/\u0000") + xmp.toByteArray(Charsets.UTF_8) + ByteArray(12))
            }
            segment(0xDB, ByteArray(65) { 1 })
            segment(0xDA, byteArrayOf(1, 1, 0, 0, 0x3F, 0))
            write(ByteArray(600) { if (it % 5 == 0) 0x55 else 0x12 })
            write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        }.toByteArray()

    /** XMP Google « Motion Photo », forme attributs, préfixe `GCamera` comme chez Pixel. */
    fun googleXmp(items: List<Item>, flag: String = "1", timestampUs: Long = 968_653): String = """
        <?xpacket begin="${'﻿'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
        <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about=""
                xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                xmlns:Container="http://ns.google.com/photos/1.0/container/"
                xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
              GCamera:MotionPhoto="$flag"
              GCamera:MotionPhotoVersion="1"
              GCamera:MotionPhotoPresentationTimestampUs="$timestampUs">
              <Container:Directory>
                <rdf:Seq>
                  <rdf:li rdf:parseType="Resource">
                    <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/>
                  </rdf:li>
                  ${items.joinToString("\n") { item ->
                      """<rdf:li rdf:parseType="Resource">
                    <Container:Item Item:Mime="${item.mime}" Item:Semantic="${item.semantic}" Item:Length="${item.length}" Item:Padding="${item.padding}"/>
                  </rdf:li>"""
                  }}
                </rdf:Seq>
              </Container:Directory>
            </rdf:Description>
          </rdf:RDF>
        </x:xmpmeta>
        <?xpacket end="w"?>
    """.trimIndent()

    /**
     * Même description sous le préfixe `Camera` de la spécification, propriétés en éléments
     * (et non en attributs) : drapeau et éléments du répertoire.
     */
    fun cameraElementXmp(videoLength: Long): String = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about=""
                xmlns:Camera="http://ns.google.com/photos/1.0/camera/"
                xmlns:Container="http://ns.google.com/photos/1.0/container/"
                xmlns:Item="http://ns.google.com/photos/1.0/container/item/">
              <Camera:MotionPhoto>1</Camera:MotionPhoto>
              <Camera:MotionPhotoPresentationTimestampUs>-1</Camera:MotionPhotoPresentationTimestampUs>
              <Container:Directory>
                <rdf:Seq>
                  <rdf:li rdf:parseType="Resource">
                    <Container:Item rdf:parseType="Resource">
                      <Item:Mime>image/jpeg</Item:Mime>
                      <Item:Semantic>Primary</Item:Semantic>
                    </Container:Item>
                  </rdf:li>
                  <rdf:li rdf:parseType="Resource">
                    <Container:Item rdf:parseType="Resource">
                      <Item:Mime>video/mp4</Item:Mime>
                      <Item:Semantic>MotionPhoto</Item:Semantic>
                      <Item:Length>$videoLength</Item:Length>
                    </Container:Item>
                  </rdf:li>
                </rdf:Seq>
              </Container:Directory>
            </rdf:Description>
          </rdf:RDF>
        </x:xmpmeta>
    """.trimIndent()

    /** Ancien format « MicroVideo » des Pixel (fichiers MVIMG_…). */
    fun microVideoXmp(offset: Long): String = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about="" xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
              GCamera:MicroVideo="1"
              GCamera:MicroVideoVersion="1"
              GCamera:MicroVideoOffset="$offset"
              GCamera:MicroVideoPresentationTimestampUs="1342287"/>
          </rdf:RDF>
        </x:xmpmeta>
    """.trimIndent()

    /** Photo ordinaire : XMP sans propriété de photo animée. */
    fun plainXmp(): String = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:CreatorTool="Focale"/>
          </rdf:RDF>
        </x:xmpmeta>
    """.trimIndent()

    /**
     * Remorque Samsung à accoler à un JPEG de [imageLength] octets : bloc `MotionPhoto_Data`
     * (la vidéo), autre bloc, puis l'en-tête SEFH qui les répertorie, sa taille et « SEFT ».
     * Rend la remorque et la position attendue de la vidéo.
     */
    fun samsungTrailer(imageLength: Long, video: ByteArray): Pair<ByteArray, Long> {
        val blocks = listOf(
            Triple(0x0A30, "MotionPhoto_Data", video),
            Triple(0x0AA1, "Image_UTC_Data", ascii("1700000000000")),
        )
        val out = ByteArrayOutputStream()
        val starts = mutableListOf<Long>()
        val lengths = mutableListOf<Long>()
        for ((type, name, data) in blocks) {
            starts += imageLength + out.size()
            val block = ByteArrayOutputStream().apply {
                write(int16le(0))
                write(int16le(type))
                write(int32le(name.length))
                write(ascii(name))
                write(data)
            }.toByteArray()
            lengths += block.size.toLong()
            out.write(block)
        }
        val directoryStart = imageLength + out.size()
        val directory = ByteArrayOutputStream().apply {
            write(ascii("SEFH"))
            write(int32le(107))
            write(int32le(blocks.size))
            blocks.forEachIndexed { index, (type, _, _) ->
                write(int16le(0))
                write(int16le(type))
                write(int32le((directoryStart - starts[index]).toInt()))
                write(int32le(lengths[index].toInt()))
            }
        }.toByteArray()
        out.write(directory)
        out.write(int32le(directory.size))
        out.write(ascii("SEFT"))
        return out.toByteArray() to starts[0] + 8 + "MotionPhoto_Data".length
    }

    private fun ByteArrayOutputStream.segment(marker: Int, payload: ByteArray) {
        write(0xFF)
        write(marker)
        val length = payload.size + 2
        write(length shr 8)
        write(length and 0xFF)
        write(payload)
    }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    private fun int32be(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private fun int32le(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())

    private fun int16le(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte())
}
