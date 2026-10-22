package app.focale.vault

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.focale.core.settings.SettingsRepository
import app.focale.core.settings.VaultAlbumOrder
import app.focale.core.settings.VaultAlbumSort
import app.focale.core.settings.VaultItemOrder
import app.focale.core.settings.VaultSort
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Tri du coffre : ordre des éléments et des albums, choix mémorisé dans les réglages. */
class VaultSortTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var defaultLocale: Locale

    /** Langue du système autre que celle de l'application : le tri suit celle de l'application. */
    @Before
    fun setUp() {
        defaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("sv-SE"))
    }

    @After
    fun tearDown() {
        Locale.setDefault(defaultLocale)
        scope.cancel()
    }

    private val items = listOf(
        item(1, "IMG_0002.jpg", taken = 3_000, added = 10, size = 500),
        item(2, "éclipse.png", taken = 1_000, added = 30, size = 2_000),
        item(3, "Été.jpg", taken = 2_000, added = 20, size = 100),
        item(4, "dossier.pdf.jpg", taken = 2_000, added = 40, size = 900),
    )

    @Test
    fun `éléments par date de prise de vue, d'ajout au coffre, nom ou taille, dans les deux sens`() {
        assertEquals(listOf(1L, 4L, 3L, 2L), sorted(VaultSort()))
        assertEquals(listOf(2L, 4L, 3L, 1L), sorted(VaultSort(VaultItemOrder.DATE_TAKEN, ascending = true)))
        assertEquals(listOf(4L, 2L, 3L, 1L), sorted(VaultSort(VaultItemOrder.DATE_ADDED)))
        assertEquals(listOf(1L, 3L, 2L, 4L), sorted(VaultSort(VaultItemOrder.DATE_ADDED, ascending = true)))
        // Ni casse ni accents : « éclipse » entre « dossier » et « Été ».
        assertEquals(listOf(4L, 2L, 3L, 1L), sorted(VaultSort(VaultItemOrder.NAME, ascending = true)))
        assertEquals(listOf(2L, 4L, 1L, 3L), sorted(VaultSort(VaultItemOrder.SIZE)))
    }

    @Test
    fun `albums par nom, date de création ou nombre d'éléments`() {
        val albums = listOf(
            album(1, "Vacances", count = 12, created = 300),
            album(2, "écoles", count = 40, created = 100),
            album(3, "Papiers", count = 12, created = 200),
        )
        fun sorted(sort: VaultAlbumSort) = albums.sortedWith(sort.comparator(Locale.FRENCH)).map { it.id }

        assertEquals(listOf(1L, 3L, 2L), sorted(VaultAlbumSort()))
        assertEquals(listOf(2L, 3L, 1L), sorted(VaultAlbumSort(VaultAlbumOrder.NAME, ascending = true)))
        // À égalité de nombre, par nom.
        assertEquals(listOf(2L, 3L, 1L), sorted(VaultAlbumSort(VaultAlbumOrder.COUNT)))
        assertEquals(listOf(3L, 1L, 2L), sorted(VaultAlbumSort(VaultAlbumOrder.COUNT, ascending = true)))
    }

    @Test
    fun `ordre alphabétique de la langue de l'application, pas de celle du système`() {
        val albums = listOf(album(1, "Zurich", count = 1, created = 0), album(2, "Åland", count = 1, created = 0))
        // Système en suédois (« Å » après « Z ») ; Focale en français : « Å » se range avec « A ».
        val byName = VaultAlbumSort(VaultAlbumOrder.NAME, ascending = true)
        assertEquals(listOf(2L, 1L), albums.sortedWith(byName.comparator(Locale.FRENCH)).map { it.id })
        assertEquals(listOf(1L, 2L), albums.sortedWith(byName.comparator(Locale.getDefault())).map { it.id })
    }

    @Test
    fun `le choix est mémorisé`() = runTest {
        val file = File(folder.root, "settings.preferences_pb")
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file }, scope)
        settings.setVaultSort(VaultSort(VaultItemOrder.SIZE, ascending = true))
        settings.setVaultAlbumSort(VaultAlbumSort(VaultAlbumOrder.COUNT))

        val read = settings.settings.first()
        assertEquals(VaultSort(VaultItemOrder.SIZE, ascending = true), read.vaultSort)
        assertEquals(VaultAlbumSort(VaultAlbumOrder.COUNT, ascending = false), read.vaultAlbumSort)
    }

    private fun sorted(sort: VaultSort) = items.sortedWith(sort.comparator(Locale.FRENCH)).map { it.id }

    private fun item(id: Long, name: String, taken: Long, added: Long, size: Long) = VaultItem(
        id = id, displayName = name, mimeType = "image/jpeg", isVideo = false, originalPath = "DCIM/Camera/",
        dateTaken = taken, size = size, durationMs = 0, fileName = "$id.bin", thumbFileName = null, addedAt = added,
    )

    private fun album(id: Long, name: String, count: Int, created: Long) =
        VaultAlbum(id = id, name = name, count = count, coverThumbFileName = null, sourcePath = null, createdAt = created)
}
