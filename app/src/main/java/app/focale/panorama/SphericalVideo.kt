package app.focale.panorama

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import app.focale.core.model.MediaItem
import app.focale.data.readAt

/** Projection d'une vidéo sphérique ; une projection inconnue n'est pas proposée en 360°. */
enum class SphericalProjection { EQUIRECTANGULAR, CUBEMAP, MESH, OTHER }

/**
 * Métadonnées sphériques d'une vidéo MP4 ou MOV.
 *
 * @property version 1 (XMP GSpherical, boîte `uuid`) ou 2 (boîtes `st3d` et `sv3d`).
 * @property area zone de la sphère couverte par l'image (d'un œil), null si les bornes sont
 *   incohérentes ; un cube ou un maillage couvre toute la sphère.
 * @property yaw longitude (degrés) du centre de la vue initiale, null si le fichier n'en dit rien :
 *   cap initial (V1) ou d'après le cap de la pose de la projection (`prhd`, V2).
 * @property pitch latitude (degrés) du centre de la vue initiale (V1), null sinon.
 * @property pose inclinaison et roulis de la pose de la projection (`prhd`, V2) : horizon redressé.
 * @property geometry cube ou maillage lisible ; null pour l'équirectangulaire, ou un cube et un
 *   maillage inutilisables (disposition inconnue, marge trop grande, maillage mal formé).
 */
data class SphericalMetadata(
    val version: Int,
    val projection: SphericalProjection,
    val stereo: StereoMode,
    val area: SphereArea?,
    val yaw: Double? = null,
    val pitch: Double? = null,
    val pose: SpherePose = SpherePose.LEVEL,
    val geometry: SphereProjection? = null,
)

/**
 * Lecture des métadonnées sphériques d'un fichier ISO BMFF (MP4, MOV, 3GP) sans toucher aux
 * données vidéo : seuls les en-têtes des boîtes menant à la piste vidéo sont lus, où que soit la
 * boîte `moov` (début ou fin du fichier), quelques dizaines d'octets à la fois.
 *
 * - **V2** (Spherical Video V2, Google) : dans l'entrée de description (`stsd`) de la piste
 *   vidéo, `st3d` (stéréoscopie) et `sv3d` → `proj` → `prhd` (pose) et la projection : `equi`
 *   (équirectangulaire, bornes de l'image sur la sphère), `cbmp` (cube : disposition et marge,
 *   avec les dimensions de l'entrée) ou `mshp` (maillage, lu en entier : quelques kilo-octets,
 *   [MeshProjection.MAX_BOX] au plus) ;
 * - **V1** : boîte `uuid` `ffcc8263-f855-4a93-8814-587a02521fdd` de la piste vidéo (ou de `moov`),
 *   document XML GSpherical (`Spherical`, `ProjectionType`, `StereoMode`, cap et inclinaison
 *   initiaux, recadrage façon GPano).
 *
 * Un fichier mal formé (taille de boîte trop petite, qui déborde de son parent, fichier tronqué)
 * ne lève rien : la lecture s'arrête et rien n'est trouvé.
 */
internal object SphericalVideoHeaders {

    /** [length] octets à [offset], null au-delà de la fin du fichier. */
    fun interface Reader {
        fun read(offset: Long, length: Int): ByteArray?
    }

    const val GSPHERICAL_NS = "http://ns.google.com/videos/1.0/spherical/"
    private val V1_UUID = byteArrayOf(
        0xff.toByte(), 0xcc.toByte(), 0x82.toByte(), 0x63, 0xf8.toByte(), 0x55, 0x4a, 0x93.toByte(),
        0x88.toByte(), 0x14, 0x58, 0x7a, 0x02, 0x52, 0x1f, 0xdd.toByte(),
    )

    /** Boîtes filles lues au plus par conteneur : un fichier piégé ne fait pas tourner la lecture. */
    private const val MAX_CHILDREN = 512

    /** Document V1 : quelques centaines d'octets en pratique. */
    private const val MAX_XML = 1 shl 20

    /** Champs d'une `VisualSampleEntry` (ISO/IEC 14496-12), avant ses boîtes filles. */
    private const val VISUAL_SAMPLE_ENTRY = 78

