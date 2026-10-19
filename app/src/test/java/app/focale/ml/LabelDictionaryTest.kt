package app.focale.ml

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelDictionaryTest {

    @Test
    fun `447 étiquettes uniques`() {
        assertEquals(447, LabelDictionary.entries.size)
        assertEquals(447, LabelDictionary.entries.map { it.en }.toSet().size)
    }

    @Test
    fun `normalisation sans accents ni ponctuation`() {
        assertEquals("arc en ciel", LabelDictionary.normalize("Arc-en-ciel"))
        assertEquals("noel", LabelDictionary.normalize("Noël"))
        assertEquals("coeur", LabelDictionary.normalize("Cœur"))
    }

    @Test
    fun `correspondances bilingues, préfixes et pluriels`() {
        assertTrue("Sunset" in LabelDictionary.match("coucher"))
        assertTrue("Sunset" in LabelDictionary.match("sunset"))
        assertTrue("Mountain" in LabelDictionary.match("montagnes"))
        assertTrue("Horse" in LabelDictionary.match("chevaux"))
        assertTrue("Christmas" in LabelDictionary.match("noël"))
    }

    @Test
    fun `catégories englobantes`() {
        val animals = LabelDictionary.match("animaux")
        assertTrue("Dog" in animals && "Cat" in animals && "Bird" in animals)
        assertTrue("Pizza" in LabelDictionary.match("nourriture"))
    }

    @Test
    fun `libellé affiché selon la langue`() {
        assertEquals("Plage", LabelDictionary.displayName("Beach", Locale.FRENCH))
        assertEquals("Beach", LabelDictionary.displayName("Beach", Locale.ENGLISH))
    }
}
