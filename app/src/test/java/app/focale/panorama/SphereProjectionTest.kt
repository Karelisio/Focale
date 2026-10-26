package app.focale.panorama

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cubes (`cbmp`) et maillages (`mshp`) de vraies vidéos MP4 (voir `tools/spherical/make_fixtures.py`) :
 * lecture des boîtes, choix du mode, et géométrie vérifiée sur les images que FFmpeg (`v360`) a
 * produites d'une sphère dont chaque pixel a pour couleur sa direction : pour des centaines de
 * directions, le point du cube ou du maillage qu'on y voit (rayon depuis le centre, coordonnées de
 * texture interpolées comme le fait OpenGL) a bien cette direction pour couleur.
 */
class SphereProjectionTest {

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/spherical/$name")) { name }.use { it.readBytes() }

    private fun image(name: String): BufferedImage =
        requireNotNull(javaClass.getResourceAsStream("/spherical/$name")) { name }.use { ImageIO.read(it) }

    private fun metadata(bytes: ByteArray): SphericalMetadata? = SphericalVideoHeaders.read { offset, length ->
        if (offset < 0 || offset + length > bytes.size) null else bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    private fun metadata(name: String) = metadata(fixture(name))

    @Test
    fun `cube sans marge, disposition 0, dimensions de l'entrée vidéo`() {
        val found = requireNotNull(metadata("v2-cubemap.mp4"))
        assertEquals(SphericalProjection.CUBEMAP, found.projection)
        assertEquals(SphereProjection.Cubemap(padding = 0, width = 96, height = 64), found.geometry)
        assertEquals(SphereArea.FULL, found.area)
        assertTrue(found.pose.isLevel)
        val panorama = requireNotNull(SphericalVideoRules.classify(21, found))
        assertEquals(PanoramaKind.SPHERE, panorama.kind)
        assertEquals(found.geometry, panorama.projection)
        assertEquals(0.0, panorama.initialYaw, 1e-9)
        assertEquals(0.0, panorama.initialPitch, 1e-9)
    }

    @Test
    fun `cube avec marge, écrit par FFmpeg, et pose de la projection`() {
        val found = requireNotNull(metadata("v2-cubemap-padded-ffmpeg.mp4"))
        assertEquals(SphereProjection.Cubemap(padding = 4, width = 96, height = 64), found.geometry)
        // Cap de 30° : la sphère tournée vers la droite, la vue part de 30° à gauche ; horizon redressé.
        assertEquals(-30.0, found.yaw!!, 1e-9)
        assertEquals(SpherePose(pitch = 10.0, roll = -5.0), found.pose)
        val panorama = requireNotNull(SphericalVideoRules.classify(22, found))
        assertEquals(-30.0, panorama.initialYaw, 1e-9)
        assertEquals(SpherePose(pitch = 10.0, roll = -5.0), panorama.pose)
    }

    @Test
    fun `pose 16_16 d'une vidéo équirectangulaire écrite par FFmpeg`() {
        val found = requireNotNull(metadata("v2-equi-pose-ffmpeg.mp4"))
        assertEquals(SphericalProjection.EQUIRECTANGULAR, found.projection)
        assertEquals(20.0, found.yaw!!, 1e-9)
        assertEquals(12.5, found.pose.pitch, 1e-9)
        assertEquals(7.25, found.pose.roll, 1e-9)
        assertEquals(SpherePose(12.5, 7.25), SphericalVideoRules.classify(23, found)?.pose)
    }

    @Test
    fun `cube stéréoscopique, marge trop grande, stéréoscopie propre au fichier`() {
        val cube = fixture("v2-cubemap.mp4")
        val stereo = cube.indexOf("st3d") + 8
        // Haut-bas : cube dans la moitié haute ; gauche-droite et droite-gauche : moitié de largeur.
        assertEquals(SphereProjection.Cubemap(0, 96, 32), metadata(cube.copyOf().also { it[stereo] = 1 })?.geometry)
        assertEquals(SphereProjection.Cubemap(0, 48, 64), metadata(cube.copyOf().also { it[stereo] = 2 })?.geometry)
        assertEquals(SphereProjection.Cubemap(0, 48, 64), metadata(cube.copyOf().also { it[stereo] = 4 })?.geometry)
        assertEquals(StereoMode.RIGHT_LEFT, metadata(cube.copyOf().also { it[stereo] = 4 })?.stereo)
        val custom = requireNotNull(metadata(cube.copyOf().also { it[stereo] = 3 }))
        assertEquals(StereoMode.CUSTOM, custom.stereo)
        assertNull(custom.geometry)
        assertNull(SphericalVideoRules.classify(24, custom))
        // Marge de 16 px sur des faces de 32 : rien à afficher.
        val padding = cube.indexOf("cbmp") + 4 + 4 + 4
        val padded = cube.copyOf().also { it[padding + 3] = 16 }
        assertEquals(SphericalProjection.CUBEMAP, metadata(padded)?.projection)
        assertNull(metadata(padded)?.geometry)
        assertNull(SphereProjection.Cubemap.of(padding = 16, width = 96, height = 64))
        assertNotNull(SphereProjection.Cubemap.of(padding = 15, width = 96, height = 64))
        assertNull(SphereProjection.Cubemap.of(padding = 0, width = 2, height = 64))
    }

    @Test
    fun `géométrie du cube, faces et orientation de FFmpeg`() {
        val mesh = CubeMesh.build(SphereProjection.Cubemap(0, 96, 64))
        assertEquals(24, mesh.vertexCount)
        assertEquals(36, mesh.indices.size)
        assertMatches(mesh, image("cube.png"))
        // Avant, droit devant : au centre de la case du milieu de la ligne du bas.
        val (u, v) = requireNotNull(textureAt(mesh, doubleArrayOf(0.0, 0.0, -1.0)))
        assertEquals(0.5, u, 1e-6)
        assertEquals(0.75, v, 1e-6)
        // Sans marge, la face s'arrête au centre des pixels du bord : coin haut gauche de la face de droite.
        val corner = (0 until mesh.vertexCount).minBy { mesh.texCoords[it * 2] + mesh.texCoords[it * 2 + 1] }
        assertEquals(0.5f / 96, mesh.texCoords[corner * 2], 1e-6f)
        assertEquals(0.5f / 64, mesh.texCoords[corner * 2 + 1], 1e-6f)
    }

    @Test
    fun `marge du cube hors du rendu`() {
        val mesh = CubeMesh.build(SphereProjection.Cubemap(4, 96, 64))
        assertMatches(mesh, image("cube-pad.png"))
        // Les faces commencent à 4 px du bord de leur case.
        assertEquals(4f / 96, mesh.texCoords.filterIndexed { index, _ -> index % 2 == 0 }.min(), 1e-6f)
        assertEquals(4f / 64, mesh.texCoords.filterIndexed { index, _ -> index % 2 == 1 }.min(), 1e-6f)
        // Lire l'image avec marge comme si elle n'en avait pas : de vrais écarts.
        val wrong = errors(CubeMesh.build(SphereProjection.Cubemap(0, 96, 64)), image("cube-pad.png"))
        assertTrue("écart maximal ${wrong.max()}", wrong.max() > 5)
    }

    @Test
    fun `maillage compressé d'un cube équi-angulaire façon YouTube`() {
        val found = requireNotNull(metadata("v2-mesh-eac.mp4"))
        assertEquals(SphericalProjection.MESH, found.projection)
        assertEquals(SphereArea.FULL, found.area)
        val mesh = (found.geometry as SphereProjection.Mesh).mesh
        // 6 faces de 9 × 9 sommets ; 4 faces de 128 triangles et 2 faces en 8 bandes de 16 triangles.
        assertEquals(6 * 81, mesh.vertexCount)
        assertEquals(6 * 128 * 3, mesh.indices.size)
        // Coins du cube (à √3) ramenés au rayon 1 : entre les plans de coupe de la caméra.
        val radii = (0 until mesh.vertexCount).map { radius(mesh, it) }
        assertEquals(1.0, radii.max(), 1e-5)
        assertMatches(mesh, image("eac.png"))
        assertEquals(PanoramaKind.SPHERE, SphericalVideoRules.classify(25, found)?.kind)
    }

    @Test
    fun `maillages non compressés de chaque œil, éventails, image fixe ignorée`() {
        val found = requireNotNull(metadata("v2-mesh-stereo.mp4"))
        assertEquals(StereoMode.CUSTOM, found.stereo)
        val mesh = (found.geometry as SphereProjection.Mesh).mesh
        // Œil gauche seulement : 6 × 12 cases de 2 triangles, deux calottes de 12 triangles.
        assertEquals(13 * 7 + 2, mesh.vertexCount)
        assertEquals((6 * 12 * 2 + 2 * 12) * 3, mesh.indices.size)
        // Sphère de rayon 2,5 ramenée à 1 ; texture dans la moitié haute de l'image (v = 0 en haut).
        for (vertex in 0 until mesh.vertexCount) assertEquals(1.0, radius(mesh, vertex), 1e-5)
        val v = (0 until mesh.vertexCount).map { mesh.texCoords[it * 2 + 1] }
        assertTrue(v.all { it in 0f..0.5f })
        // Zénith en haut de l'image, centre de l'image droit devant.
        val apex = (0 until mesh.vertexCount).maxBy { mesh.positions[it * 3 + 1] }
        assertEquals(0f, mesh.texCoords[apex * 2 + 1], 1e-6f)
        val (u, front) = requireNotNull(textureAt(mesh, doubleArrayOf(0.0, 0.0, -1.0)))
        assertEquals(0.5, u, 0.02)
        assertEquals(0.25, front, 0.02)
        val panorama = requireNotNull(SphericalVideoRules.classify(26, found))
        assertEquals(StereoMode.CUSTOM, panorama.stereo)
    }

    @Test
    fun `maillage mal formé, encodage inconnu, flux deflate abîmé, rien et sans erreur`() {
        val eac = fixture("v2-mesh-eac.mp4")
        val unknown = eac.copyOf().also { "zzzz".toByteArray().copyInto(it, it.indexOf("dfl8")) }
        assertEquals(SphericalProjection.MESH, metadata(unknown)?.projection)
        assertNull(metadata(unknown)?.geometry)
        assertNull(SphericalVideoRules.classify(27, metadata(unknown)))
        // Contenu de la boîte `mshp` : tronqué ou altéré au hasard, jamais d'exception.
        for (name in listOf("v2-mesh-eac.mp4", "v2-mesh-stereo.mp4")) {
            val bytes = fixture(name)
            val start = bytes.indexOf("mshp") + 4
            val size = u32(bytes, start - 8).toInt()
            val content = bytes.copyOfRange(start, start - 8 + size)
            assertNotNull(name, MeshProjection.decode(content))
            for (length in 0 until content.size step 7) MeshProjection.decode(content.copyOf(length))
            val random = Random(7)
            repeat(300) {
                val altered = content.copyOf()
                repeat(3) { altered[12 + random.nextInt(altered.size - 12)] = random.nextInt(256).toByte() }
                MeshProjection.decode(altered)
            }
        }
        // Aucune boîte `mesh` ; seule une liste d'image fixe (texture 1) : rien à dessiner.
        assertNull(MeshProjection.decode(ByteArray(8) + "raw ".toByteArray()))
        assertNull(MeshProjection.decode(triangleMesh(texture = 1)))
        assertEquals(3, MeshProjection.decode(triangleMesh(texture = 0))?.indices?.size)
    }

    /**
     * Contenu d'une boîte `mshp` non compressée : un triangle (coordonnées 0 et 1, index sur 2 bits,
     * sommets sur 3 bits) de la texture [texture].
     */
    private fun triangleMesh(texture: Int): ByteArray {
        val bits = ArrayList<Boolean>()
        fun write(value: Int, count: Int) = (count - 1 downTo 0).forEach { bits += ((value shr it) and 1) == 1 }
        fun align() = repeat((8 - bits.size % 8) % 8) { bits += false }
        write(2, 32)
        write(0f.toRawBits(), 32)
        write(1f.toRawBits(), 32)
        write(3, 32)
        // Écarts en zigzag : (0, 0, 0, 0, 0), puis x et u à 1, puis x à 0 (-1), y et v à 1.
        listOf(listOf(0, 0, 0, 0, 0), listOf(2, 0, 0, 2, 0), listOf(1, 2, 0, 0, 2)).flatten().forEach { write(it, 2) }
        align()
        write(1, 32)
        write(texture, 8)
        write(0, 8)
        write(3, 32)
        listOf(0, 2, 2).forEach { write(it, 3) }
        align()
        val mesh = ByteArray(bits.size / 8) { byte -> (0 until 8).fold(0) { acc, bit -> (acc shl 1) or (if (bits[byte * 8 + bit]) 1 else 0) }.toByte() }
        val box = byteArrayOf(0, 0, 0, (8 + mesh.size).toByte()) + "mesh".toByteArray() + mesh
        return ByteArray(8) + "raw ".toByteArray() + box
    }

    /** Écart (degrés) entre chaque direction et la couleur du point de [mesh] qu'on y voit dans [image]. */
    private fun errors(mesh: TexturedMesh, image: BufferedImage): List<Double> {
        val found = ArrayList<Double>()
        for (pitch in -85..85 step 10) {
            for (yaw in -180 until 180 step 15) {
                val direction = direction(yaw + 0.5, pitch + 0.3)
                val (u, v) = requireNotNull(textureAt(mesh, direction)) { "rien à $yaw, $pitch" }
                found += angle(direction, color(image, u, v))
            }
        }
        return found
    }

    private fun assertMatches(mesh: TexturedMesh, image: BufferedImage) {
        val errors = errors(mesh, image)
        assertTrue("écart maximal ${errors.max()}°", errors.max() < 3.0)
        assertTrue("écart moyen ${errors.average()}°", errors.average() < 1.0)
    }

    private fun direction(yaw: Double, pitch: Double): DoubleArray {
        val y = Math.toRadians(yaw)
        val p = Math.toRadians(pitch)
        return doubleArrayOf(cos(p) * sin(y), sin(p), -cos(p) * cos(y))
    }

    /** Coordonnées de texture interpolées du triangle de [mesh] que traverse le rayon [d] depuis le centre. */
    private fun textureAt(mesh: TexturedMesh, d: DoubleArray): Pair<Double, Double>? {
        fun point(vertex: Int) = DoubleArray(3) { mesh.positions[vertex * 3 + it].toDouble() }
        for (triangle in 0 until mesh.indices.size / 3) {
            val (a, b, c) = (0 until 3).map { mesh.indices[triangle * 3 + it].toInt() and 0xFFFF }
            val pa = point(a)
            val e1 = minus(point(b), pa)
            val e2 = minus(point(c), pa)
            val p = cross(d, e2)
            val det = dot(e1, p)
            if (kotlin.math.abs(det) < 1e-12) continue
            val t = DoubleArray(3) { -pa[it] }
            val u = dot(t, p) / det
            if (u < -1e-9 || u > 1 + 1e-9) continue
            val q = cross(t, e1)
            val v = dot(d, q) / det
            if (v < -1e-9 || u + v > 1 + 1e-9) continue
            if (dot(e2, q) / det <= 0) continue
            fun tex(vertex: Int, axis: Int) = mesh.texCoords[vertex * 2 + axis].toDouble()
            return Pair(
                tex(a, 0) * (1 - u - v) + tex(b, 0) * u + tex(c, 0) * v,
                tex(a, 1) * (1 - u - v) + tex(b, 1) * u + tex(c, 1) * v,
            )
        }
        return null
    }

    /** Direction codée par la couleur de [image] en (u, v) (v = 0 en haut), filtrage bilinéaire. */
    private fun color(image: BufferedImage, u: Double, v: Double): DoubleArray {
        val x = u * image.width - 0.5
        val y = v * image.height - 0.5
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        fun pixel(px: Int, py: Int): DoubleArray {
            val rgb = image.getRGB(px.coerceIn(0, image.width - 1), py.coerceIn(0, image.height - 1))
            return doubleArrayOf(((rgb shr 16) and 0xFF) - 128.0, ((rgb shr 8) and 0xFF) - 128.0, (rgb and 0xFF) - 128.0)
        }
        val fx = x - x0
        val fy = y - y0
        return DoubleArray(3) { axis ->
            (pixel(x0, y0)[axis] * (1 - fx) + pixel(x0 + 1, y0)[axis] * fx) * (1 - fy) +
                (pixel(x0, y0 + 1)[axis] * (1 - fx) + pixel(x0 + 1, y0 + 1)[axis] * fx) * fy
        }
    }

    private fun angle(a: DoubleArray, b: DoubleArray): Double =
        Math.toDegrees(acos((dot(a, b) / sqrt(dot(a, a) * dot(b, b))).coerceIn(-1.0, 1.0)))

    private fun radius(mesh: TexturedMesh, vertex: Int): Double =
        sqrt((0 until 3).sumOf { (mesh.positions[vertex * 3 + it] * mesh.positions[vertex * 3 + it]).toDouble() })

    private fun minus(a: DoubleArray, b: DoubleArray) = DoubleArray(3) { a[it] - b[it] }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    private fun u32(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toLong() and 0xFF) shl 24) or ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or (bytes[at + 3].toLong() and 0xFF)

    private fun ByteArray.indexOf(type: String): Int {
        val pattern = type.toByteArray(Charsets.ISO_8859_1)
        for (start in 0..size - pattern.size) {
            if (pattern.indices.all { this[start + it] == pattern[it] }) return start
        }
        error("$type absent")
    }
}