    /** Largeur puis hauteur (16 bits chacune) dans ces champs. */
    private const val ENTRY_WIDTH = 24

    private class Box(val type: String, val start: Long, private val header: Int, val size: Long) {
        val content: Long get() = start + header
        val end: Long get() = start + size
        val contentLength: Long get() = size - header
    }

    /** Métadonnées sphériques de la première piste vidéo qui en a, ou null. */
    fun read(reader: Reader): SphericalMetadata? {
        val top = children(reader, 0, null)
        // Pas un fichier ISO BMFF (Matroska, AVI…) : la première « boîte » n'a pas de type lisible.
        if (top.isEmpty() || top.first().type.any { it.code !in 0x20..0x7e }) return null
        val moov = top.firstOrNull { it.type == "moov" } ?: return null
        val tracks = children(reader, moov.content, moov.end)
        var v1: SphericalMetadata? = null
        for (trak in tracks) {
            if (trak.type != "trak") continue
            val parts = children(reader, trak.content, trak.end)
            val mdia = parts.firstOrNull { it.type == "mdia" } ?: continue
            val media = children(reader, mdia.content, mdia.end)
            if (!isVideo(reader, media)) continue
            sampleEntry(reader, media)?.let { entry -> v2(reader, entry) }?.let { return it }
            if (v1 == null) v1 = parts.firstNotNullOfOrNull { v1(reader, it) }
        }
        // Quelques outils rangent la boîte V1 dans `moov` plutôt que dans la piste.
        return v1 ?: tracks.firstNotNullOfOrNull { v1(reader, it) }
    }

    /** Piste vidéo : gestionnaire `vide` de la boîte `hdlr` (pleine : version, pre_defined, type). */
    private fun isVideo(reader: Reader, media: List<Box>): Boolean {
        val handler = media.firstOrNull { it.type == "hdlr" }?.takeIf { it.contentLength >= 12 } ?: return false
        return reader.read(handler.content + 8, 4)?.let { String(it, Charsets.ISO_8859_1) } == "vide"
    }

    /** Entrée de description d'une piste vidéo : dimensions de l'image, en pixels, et boîtes filles. */
    private class SampleEntry(val width: Int, val height: Int, val boxes: List<Box>)

    /** Première entrée de `mdia/minf/stbl/stsd`. */
    private fun sampleEntry(reader: Reader, media: List<Box>): SampleEntry? {
        val minf = media.firstOrNull { it.type == "minf" } ?: return null
        val stbl = children(reader, minf.content, minf.end).firstOrNull { it.type == "stbl" } ?: return null
        val stsd = children(reader, stbl.content, stbl.end).firstOrNull { it.type == "stsd" } ?: return null
        // Boîte pleine, puis le nombre d'entrées.
        val entry = children(reader, stsd.content + 8, stsd.end).firstOrNull() ?: return null
        if (entry.contentLength < VISUAL_SAMPLE_ENTRY) return null
        // Champs de VisualSampleEntry : réservés, référence, prédéfinis, puis largeur et hauteur.
        val size = reader.read(entry.content + ENTRY_WIDTH, 4) ?: return null
        val width = size.u16(0)
        val height = size.u16(2)
        return SampleEntry(width, height, children(reader, entry.content + VISUAL_SAMPLE_ENTRY, entry.end))
    }

