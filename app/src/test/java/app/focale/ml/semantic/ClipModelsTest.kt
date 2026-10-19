package app.focale.ml.semantic

import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exécute réellement les modèles embarqués (ONNX Runtime de bureau) et compare aux
 * empreintes produites en Python par tools/clip/export_clip.py : même prétraitement, même
 * tokeniseur, mêmes modèles quantifiés, et écart borné avec le modèle torch d'origine.
 */
class ClipModelsTest {

    private val resources = File("src/test/resources/clip")
    private val reference: JsonObject = Json.parseToJsonElement(File(resources, "embedding_reference.json").readText()).jsonObject

    private fun vector(json: JsonObject, key: String): FloatArray =
        json.getValue(key).jsonArray.map { it.jsonPrimitive.float }.toFloatArray()

    private fun cosine(a: FloatArray, b: FloatArray): Float = a.indices.fold(0f) { sum, i -> sum + a[i] * b[i] }

    private fun pixels(name: String): Triple<IntArray, Int, Int> {
        val image = ImageIO.read(File(resources, "$name.png"))
        val argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        return Triple(argb, image.width, image.height)
    }

    private fun tensor(name: String): FloatArray {
        val (argb, width, height) = pixels(name)
        return ClipPreprocessor.preprocess(argb, width, height)
    }

    @Test
    fun `prétraitement identique à celui de Python`() {
        for ((name, entry) in reference.getValue("images").jsonObject) {
            val expected = entry.jsonObject
            val tensor = tensor(name)
            assertEquals(3 * ClipPreprocessor.SIZE * ClipPreprocessor.SIZE, tensor.size)
            val sum = tensor.fold(0.0) { acc, v -> acc + v }
            assertEquals("somme $name", expected.getValue("pixels_sum").jsonPrimitive.double, sum, 1e-2)
            for ((index, value) in expected.getValue("pixels_samples").jsonObject) {
                assertEquals("$name[$index]", value.jsonPrimitive.float, tensor[index.toInt()], 1e-5f)
            }
        }
    }

    @Test
    fun `empreintes d'images cohérentes avec Python`() {
        for ((name, entry) in reference.getValue("images").jsonObject) {
            val embedding = imageEncoder.embed(tensor(name))
            assertEquals(ClipModels.DIMENSION, embedding.size)
            assertEquals(1f, cosine(embedding, embedding), 1e-4f)
            val sameModel = cosine(embedding, vector(entry.jsonObject, "onnx"))
            val original = cosine(embedding, vector(entry.jsonObject, "torch"))
            assertTrue("$name : $sameModel", sameModel > 0.999f)
            assertTrue("$name : $original", original > 0.98f)
        }
    }

    @Test
    fun `empreintes de texte cohérentes avec Python`() {
        for ((prompt, entry) in reference.getValue("texts").jsonObject) {
            val embedding = textEncoder.embed(prompt)
            val sameModel = cosine(embedding, vector(entry.jsonObject, "onnx"))
            val original = cosine(embedding, vector(entry.jsonObject, "torch"))
            assertTrue("« $prompt » : $sameModel", sameModel > 0.9999f)
            assertTrue("« $prompt » : $original", original > 0.98f)
        }
    }

    @Test
    fun `zéro-shot simple sur des couleurs et un damier`() {
        val colors = listOf("red", "green", "blue")
        val texts = colors.map { textEncoder.embed("a photo of something $it") }
        for ((index, color) in colors.withIndex()) {
            val image = imageEncoder.embed(tensor(color))
            val best = texts.indices.maxBy { cosine(image, texts[it]) }
            assertEquals(color, colors[best])
        }
        val board = imageEncoder.embed(tensor("checkerboard"))
        val checkerboard = textEncoder.embed("a photo of a checkerboard")
        val dog = textEncoder.embed("a photo of a dog on the beach")
        assertTrue(cosine(board, checkerboard) > cosine(board, dog) + 0.1f)
    }

    @Test
    fun `recherche de bout en bout dans un index`() {
        val builder = EmbeddingIndex.Builder(ClipModels.DIMENSION)
        val names = listOf("red", "green", "blue", "landscape", "checkerboard")
        names.forEachIndexed { id, name -> builder.add(id.toLong(), imageEncoder.embed(tensor(name))) }
        val index = builder.build()
        val scores = FloatArray(index.size)
        index.score(textEncoder.embed("a photo of a checkerboard"), scores)
        val picked = SimilarityThreshold.Default.select(scores).map { names[index.id(it).toInt()] }
        assertEquals(listOf("checkerboard"), picked)
        assertTrue(abs(scores.max() - scores[4]) < 1e-6f)
        // Rien de tel dans la bibliothèque : aucun résultat.
        index.score(textEncoder.embed("a photo of a dog on the beach"), scores)
        assertEquals(0, SimilarityThreshold.Default.select(scores).size)
    }

    companion object {
        private val imageEncoder by lazy {
            ClipImageEncoder(ClipModels.openFile("src/main/assets/${ClipModels.IMAGE_ASSET}", threads = 2))
        }
        private val textEncoder by lazy {
            val tokenizer = File("src/main/assets/${ClipModels.VOCABULARY_ASSET}").inputStream().use { ClipTokenizer.load(it) }
            ClipTextEncoder(ClipModels.openFile("src/main/assets/${ClipModels.TEXT_ASSET}", threads = 2), tokenizer)
        }

        @JvmStatic
        @AfterClass
        fun release() {
            imageEncoder.close()
            textEncoder.close()
        }
    }
}
