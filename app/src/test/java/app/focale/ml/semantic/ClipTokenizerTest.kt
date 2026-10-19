package app.focale.ml.semantic

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipTokenizerTest {

    private val tokenizer = File("src/main/assets/${ClipModels.VOCABULARY_ASSET}").inputStream().use { ClipTokenizer.load(it) }

    /** Lignes « texte JSON \t identifiants » produites par tools/clip/export_clip.py. */
    private val reference: List<Pair<String, IntArray>> =
        File("src/test/resources/clip/tokenizer_reference.tsv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val (text, ids) = line.split('\t')
                Json.decodeFromString<String>(text) to ids.split(' ').map(String::toInt).toIntArray()
            }

    @Test
    fun `vocabulaire complet de CLIP`() {
        assertEquals(49408, tokenizer.vocabularySize)
        assertEquals(49406, tokenizer.startToken)
        assertEquals(49407, tokenizer.endToken)
    }

    @Test
    fun `jetons identiques au tokeniseur de référence en Python`() {
        assertTrue(reference.size >= 20)
        for ((text, expected) in reference) {
            val tokens = tokenizer.tokenize(text)
            val actual = tokens.takeWhile { it != 0L }.map(Long::toInt).toIntArray()
            assertArrayEquals("« $text »", expected, actual)
            assertTrue(tokens.drop(actual.size).all { it == 0L })
        }
    }

    @Test
    fun `phrase simple et casse ignorée`() {
        val expected = tokenizer.encode("a photo of a dog")
        assertArrayEquals(expected, tokenizer.encode("  A PHOTO   of a Dog "))
        assertEquals(5, expected.size)
    }

    @Test
    fun `texte trop long tronqué avec le jeton de fin en dernier`() {
        val tokens = tokenizer.tokenize("dog ".repeat(200))
        assertEquals(ClipTokenizer.CONTEXT_LENGTH, tokens.size)
        assertEquals(tokenizer.startToken.toLong(), tokens.first())
        assertEquals(tokenizer.endToken.toLong(), tokens.last())
    }

    @Test
    fun `vocabulaire réduit aux premières fusions`() {
        val reduced = File("src/main/assets/${ClipModels.VOCABULARY_ASSET}").inputStream().use { ClipTokenizer.load(it, maxMerges = 1000) }
        assertEquals(512 + 1000 + 2, reduced.vocabularySize)
        assertTrue(reduced.encode("supercalifragilistic").all { it < 512 + 1000 })
    }
}
