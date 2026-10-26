package app.focale.ml.text

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextRecognitionStepTest {

    @Test
    fun `le texte lu est enregistré avec sa clé, sa version et la date du média`() = runTest {
        val dao = FakeTextDao()
        val step = TextRecognitionStep(dao, FakeTextReader(mapOf(1L to "Code Wi-Fi\nMOT DE PASSE : Été2024")))

        step.process(listOf(photo(1, modified = 5_000L)))

        val row = dao.rows.getValue(1L)
        assertEquals("Code Wi-Fi\nMOT DE PASSE : Été2024", row.text)
        // Forme compacte de « Wi-Fi », puis lettres et chiffres de « Été2024 » séparés.
        assertEquals("code wi fi mot de passe ete2024 wifi ete 2024", row.textKey)
        assertEquals(TextRecognitionStep.VERSION, row.version)
        assertEquals(5_000L, row.analyzedModified)
    }

    @Test
    fun `une photo sans texte ou illisible est marquée comme lue`() = runTest {
        val dao = FakeTextDao()
        val step = TextRecognitionStep(dao, FakeTextReader(mapOf(1L to "", 2L to null, 3L to "Menu")))

        step.process(listOf(photo(1), photo(2), photo(3)))

        assertEquals(setOf(1L, 2L, 3L), dao.rows.keys)
        assertEquals("", dao.rows.getValue(1L).text)
        assertEquals("", dao.rows.getValue(2L).textKey)
        assertEquals("menu", dao.rows.getValue(3L).textKey)
    }

    @Test
    fun `un échec isolé est marqué comme lu sans bloquer le lot`() = runTest {
        val dao = FakeTextDao()
        val step = TextRecognitionStep(dao, FakeTextReader(mapOf(1L to "Facture", 2L to IllegalStateException("échec"))))

        step.process(listOf(photo(1), photo(2)))

        assertEquals("facture", dao.rows.getValue(1L).textKey)
        assertEquals("", dao.rows.getValue(2L).text)
    }

    @Test
    fun `un modèle indisponible interrompt l'étape sans rien enregistrer`() = runTest {
        val dao = FakeTextDao()
        val failure = IllegalStateException("Failed to load text module")
        val step = TextRecognitionStep(dao, FakeTextReader(mapOf(1L to failure, 2L to null, 3L to IOException("x"))))

        val error = runCatching { step.process(listOf(photo(1), photo(2), photo(3))) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(0, dao.upserts)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `release libère le lecteur`() {
        val reader = FakeTextReader(emptyMap())
        TextRecognitionStep(FakeTextDao(), reader).release()
        assertEquals(1, reader.released)
    }

    @Test
    fun `une ligne est à jour tant que la photo n'a pas changé`() {
        val row = TextRecognitionStep.entity(photo(7, modified = 10L), "Menu")
        assertTrue(TextRecognitionStep.isCurrent(row, photo(7, modified = 10L)))
        assertTrue(!TextRecognitionStep.isCurrent(row, photo(7, modified = 11L)))
        assertTrue(!TextRecognitionStep.isCurrent(row.copy(version = 0), photo(7, modified = 10L)))
    }
}