    private fun v2(reader: Reader, entry: SampleEntry): SphericalMetadata? {
        val sv3d = entry.boxes.firstOrNull { it.type == "sv3d" } ?: return null
        val stereo = entry.boxes.firstOrNull { it.type == "st3d" }?.let { st3d ->
            // Boîte pleine, puis stereo_mode : 0 mono, 1 haut-bas, 2 gauche-droite, 3 propre au
            // fichier (maillages), 4 droite-gauche.
            when (reader.read(st3d.content, 5)?.get(4)?.toInt()) {
                1 -> StereoMode.TOP_BOTTOM
                2 -> StereoMode.LEFT_RIGHT
                3 -> StereoMode.CUSTOM
                4 -> StereoMode.RIGHT_LEFT
                else -> StereoMode.MONO
            }
        } ?: StereoMode.MONO
        val proj = children(reader, sv3d.content, sv3d.end).firstOrNull { it.type == "proj" }
            ?: return SphericalMetadata(2, SphericalProjection.OTHER, stereo, null)
        val parts = children(reader, proj.content, proj.end)
        // Pose de la projection, en 16.16, lue comme FFmpeg : un cap positif tourne la sphère vers
        // la droite (on voit d'abord ce qui était à gauche du centre de l'image : vue initiale),
        // une inclinaison positive la fait monter, un roulis positif la penche vers la droite
        // (horizon redressé, [SpherePose]).
        val pose = parts.firstOrNull { it.type == "prhd" }?.takeIf { it.contentLength >= 16 }?.let { prhd -> reader.read(prhd.content, 16) }
        val yaw = pose?.let { -it.s32(4) / 65_536.0 }
        val level = pose?.let { SpherePose.of(it.s32(8) / 65_536.0, it.s32(12) / 65_536.0) } ?: SpherePose.LEVEL
        val equi = parts.firstOrNull { it.type == "equi" }
        if (equi != null) {
            val bounds = reader.read(equi.content, 20) ?: return null
            return SphericalMetadata(2, SphericalProjection.EQUIRECTANGULAR, stereo, bounded(bounds), yaw = yaw, pose = level)
        }
        val cbmp = parts.firstOrNull { it.type == "cbmp" }
        if (cbmp != null) {
            return SphericalMetadata(2, SphericalProjection.CUBEMAP, stereo, SphereArea.FULL, yaw = yaw, pose = level, geometry = cubemap(reader, cbmp, entry, stereo))
        }
        // `ytmp` : nom des premiers fichiers de YouTube.
        val mshp = parts.firstOrNull { it.type == "mshp" || it.type == "ytmp" }
        if (mshp != null) {
            val mesh = mshp.takeIf { it.contentLength <= MeshProjection.MAX_BOX }
                ?.let { reader.read(it.content, it.contentLength.toInt()) }
                ?.let(MeshProjection::decode)
            return SphericalMetadata(2, SphericalProjection.MESH, stereo, SphereArea.FULL, yaw = yaw, pose = level, geometry = mesh?.let(SphereProjection::Mesh))
        }
        return SphericalMetadata(2, SphericalProjection.OTHER, stereo, null, yaw = yaw, pose = level)
    }

    /**
     * Cube de la boîte `cbmp` (boîte pleine, puis disposition et marge, entiers de 32 bits) :
     * disposition 0 seulement (3 × 2, la seule que définit la RFC), dans l'image d'un œil de
     * l'entrée ; null sinon, ou si la stéréoscopie n'est décrite que par des maillages.
     */
    private fun cubemap(reader: Reader, cbmp: Box, entry: SampleEntry, stereo: StereoMode): SphereProjection.Cubemap? {
        if (cbmp.contentLength < 12) return null
        val fields = reader.read(cbmp.content, 12) ?: return null
        if (fields.u32(4) != 0L) return null
        val (width, height) = when (stereo) {
            StereoMode.MONO -> entry.width to entry.height
            StereoMode.TOP_BOTTOM -> entry.width to entry.height / 2
            StereoMode.LEFT_RIGHT, StereoMode.RIGHT_LEFT -> entry.width / 2 to entry.height
            StereoMode.CUSTOM -> return null
        }
        return SphereProjection.Cubemap.of(fields.u32(8), width, height)
    }

    /**
     * Zone couverte d'après les bornes de `equi` (boîte pleine, puis haut, bas, gauche, droite :
     * part de la sphère retirée de chaque côté, en virgule fixe 0.32) ; null si elles se croisent.
     */
    private fun bounded(bounds: ByteArray): SphereArea? {
        fun fraction(at: Int) = bounds.u32(at) / 4_294_967_296.0
        val top = fraction(4)
        val bottom = fraction(8)
        val left = fraction(12)
        val right = fraction(16)
        if (top + bottom >= 1 || left + right >= 1) return null
        return SphereArea(
            west = -180 + left * SphereArea.FULL_TURN,
            east = 180 - right * SphereArea.FULL_TURN,
            north = 90 - top * 180,
            south = -90 + bottom * 180,
        )
    }

