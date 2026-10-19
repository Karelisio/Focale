package app.focale.data

import app.focale.ml.text.FakeTextDao
import app.focale.ml.text.FakeTextReader
import app.focale.ml.text.TextRecognitionStep
import app.focale.ml.text.photo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoTextRepositoryTest {

    @Test
    fun `le texte de l'analyse est rendu sans relire la photo`() = runTest {
        val dao = FakeTextDao()
        dao.upsert(listOf(TextRecognitionStep.entity(photo(1, modified = 10L), "Menu du jour")))
        val reader = FakeTextReader(mapOf(1L to "autre"))

        assertEquals("Menu du jour", PhotoTextRepository(dao, reader).textOf(photo(1, modified = 10L)))
        assertTrue(reader.reads.isEmpty())
    }

    @Test
    fun `une photo pas encore analysée est lue à la demande et enregistrée`() = runTest {
        val dao = FakeTextDao()
        val reader = FakeTextReader(mapOf(1L to "Wi-Fi : Maison"))

        assertEquals("Wi-Fi : Maison", PhotoTextRepository(dao, reader).textOf(photo(1)))
        assertEquals(listOf(1L), reader.reads)
        assertEquals("wi fi maison wifi", dao.rows.getValue(1L).textKey)
    }

    @Test
    fun `une photo modifiée depuis l'analyse est relue`() = runTest {
        val dao = FakeTextDao()
        dao.upsert(listOf(TextRecognitionStep.entity(photo(1, modified = 10L), "avant")))
        val reader = FakeTextReader(mapOf(1L to "après"))

        assertEquals("après", PhotoTextRepository(dao, reader).textOf(photo(1, modified = 20L)))
        assertEquals(20L, dao.rows.getValue(1L).analyzedModified)
    }

    @Test
    fun `une image illisible n'est pas enregistrée`() = runTest {
        val dao = FakeTextDao()
        assertNull(PhotoTextRepository(dao, FakeTextReader(mapOf(1L to null))).textOf(photo(1)))
        assertTrue(dao.rows.isEmpty())
    }
}
