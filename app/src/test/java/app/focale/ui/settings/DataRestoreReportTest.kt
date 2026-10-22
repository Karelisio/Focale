package app.focale.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.focale.backup.DataCategory
import app.focale.backup.DataRestoreReport
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Bilan d'une restauration : il ne parle que des catégories choisies. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataRestoreReportTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun lines(report: DataRestoreReport): List<String> = DataBackupViewModel.reportLines(report).map { it.resolve(context) }

    private val full = DataRestoreReport(
        media = 11, matched = 10, favorites = 2, albums = 2, people = 3, faces = 9, hiddenFolders = 2, searches = 2,
        settings = true, dates = 1,
    )

    @Test
    fun `restauration complète`() {
        assertEquals(
            listOf(
                "10 of 11 photos and videos found on this phone",
                "Restored: 2 favorites · 2 albums · 3 people · 2 hidden folders · 2 searches · 1 original date · settings",
                "1 photo or video isn’t on this phone: what concerns it wasn’t restored. Restore the backup again once it’s copied.",
            ),
            lines(full),
        )
    }

    @Test
    fun `favoris seuls`() {
        val report = DataRestoreReport(
            media = 3, matched = 2, favorites = 2, albums = 0, people = 0, faces = 0, hiddenFolders = 0, searches = 0,
            settings = null, categories = setOf(DataCategory.FAVORITES),
        )
        assertEquals(
            listOf(
                "2 of 3 photos and videos found on this phone",
                "Restored: 2 favorites",
                "1 photo or video isn’t on this phone: what concerns it wasn’t restored. Restore the backup again once it’s copied.",
            ),
            lines(report),
        )
    }

    @Test
    fun `réglages et historique, sans médias à retrouver`() {
        val report = full.copy(media = 0, matched = 0, categories = setOf(DataCategory.SETTINGS, DataCategory.SEARCH_HISTORY))
        assertEquals(listOf("Restored: 2 searches · settings"), lines(report))
        assertEquals(
            listOf("Nothing was restored", "The settings couldn’t be restored."),
            lines(report.copy(searches = 0, settings = false)),
        )
    }

    @Test
    fun `visages sans personne de la liste, dossiers déjà masqués`() {
        val report = full.copy(people = 0, faces = 4, hiddenFolders = 0, categories = setOf(DataCategory.PEOPLE, DataCategory.HIDDEN_FOLDERS))
        assertEquals(
            listOf(
                "10 of 11 photos and videos found on this phone",
                "Restored: 4 faces",
                "1 photo or video isn’t on this phone: what concerns it wasn’t restored. Restore the backup again once it’s copied.",
            ),
            lines(report),
        )
    }
}