    private fun v1(reader: Reader, box: Box): SphericalMetadata? {
        if (box.type != "uuid" || box.contentLength <= V1_UUID.size || box.contentLength > MAX_XML + V1_UUID.size) return null
        if (reader.read(box.content, V1_UUID.size)?.contentEquals(V1_UUID) != true) return null
        val xml = reader.read(box.content + V1_UUID.size, (box.contentLength - V1_UUID.size).toInt()) ?: return null
        val values = XmpPanorama.properties(xml, GSPHERICAL_NS, "GSpherical")
        if (values["Spherical"]?.let(XmpPanorama::flag) != true) return null
        val projection = values["ProjectionType"]?.trim()?.lowercase()
        val stereo = when (values["StereoMode"]?.trim()?.lowercase()) {
            "top-bottom" -> StereoMode.TOP_BOTTOM
            "left-right" -> StereoMode.LEFT_RIGHT
            else -> StereoMode.MONO
        }
        fun int(name: String): Int? = values[name]?.trim()?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
        fun real(name: String): Double? = values[name]?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }
        // Recadrage décrit comme celui d'une photo sphérique (GPano) ; sans lui, la sphère entière.
        val crop = GPano(
            croppedWidth = int("CroppedAreaImageWidthPixels"),
            croppedHeight = int("CroppedAreaImageHeightPixels"),
            croppedLeft = int("CroppedAreaLeftPixels"),
            croppedTop = int("CroppedAreaTopPixels"),
            fullWidth = int("FullPanoWidthPixels"),
            fullHeight = int("FullPanoHeightPixels"),
        )
        val area = if (crop.fullWidth != null && crop.fullHeight != null) {
            PanoramaRules.areaOf(crop, crop.croppedWidth ?: crop.fullWidth, crop.croppedHeight ?: crop.fullHeight)
        } else {
            SphereArea.FULL
        }
        return SphericalMetadata(
            version = 1,
            projection = if (projection == null || projection == "equirectangular") SphericalProjection.EQUIRECTANGULAR else SphericalProjection.OTHER,
            stereo = stereo,
            area = area,
            yaw = real("InitialViewHeadingDegrees"),
            pitch = real("InitialViewPitchDegrees"),
        )
    }

    /**
     * Boîtes de [from, to[ ; [to] null : premier niveau, jusqu'à la fin du fichier. La lecture
     * s'arrête à la première boîte invalide : taille plus petite que son en-tête, ou qui déborde.
     */
    private fun children(reader: Reader, from: Long, to: Long?): List<Box> {
        val found = ArrayList<Box>()
        var position = from
        while (found.size < MAX_CHILDREN && (to == null || position + 8 <= to)) {
            val header = reader.read(position, 16) ?: reader.read(position, 8) ?: break
            val size32 = header.u32(0)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            val (size, headerLength) = when (size32) {
                1L -> if (header.size >= 16) header.u64(8) to 16 else break
                // Jusqu'à la fin du parent (ou du fichier) : forcément la dernière boîte.
                0L -> (if (to != null) to - position else Long.MAX_VALUE - position) to 8
                else -> size32 to 8
            }
            if (size < headerLength || (to != null && size > to - position)) break
            found += Box(type, position, headerLength, size)
            if (size32 == 0L || position + size < position) break
            position += size
        }
        return found
    }

    private fun ByteArray.u32(at: Int): Long =
        ((this[at].toLong() and 0xFF) shl 24) or ((this[at + 1].toLong() and 0xFF) shl 16) or
            ((this[at + 2].toLong() and 0xFF) shl 8) or (this[at + 3].toLong() and 0xFF)

    private fun ByteArray.s32(at: Int): Int = u32(at).toInt()

    private fun ByteArray.u16(at: Int): Int = ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)

    /** Taille 64 bits ; au-delà de 2⁶³ (négative), la boîte est refusée. */
    private fun ByteArray.u64(at: Int): Long = (u32(at) shl 32) or u32(at + 4)
}

