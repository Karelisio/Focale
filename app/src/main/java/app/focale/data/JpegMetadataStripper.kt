package app.focale.data

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Copie un JPEG en retirant ses segments de métadonnées, sans recompression :
 * EXIF et XMP (APP1), IPTC (APP13), commentaires, segments propriétaires et images
 * secondaires (MPF). Tout ce qui suit la fin de l'image principale est aussi ignoré
 * (vidéo d'une « photo animée », carte de gain…), car ces données peuvent elles-mêmes
 * contenir une localisation.
 *
 * Sont conservés : JFIF (APP0), le profil couleur ICC (APP2) et le segment Adobe
 * (APP14), nécessaires au bon rendu des couleurs.
 */
object JpegMetadataStripper {

    private const val SOI = 0xD8
    private const val EOI = 0xD9
    private const val SOS = 0xDA
    private const val APP0 = 0xE0
    private const val APP2 = 0xE2
    private const val APP14 = 0xEE
    private const val COM = 0xFE
    private const val BUFFER = 64 * 1024
    private val ICC_SIGNATURE = "ICC_PROFILE\u0000".toByteArray(Charsets.US_ASCII)

    /** @throws IOException si le flux n'est pas un JPEG complet et valide. */
    fun strip(input: InputStream, output: OutputStream) {
        val source = DataInputStream(BufferedInputStream(input, BUFFER))
        val sink = DataOutputStream(BufferedOutputStream(output, BUFFER))
        if (source.readUnsignedByte() != 0xFF || source.readUnsignedByte() != SOI) throw IOException("JPEG invalide")
        sink.writeMarker(SOI)
        var marker = nextMarker(source)
        while (marker != EOI) {
            if (marker in 0xD0..0xD7 || marker == 0x01) {
                // Marqueurs autonomes, sans longueur.
                sink.writeMarker(marker)
                marker = nextMarker(source)
                continue
            }
            val length = source.readUnsignedShort()
            if (length < 2) throw IOException("Segment JPEG invalide")
            val payload = ByteArray(length - 2).also(source::readFully)
            if (keep(marker, payload)) {
                sink.writeMarker(marker)
                sink.writeShort(length)
                sink.write(payload)
            }
            marker = if (marker == SOS) copyScan(source, sink) else nextMarker(source)
        }
        sink.writeMarker(EOI)
        sink.flush()
    }

    private fun keep(marker: Int, payload: ByteArray): Boolean = when (marker) {
        APP0, APP14 -> true
        APP2 -> payload.size >= ICC_SIGNATURE.size && ICC_SIGNATURE.indices.all { payload[it] == ICC_SIGNATURE[it] }
        in 0xE1..0xEF, COM -> false
        else -> true // tables, en-têtes de trame et de scan…
    }

    /** Prochain marqueur, après d'éventuels octets de bourrage 0xFF. */
    private fun nextMarker(source: DataInputStream): Int {
        if (source.readUnsignedByte() != 0xFF) throw IOException("Marqueur JPEG attendu")
        var marker = source.readUnsignedByte()
        while (marker == 0xFF) marker = source.readUnsignedByte()
        return marker
    }

    /**
     * Recopie les données compressées d'un scan et renvoie le marqueur qui le
     * termine. Dans ces données, 0xFF n'est suivi que de 0x00 (octet échappé) ou d'un
     * marqueur de reprise RSTn.
     */
    private fun copyScan(source: DataInputStream, sink: OutputStream): Int {
        while (true) {
            val byte = source.readUnsignedByte()
            if (byte != 0xFF) {
                sink.write(byte)
                continue
            }
            var next = source.readUnsignedByte()
            while (next == 0xFF) next = source.readUnsignedByte()
            if (next == 0x00 || next in 0xD0..0xD7) {
                sink.write(0xFF)
                sink.write(next)
            } else {
                return next
            }
        }
    }

    private fun OutputStream.writeMarker(marker: Int) {
        write(0xFF)
        write(marker)
    }
}
