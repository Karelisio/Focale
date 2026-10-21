package app.focale.perf

import app.focale.ml.ImageMetrics
import app.focale.ml.PlaceIndex
import app.focale.ml.PlaceTranslations
import app.focale.ml.semantic.ClipImageEncoder
import app.focale.ml.semantic.ClipModels
import app.focale.ml.semantic.ClipPreprocessor
import app.focale.ml.semantic.ClipTextEncoder
import app.focale.ml.semantic.ClipTokenizer
import app.focale.ml.semantic.EmbeddingIndex
import app.focale.people.ClusterFace
import app.focale.people.FaceAlignment
import app.focale.people.FaceClusterer
import app.focale.people.FaceEmbeddings
import app.focale.people.FacePoint
import app.focale.people.SFaceModel
import java.io.File
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Charge : calculs par photo de l'analyse, exécutables sur la JVM de bureau (sans Android) :
 * empreinte dHash et netteté (parties Kotlin, sans la réduction du bitmap), géocodage hors
 * ligne, alignement et empreinte SFace, prétraitement et empreinte TinyCLIP (ONNX Runtime pour
 * la JVM, deux fils comme dans l'application), recherche par le sens dans 50 000 empreintes et
 * regroupement des visages. ML Kit (étiquettes, texte, détection des visages) ne s'exécute pas
 * sur la JVM : son coût est estimé à part dans docs/PERFORMANCE.md. Budgets : environ cinq fois
 * les médianes mesurées.
 */
class AnalysisComputeLoadTest {

    private val report = LoadReport("calculs")
    private val assets = listOf("src/main/assets", "app/src/main/assets").map(::File).first { it.isDirectory }

    /** Image ARGB de synthèse, déterministe : dégradés, motifs et bruit, comme une photo. */
    private fun photo(width: Int, height: Int, seed: Int): IntArray {
        val random = Random(seed)
        return IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val r = (128 + 90 * sin(x / 23.0 + seed) + random.nextInt(-20, 20)).toInt().coerceIn(0, 255)
            val g = (128 + 90 * sin(y / 17.0 - seed) + random.nextInt(-20, 20)).toInt().coerceIn(0, 255)
            val b = ((x + y) * 255 / (width + height) + random.nextInt(-10, 10)).coerceIn(0, 255)
            0xFF shl 24 or (r shl 16) or (g shl 8) or b
        }
    }

    @Test
    fun `empreinte dHash et netteté par photo`() {
        // ImageAnalyzer : miniature de 512 px réduite à 384 × 288 (netteté) et à 9 × 8 (empreinte).
        val (width, height) = ImageMetrics.fit(512, 384, 384)
        val images = (0 until 8).map { photo(width, height, it) }
        val small = (0 until 8).map { photo(ImageMetrics.HASH_WIDTH, ImageMetrics.HASH_HEIGHT, it) }
        val perPhoto = report.samples(repeat = 200, warmup = 20) { index ->
            val k = (if (index < 0) -index else index) % images.size
            val gray = IntArray(width * height) { ImageMetrics.luminance(images[k][it]) }
            val blur = ImageMetrics.laplacianVariance(gray, width, height)
            val hash = ImageMetrics.dHash(IntArray(small[k].size) { ImageMetrics.luminance(small[k][it]) })
            assertTrue(blur > 0 && hash != 0L)
        }
        report.check("dHash et netteté, par photo (luminance 384 × 288 + laplacien)", perPhoto, budgetMs = 5.0)
    }

    @Test
    fun `géocodage hors ligne`() {
        var index: PlaceIndex? = null
        val load = report.samples(repeat = 3, warmup = 0) {
            index = File(assets, "geo/cities.bin").inputStream().use(PlaceIndex::read)
        }
        report.check("chargement de la base des lieux (par passage d'analyse)", load, budgetMs = 500.0, detail = "${index!!.size} lieux")
        val random = Random(3)
        val points = List(10_000) {
            // Surtout l'Europe, une part du reste du monde.
            if (random.nextDouble() < 0.8) random.nextDouble(36.0, 60.0) to random.nextDouble(-10.0, 25.0)
            else random.nextDouble(-50.0, 65.0) to random.nextDouble(-170.0, 170.0)
        }
        var found = 0
        val lookups = report.samples(repeat = 5) {
            found = points.count { (lat, lon) -> index!!.nearest(lat, lon) != null }
        }
        assertTrue(found > 5_000)
        report.check("10 000 recherches du lieu le plus proche", lookups, budgetMs = 600.0, detail = "$found lieux trouvés")
        // Noms français et anglais (PlaceNames), lus une fois par processus, sans les lieux.
        var names = 0
        val translations = report.samples(repeat = 3, warmup = 0) {
            names = File(assets, "geo/cities.bin").inputStream().use(PlaceTranslations::read).size
        }
        assertTrue(names > 0)
        report.check("chargement des noms traduits des lieux (une fois par processus)", translations, budgetMs = 250.0, detail = "$names noms")
    }

    @Test
    fun `alignement et empreinte SFace par visage`() {
        val load = report.once {
            SFaceModel(File(assets, SFaceModel.ASSET).readBytes(), threads = THREADS).close()
        }
        report.check("chargement de SFace (par passage d'analyse)", load, budgetMs = 2_000.0)
        SFaceModel(File(assets, SFaceModel.ASSET).readBytes(), threads = THREADS).use { model ->
            // Photo réduite à 1 280 px (FaceAnalyzer) et repères d'un visage de 200 px.
            val pixels = photo(1280, 960, 1)
            val points = FaceAlignment.ordered(
                rightEye = FacePoint(560f, 400f),
                leftEye = FacePoint(640f, 402f),
                nose = FacePoint(600f, 450f),
                mouthRight = FacePoint(570f, 500f),
                mouthLeft = FacePoint(630f, 501f),
            )
            var tensor = FloatArray(0)
            val align = report.samples(repeat = 50, warmup = 5) {
                val aligned = FaceAlignment.warp(pixels, 1280, 960, FaceAlignment.similarity(points))
                assertTrue(FaceAlignment.sharpness(aligned) >= 0)
                tensor = FaceAlignment.tensor(aligned)
            }
            report.check("alignement d'un visage (112 × 112) et netteté", align, budgetMs = 5.0)
            val embed = report.samples(repeat = 30, warmup = 3) {
                assertEquals(FaceEmbeddings.DIMENSION, model.embed(tensor).size)
            }
            report.check("empreinte SFace d'un visage ($THREADS fils)", embed, budgetMs = 300.0)
        }
    }

    @Test
    fun `prétraitement et empreinte TinyCLIP par photo, puis recherche par le sens`() {
        val load = report.once {
            ClipImageEncoder(ClipModels.openFile(File(assets, ClipModels.IMAGE_ASSET).path, THREADS)).close()
        }
        report.check("chargement de TinyCLIP image (par passage d'analyse)", load, budgetMs = 500.0)
        ClipImageEncoder(ClipModels.openFile(File(assets, ClipModels.IMAGE_ASSET).path, THREADS)).use { encoder ->
            // Miniature MediaStore de 512 × 384 (EmbeddingStep).
            val thumbnail = photo(512, 384, 2)
            var tensor = FloatArray(0)
            val preprocess = report.samples(repeat = 30, warmup = 3) { tensor = ClipPreprocessor.preprocess(thumbnail, 512, 384) }
            report.check("prétraitement CLIP d'une miniature (512 × 384 → 224 × 224)", preprocess, budgetMs = 25.0)
            val embed = report.samples(repeat = 20, warmup = 3) { assertEquals(ClipModels.DIMENSION, encoder.embed(tensor).size) }
            report.check("empreinte TinyCLIP d'une photo ($THREADS fils)", embed, budgetMs = 150.0)
        }

        // Recherche : phrase encodée puis comparée aux empreintes de toute la bibliothèque.
        val tokenizer = File(assets, ClipModels.VOCABULARY_ASSET).inputStream().use { ClipTokenizer.load(it) }
        ClipTextEncoder(ClipModels.openFile(File(assets, ClipModels.TEXT_ASSET).path, THREADS), tokenizer).use { text ->
            var query = FloatArray(0)
            val encode = report.samples(repeat = 10, warmup = 2) { query = text.embed("a photo of a dog on the beach") }
            report.check("empreinte d'une phrase de recherche", encode, budgetMs = 50.0)
            val random = Random(5)
            val builder = EmbeddingIndex.Builder(ClipModels.DIMENSION)
            repeat(PHOTOS) { id -> builder.add(id.toLong(), unit(random, ClipModels.DIMENSION)) }
            val index = builder.build()
            val scores = FloatArray(index.size)
            val score = report.samples(repeat = 10, warmup = 2) { index.score(query, scores) }
            report.check("comparaison à $PHOTOS empreintes (un fil)", score, budgetMs = 150.0)
        }
    }

    @Test
    fun `regroupement d'un lot de visages dans une grande bibliothèque`() {
        // État après l'analyse de 50 000 médias : 40 personnes, ~13 000 visages rattachés, 2 000 en attente.
        val random = Random(11)
        val people = List(40) { unit(random, FaceEmbeddings.DIMENSION) }
        fun faceOf(person: FloatArray?, id: Long): ClusterFace {
            val noise = unit(random, FaceEmbeddings.DIMENSION)
            val embedding = if (person == null) noise else FloatArray(person.size) { person[it] * 0.8f + noise[it] * 0.6f }
            return ClusterFace(id, mediaId = id, embedding = FaceEmbeddings.normalize(embedding))
        }
        val clusterer = FaceClusterer()
        var id = 0L
        repeat(13_000) { clusterer.addAssigned(personId = (it % people.size).toLong() + 1, face = faceOf(people[it % people.size], ++id)) }
        repeat(2_000) { clusterer.addPending(faceOf(null, ++id)) }
        val batches = report.samples(repeat = 20, warmup = 2) {
            // Lot de l'étape « visages » : 16 photos, ~1,6 visage par photo avec visages.
            val faces = List(26) { k -> faceOf(if (k % 3 == 0) null else people[random.nextInt(people.size)], ++id) }
            clusterer.cluster(faces)
        }
        report.check("regroupement d'un lot de 26 visages (40 personnes, 2 000 en attente)", batches, budgetMs = 30.0)
    }

    /** Vecteur aléatoire de norme 1. */
    private fun unit(random: Random, dimension: Int): FloatArray {
        val values = FloatArray(dimension) { random.nextFloat() - 0.5f }
        val norm = sqrt(values.sumOf { it.toDouble() * it }).toFloat()
        for (i in values.indices) values[i] /= norm
        return values
    }

    private companion object {
        /** Fils de calcul d'ONNX Runtime, comme EmbeddingStep et SFaceModel. */
        const val THREADS = 2
        const val PHOTOS = 47_500
    }
}