/**
 * Choix du mode d'une vidéo d'après ses métadonnées sphériques : vue immersive (« 360° ») pour
 * une projection équirectangulaire aux bornes cohérentes, sphère complète ou partielle (VR180,
 * bande horizontale), un cube de disposition connue ou un maillage lisible ; rien pour une vidéo
 * ordinaire, une projection inconnue, ou une stéréoscopie propre au fichier sans maillage pour la
 * décrire. Vue initiale : le cap du fichier, sinon le centre de l'image ; l'inclinaison V1, sinon
 * l'horizon s'il est couvert ; ramenés dans la zone. Horizon redressé d'après la pose (V2).
 */
object SphericalVideoRules {

    fun classify(mediaId: Long, metadata: SphericalMetadata?): Panorama? {
        metadata ?: return null
        val projection = when (metadata.projection) {
            SphericalProjection.EQUIRECTANGULAR -> SphereProjection.Equirectangular.takeIf { metadata.stereo != StereoMode.CUSTOM }
            SphericalProjection.CUBEMAP, SphericalProjection.MESH -> metadata.geometry
            SphericalProjection.OTHER -> null
        } ?: return null
        val area = metadata.area ?: return null
        val yaw = SphereMath.clampYaw(metadata.yaw ?: area.centerLongitude, area)
        val pitch = metadata.pitch ?: if (0.0 in area.south..area.north) 0.0 else (area.north + area.south) / 2
        return Panorama(
            mediaId = mediaId,
            kind = PanoramaKind.SPHERE,
            area = area,
            initialYaw = yaw,
            initialPitch = pitch.coerceIn(area.south, area.north),
            stereo = metadata.stereo,
            projection = projection,
            pose = metadata.pose,
        )
    }
}

/**
 * Détection des vidéos 360°, à l'ouverture dans la visionneuse, comme celle des photos
 * panoramiques ([Panoramas]) : rien n'est analysé en arrière-plan ni enregistré dans la base, les
 * métadonnées lues (absence comprise) restent en mémoire par média et date de modification. Un
 * maillage compte pour autant d'entrées qu'il occupe de fois [MESH_BYTES_PER_ENTRY] : 8 Mo au plus
 * de maillages en mémoire.
 */
object SphericalVideos {

    private const val TAG = "SphericalVideos"
    private const val CACHE_SIZE = 512
    private const val MESH_BYTES_PER_ENTRY = 16 * 1024

    /** Conteneurs ISO BMFF, les seuls qui portent ces métadonnées. */
    private val types = setOf("video/mp4", "video/quicktime", "video/3gpp", "video/3gpp2", "video/x-m4v")

    private class Detection(val metadata: SphericalMetadata?)

    private val cache = object : LruCache<String, Detection>(CACHE_SIZE) {
        override fun sizeOf(key: String, value: Detection): Int =
            1 + ((value.metadata?.geometry as? SphereProjection.Mesh)?.mesh?.byteCount ?: 0) / MESH_BYTES_PER_ENTRY
    }

    fun isCandidate(item: MediaItem): Boolean = item.isVideo && isCandidate(item.mimeType)

    /** Type d'un conteneur ISO BMFF. */
    fun isCandidate(mimeType: String): Boolean = mimeType.lowercase() in types

    /** Bloquant : lit au besoin les en-têtes du fichier. À appeler hors du fil principal. */
    fun detect(resolver: ContentResolver, item: MediaItem): Panorama? {
        if (!isCandidate(item)) return null
        val key = "${item.id}:${item.dateModified}"
        val known = cache.get(key)
        val metadata = if (known != null) {
            known.metadata
        } else {
            val read = try {
                read(resolver, item.uri)
            } catch (error: Exception) {
                // Fichier illisible pour l'instant (permission, suppression…) : rien n'est mémorisé.
                Log.d(TAG, "Lecture impossible : ${item.displayName}", error)
                return null
            }
            cache.put(key, Detection(read))
            read
        }
        return SphericalVideoRules.classify(item.id, metadata)
    }

    /**
     * Bloquant : métadonnées de la vidéo [uri], par accès aléatoire à son descripteur de fichier
     * (un fournisseur qui n'en donne pas : rien) ; lève l'erreur de lecture.
     */
    fun read(resolver: ContentResolver, uri: Uri): SphericalMetadata? =
        resolver.openFileDescriptor(uri, "r")?.let { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                val channel = stream.channel
                SphericalVideoHeaders.read { offset, length -> channel.readAt(offset, length) }
            }
        }
}
