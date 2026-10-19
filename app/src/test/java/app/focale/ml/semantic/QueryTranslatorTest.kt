package app.focale.ml.semantic

import app.focale.ml.LabelDictionary
import app.focale.ml.SearchQueryParser
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryTranslatorTest {

    private val today = LocalDate.of(2026, 10, 19)
    private val zone = ZoneId.of("Europe/Paris")

    /** Chaîne complète de l'application : analyse de la requête puis gabarit. */
    private fun prompt(query: String): String? = QueryTranslator.prompt(SearchQueryParser.freeWords(query, today, zone))

    @Test
    fun `phrase française traduite avec article et préposition`() {
        assertEquals("a photo of a dog on the beach", prompt("chien sur la plage"))
        assertEquals("a photo of a cat on the bed", prompt("chat sur le lit"))
        assertEquals("a photo of my dog", prompt("mon chien"))
    }

    @Test
    fun `adjectifs replacés avant le nom`() {
        assertEquals("a photo of a red car", prompt("voiture rouge"))
        assertEquals("a photo of black cats", prompt("chats noirs"))
        assertEquals("a photo of a black and white dog", prompt("chien noir et blanc"))
        assertEquals("a photo of a small white house", prompt("petite maison blanche"))
        assertEquals("a photo of a pink car", prompt("voiture rose"))
        assertEquals("a photo of a rose", prompt("rose"))
    }

    @Test
    fun `expressions figées et pluriels`() {
        assertEquals("a photo of a sunset on the sea", prompt("coucher de soleil sur la mer"))
        assertEquals("a photo of sunsets", prompt("couchers de soleil"))
        assertEquals("a photo of a birthday cake", prompt("gâteau d'anniversaire"))
        assertEquals("a photo of the eiffel tower at night", prompt("tour Eiffel la nuit"))
        assertEquals("a photo of fireworks", prompt("feux d'artifice"))
        assertEquals("a photo of horses in the snow", prompt("chevaux dans la neige"))
        assertEquals("a photo of two children", prompt("deux enfants"))
    }

    @Test
    fun `verbes à la forme en ing`() {
        assertEquals("a photo of children playing in the snow", prompt("enfants qui jouent dans la neige"))
        assertEquals("a photo of a dog swimming in the lake", prompt("chien qui nage dans le lac"))
        assertEquals("a photo of a child riding a bike", prompt("enfant qui fait du vélo"))
    }

    @Test
    fun `de et articles contractés selon le contexte`() {
        assertEquals("a photo of a glass of wine", prompt("verre de vin"))
        assertEquals("a photo of bread", prompt("du pain"))
        assertEquals("a photo of dogs", prompt("des chiens"))
        assertEquals("a photo by the sea", prompt("au bord de la mer"))
        assertEquals("a photo of a dog in the sun", prompt("chien au soleil"))
    }

    @Test
    fun `noms indénombrables sans article`() {
        assertEquals("a photo of snow", prompt("neige"))
        assertEquals("a photo of christmas", prompt("Noël"))
        assertEquals("a photo of skiing", prompt("ski"))
    }

    @Test
    fun `étiquettes ML Kit en complément du dictionnaire`() {
        assertEquals("a photo of a surfboard", prompt("planche de surf"))
        assertEquals("a photo of a lighthouse", prompt("phare"))
    }

    @Test
    fun `requête anglaise gardée telle quelle`() {
        assertEquals("a photo of a dog on the beach", prompt("dog on the beach"))
        assertEquals("a photo of dogs playing in the park", prompt("dogs playing in the park"))
        assertEquals("a photo of a red car", prompt("red car"))
    }

    @Test
    fun `dates et types retirés, noms propres conservés`() {
        assertEquals("a photo of a dog on the beach", prompt("photos de chien sur la plage en 2023"))
        assertEquals("a photo of pikachu", prompt("pikachu"))
    }

    @Test
    fun `rien à chercher par le sens`() {
        assertNull(prompt("img"))
        assertNull(prompt("2023"))
        assertNull(prompt("de la"))
        assertNull(prompt("vidéos juillet"))
    }

    @Test
    fun `mots connus et noms propres`() {
        assertTrue(QueryTranslator.isKnownWord("plage"))
        assertTrue(QueryTranslator.isKnownWord("chiens"))
        assertTrue(QueryTranslator.isKnownWord("beach"))
        assertTrue(QueryTranslator.isKnownWord("lac"))
        assertFalse(QueryTranslator.isKnownWord("biarritz"))
    }

    @Test
    fun `clés du dictionnaire normalisées`() {
        for (key in QueryTranslator.dictionary.keys) {
            assertEquals("clé « $key »", LabelDictionary.normalize(key), key)
        }
    }

    @Test
    fun `pluriels anglais réguliers`() {
        assertEquals("dogs", QueryTranslator.englishPlural("dog"))
        assertEquals("beaches", QueryTranslator.englishPlural("beach"))
        assertEquals("puppies", QueryTranslator.englishPlural("puppy"))
        assertEquals("birthday cakes", QueryTranslator.englishPlural("birthday cake"))
        assertEquals("boys", QueryTranslator.englishPlural("boy"))
    }
}
