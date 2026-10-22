package app.focale.backup

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.focale.core.database.AlbumEntity
import app.focale.core.database.AlbumMediaEntity
import app.focale.core.database.FaceEntity
import app.focale.core.database.FaceScanEntity
import app.focale.core.database.FavoriteEntity
import app.focale.core.database.FocaleDatabase
import app.focale.core.database.MediaDateEntity
import app.focale.core.database.MediaEntity
import app.focale.core.database.PersonEntity
import app.focale.core.database.SearchHistoryEntity
import app.focale.core.settings.SettingsRepository
import app.focale.core.settings.ThemeMode
import app.focale.data.SearchChip
import app.focale.data.SearchFilters
import app.focale.people.FaceAnalysisStep
import app.focale.people.PeopleEditions
import app.focale.perf.SyntheticLibrary
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sauvegarde des données de bout en bout : un ancien téléphone et un nouveau, chacun avec sa base
 * Room (en mémoire) et ses réglages (DataStore), des médias aux `_ID` différents, une archive dans
 * un fichier comme celui du sélecteur système.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataBackupRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val random = Random(2026)
    private val devices = ArrayList<Device>()
    private lateinit var archiveFile: File
    private lateinit var archive: Uri

    @Before
    fun setUp() {
        // Document vide, comme celui que crée le sélecteur système avant de rendre la main.
        archiveFile = folder.newFile("focale-2026-10-22.focale")
        archive = Uri.fromFile(archiveFile)
    }

    @After
    fun tearDown() {
        devices.forEach(Device::close)
    }

    @Test
    fun `sauvegarde puis restauration sur un nouveau téléphone, aux identifiants différents`() = runTest {
        val phone = OldPhone()
        val exported = phone.device.backups.export(archive, password())
        assertEquals(DataExportResult.Success(favorites = 3, albums = 2, people = 3, hiddenFolders = 2), exported)
        assertTrue(phone.device.syncs > 0)

        val newPhone = NewPhone()
        val inspected = newPhone.device.inspect()
        val summary = inspected.summary
        assertEquals(phone.device.now, summary.createdAt)
        assertEquals("1.9.0", summary.appVersion)
        assertEquals(listOf(3, 2, 3, 2), listOf(summary.favorites, summary.albums, summary.people, summary.hiddenFolders))
        assertEquals(11, summary.media)
        assertEquals(10, summary.faces)
        assertEquals(2, summary.searches)
        assertEquals(1, summary.dates)
        assertTrue(summary.settings)
        assertEquals("tout est proposé, et coché d'office", DataCategory.ALL, summary.categories)
        // « Inconnu », nommé sur le nouveau téléphone : ses visages des photos retrouvées seront remplacés.
        assertEquals(1, summary.existingPeople)

        val syncsBefore = newPhone.device.syncs
        val report = inspected.use { (newPhone.device.backups.restore(it) as DataRestoreResult.Success).report }
        assertTrue("synchronisation avant l'appariement", newPhone.device.syncs > syncsBefore)
        assertEquals(
            DataRestoreReport(media = 11, matched = 10, favorites = 2, albums = 2, people = 3, faces = 9, hiddenFolders = 2, searches = 2, settings = true, dates = 1),
            report,
        )
        assertEquals(1, report.missing)

        val db = newPhone.device.database
        // Favoris : réunion ; celui du média introuvable n'est pas restauré.
        assertEquals(
            setOf("IMG_9000.jpg", "IMG_0001.jpg", "IMG_0100.jpg"),
            db.query("SELECT m.display_name FROM favorite f JOIN media m ON m.id = f.media_id").toSet(),
        )
        // Albums : « vacances » du téléphone et « Vacances » de la sauvegarde réunis, avec la couverture
        // de la sauvegarde (le sien n'en avait pas) ; « Vide » recréé.
        assertEquals(
            listOf("Vide|∅", "vacances|IMG_0002.jpg"),
            db.query("SELECT a.name || '|' || COALESCE((SELECT display_name FROM media WHERE id = a.cover_media_id), '∅') FROM album a ORDER BY a.name"),
        )
        assertEquals(
            setOf("IMG_9000.jpg", "IMG_0001.jpg", "IMG_0002.jpg"),
            db.query("SELECT m.display_name FROM album_media am JOIN media m ON m.id = am.media_id").toSet(),
        )

        // Personnes recréées, avec nom, épinglage, masquage et vignette.
        val faces = newPhone.device.database.faceDao()
        val people = db.query("SELECT COALESCE(name, '∅') || '|' || hidden || '|' || pinned FROM person ORDER BY id")
        assertEquals(listOf("Inconnu|0|0", "Alice|0|1", "∅|0|0", "Bob|1|0"), people)
        val alice = db.query("SELECT id FROM person WHERE name = 'Alice'").single().toLong()
        assertEquals(
            "IMG_0002.jpg",
            db.query("SELECT m.display_name FROM person p JOIN face f ON f.id = p.cover_face_id JOIN media m ON m.id = f.media_id WHERE p.id = $alice").single(),
        )
        // Visages des photos retrouvées : ceux de la sauvegarde, à la place de ceux du téléphone.
        assertEquals(
            listOf("Alice|0", "∅|1"),
            db.query(
                """SELECT COALESCE(p.name, '∅') || '|' || f.manual FROM face f JOIN media m ON m.id = f.media_id
                LEFT JOIN person p ON p.id = f.person_id WHERE m.display_name = 'IMG_0001.jpg' ORDER BY f.box_left""",
            ),
        )
        val restoredFace = faces.facesOfMedia(listOf(newPhone.id("IMG_0002.jpg"))).single()
        assertArrayEquals(phone.embeddings.getValue("IMG_0002.jpg"), restoredFace.embedding)
        assertEquals(0.22f, restoredFace.boxLeft)
        // « Inconnu » garde son visage de la photo propre au nouveau téléphone.
        assertEquals(listOf("IMG_9000.jpg"), db.query("SELECT m.display_name FROM face f JOIN person p ON p.id = f.person_id JOIN media m ON m.id = f.media_id WHERE p.name = 'Inconnu'"))
        // Refus de fusion reporté sur les personnes recréées.
        assertEquals(listOf("Alice|∅"), db.query("SELECT COALESCE(l.name, '∅') || '|' || COALESCE(h.name, '∅') FROM person_refusal r JOIN person l ON l.id = r.person_low JOIN person h ON h.id = r.person_high"))

        // L'analyse des visages n'est pas refaite sur les photos retrouvées, sauf celle modifiée depuis.
        val pending = faces.pending(FaceAnalysisStep.VERSION, 100).map { it.displayName }.toSet()
        assertEquals(setOf("IMG_9000.jpg", "IMG_0006.jpg"), pending)

        // Date d'origine restaurée et date de tri suivie (MediaStore n'en a pas).
        val dated = newPhone.id("IMG_0008.jpg")
        assertEquals(DATED, newPhone.device.database.mediaDao().get(dated)?.dateTaken)
        assertEquals(listOf(MediaDateEntity(dated, DATED)), newPhone.device.database.mediaDao().originalDates())

        // Historique : la recherche de la personne désigne Alice recréée.
        val history = db.query("SELECT query FROM search_history ORDER BY searched_at DESC")
        assertEquals("plage", history.first())
        val chip = SearchFilters.fromHistory(history[1])!!.chips.single() as SearchChip.Person
        assertEquals(alice, chip.id)
        assertEquals("Alice", chip.name)

        // Réglages remplacés (liste blanche) : un réglage par défaut sur l'ancien téléphone y revient.
        val settings = newPhone.device.settings.settings.first()
        assertEquals(ThemeMode.DARK, settings.themeMode)
        assertEquals(3, settings.gridColumns)
        assertTrue(settings.amoled)
        assertTrue("lecture automatique revenue à sa valeur par défaut", settings.autoplayVideos)
        // Dossiers masqués : réunion, rapprochés par leur chemin.
        assertEquals(setOf(newPhone.whatsAppBucket, NEW_HIDDEN, OLD_EMPTY_HIDDEN), settings.hiddenFolders)
    }

    @Test
    fun `restaurer deux fois donne le même résultat, et restaurer plus tard ajoute les médias arrivés`() = runTest {
        OldPhone().device.backups.export(archive, password())
        val newPhone = NewPhone()
        newPhone.device.restore()
        val first = newPhone.device.snapshot()
        newPhone.device.restore()
        assertEquals(first, newPhone.device.snapshot())

        // La photo manquante arrive sur le nouveau téléphone : sa place est retrouvée à la restauration suivante.
        newPhone.device.media(7_777, "DCIM/Camera/", "IMG_0009.jpg", size = 9_009, taken = T + 9 * DAY)
        val report = newPhone.device.restore()
        assertEquals(0, report.missing)
        val db = newPhone.device.database
        assertTrue("IMG_0009.jpg" in db.query("SELECT m.display_name FROM favorite f JOIN media m ON m.id = f.media_id"))
        assertTrue("IMG_0009.jpg" in db.query("SELECT m.display_name FROM album_media am JOIN media m ON m.id = am.media_id"))
        assertEquals(listOf("Bob"), db.query("SELECT p.name FROM face f JOIN person p ON p.id = f.person_id WHERE f.media_id = 7777"))
        // Et rien d'autre ne change, une fois de plus.
        val second = newPhone.device.snapshot()
        newPhone.device.restore()
        assertEquals(second, newPhone.device.snapshot())
    }

    @Test
    fun `chaque catégorie restaurée seule ne change qu'elle, comme une restauration complète`() = runTest {
        OldPhone().device.backups.export(archive, password())
        val full = NewPhone("complet").apply { device.restore() }.device.sections()
        for (category in DataCategory.entries) {
            val newPhone = NewPhone("seul-${category.name}")
            val before = newPhone.device.sections()
            assertNotEquals("$category : rien à restaurer, le test ne prouverait rien", before.getValue(category), full.getValue(category))
            val syncs = newPhone.device.syncs
            val report = newPhone.device.restore(setOf(category))
            val after = newPhone.device.sections()
            for (other in DataCategory.entries) {
                val expected = if (other == category) full.getValue(other) else before.getValue(other)
                assertEquals("$category seule : $other", expected, after.getValue(other))
            }
            assertEquals(setOf(category), report.categories)
            // Ni réglages ni historique ne désignent de médias : pas d'index à synchroniser.
            val needsLibrary = category.needsMedia || category == DataCategory.HIDDEN_FOLDERS
            assertEquals("$category : synchronisation", needsLibrary, newPhone.device.syncs > syncs)
        }
    }

    @Test
    fun `le bilan d'une catégorie seule ne compte qu'elle et ses médias`() = runTest {
        OldPhone().device.backups.export(archive, password())
        // Favoris : 3 photos désignées, dont IMG_0009 absente du nouveau téléphone.
        assertEquals(
            DataRestoreReport(
                media = 3, matched = 2, favorites = 2, albums = 0, people = 0, faces = 0, hiddenFolders = 0, searches = 0,
                settings = null, dates = 0, categories = setOf(DataCategory.FAVORITES),
            ),
            NewPhone("favoris").device.restore(setOf(DataCategory.FAVORITES)),
        )
        assertEquals(
            DataRestoreReport(
                media = 0, matched = 0, favorites = 0, albums = 0, people = 0, faces = 0, hiddenFolders = 0, searches = 0,
                settings = true, dates = 0, categories = setOf(DataCategory.SETTINGS),
            ),
            NewPhone("réglages").device.restore(setOf(DataCategory.SETTINGS)),
        )
        val people = NewPhone("personnes").device.restore(setOf(DataCategory.PEOPLE))
        assertEquals(listOf(3, 9, 0), listOf(people.people, people.faces, people.favorites))
        // Toutes les photos ont été analysées : toutes concernées.
        assertEquals(listOf(11, 10), listOf(people.media, people.matched))
        assertEquals(null, people.settings)
    }

    @Test
    fun `des catégories combinées, restaurées deux fois, donnent le même résultat`() = runTest {
        OldPhone().device.backups.export(archive, password())
        val full = NewPhone("complet").apply { device.restore() }.device.sections()
        val combinations = listOf(
            setOf(DataCategory.FAVORITES, DataCategory.ALBUMS),
            setOf(DataCategory.PEOPLE, DataCategory.SEARCH_HISTORY),
            setOf(DataCategory.SETTINGS, DataCategory.HIDDEN_FOLDERS, DataCategory.ORIGINAL_DATES),
            DataCategory.ALL - DataCategory.PEOPLE,
        )
        for ((index, combination) in combinations.withIndex()) {
            val newPhone = NewPhone("combinaison-$index")
            val before = newPhone.device.sections()
            newPhone.device.restore(combination)
            val first = newPhone.device.sections()
            for (category in DataCategory.entries) {
                val expected = if (category in combination) full.getValue(category) else before.getValue(category)
                assertEquals("$combination : $category", expected, first.getValue(category))
            }
            newPhone.device.restore(combination)
            assertEquals("$combination restaurée deux fois", first, newPhone.device.sections())
        }
    }

    @Test
    fun `une restauration partielle annulée ne change rien`() = runTest {
        OldPhone().device.backups.export(archive, password())
        val newPhone = NewPhone()
        val before = newPhone.device.snapshot()
        var read = 0
        val result = newPhone.device.inspect().use {
            newPhone.device.backups.restore(it, setOf(DataCategory.FAVORITES, DataCategory.SETTINGS), cancelled = { ++read > 20 })
        }
        assertEquals(DataRestoreResult.Cancelled, result)
        assertEquals(before, newPhone.device.snapshot())
    }

    @Test
    fun `restaurer sur le téléphone sauvegardé ne change rien`() = runTest {
        val phone = OldPhone()
        val before = phone.device.snapshot()
        phone.device.backups.export(archive, password())
        phone.device.restore()
        assertEquals(before, phone.device.snapshot())
        val db = phone.device.database
        // Alice ne garde pas de double : son visage de la corbeille, détaché, sera repris par le regroupement.
        assertEquals(listOf("Alice"), db.query("SELECT name FROM person WHERE name = 'Alice'"))
        assertEquals(listOf("∅|0"), db.query("SELECT COALESCE(person_id, '∅') || '|' || manual FROM face WHERE media_id = 1050"))
        // Son favori, lui, reste en place.
        assertEquals(listOf("1050"), db.query("SELECT media_id FROM favorite WHERE media_id = 1050"))
    }

    @Test
    fun `un mot de passe faux, un fichier endommagé, tronqué ou étranger sont refusés sans rien changer`() = runTest {
        OldPhone().device.backups.export(archive, password())
        val newPhone = NewPhone()
        val before = newPhone.device.snapshot()
        assertEquals(DataInspectResult.WrongPassword, newPhone.device.backups.inspect(archive, "pas le bon mot de passe".toCharArray()))

        val bytes = archiveFile.readBytes()
        suspend fun inspectBytes(content: ByteArray): DataInspectResult {
            val file = folder.newFile()
            file.writeBytes(content)
            return newPhone.device.backups.inspect(Uri.fromFile(file), password())
        }
        assertEquals(DataInspectResult.Failed(DataBackupFailure.CORRUPTED), inspectBytes(bytes.copyOf(bytes.size - 40)))
        assertEquals(DataInspectResult.Failed(DataBackupFailure.CORRUPTED), inspectBytes(bytes.copyOf().also { it[bytes.size / 2] = (it[bytes.size / 2] + 1).toByte() }))
        assertEquals(DataInspectResult.Failed(DataBackupFailure.NOT_AN_ARCHIVE), inspectBytes("Bonjour".toByteArray()))
        val vault = requireNotNull(javaClass.getResourceAsStream("/vault/sauvegarde-sans-albums.fvault")).use { it.readBytes() }
        assertEquals(DataInspectResult.Failed(DataBackupFailure.VAULT_BACKUP), inspectBytes(vault))
        assertEquals(DataInspectResult.Failed(DataBackupFailure.UNREADABLE), newPhone.device.backups.inspect(Uri.fromFile(File(folder.root, "absent.focale")), password()))

        // Vérifiée, puis abîmée avant la restauration : la transaction n'est jamais validée.
        newPhone.device.inspect().use { inspected ->
            archiveFile.writeBytes(bytes.copyOf(bytes.size - 1))
            assertEquals(DataRestoreResult.Failed(DataBackupFailure.CORRUPTED), newPhone.device.backups.restore(inspected))
        }
        assertEquals(before, newPhone.device.snapshot())
    }

    @Test
    fun `une restauration annulée ne change rien`() = runTest {
        OldPhone().device.backups.export(archive, password())
        val newPhone = NewPhone()
        val before = newPhone.device.snapshot()
        var read = 0
        val result = newPhone.device.inspect().use { newPhone.device.backups.restore(it, cancelled = { ++read > 3 }) }
        assertEquals(DataRestoreResult.Cancelled, result)
        assertEquals(before, newPhone.device.snapshot())
    }

    @Test
    fun `seuls les réglages de la liste blanche sont sauvegardés, puis remplacés à la restauration`() = runTest {
        val phone = Device("ancien")
        phone.settings.setThemeMode(ThemeMode.LIGHT)
        phone.settings.setSlideshowInterval(10)
        phone.settings.setAnalysisOnlyWhileCharging(true)
        phone.backups.export(archive, password())
        val records = readRecords(archiveFile.readBytes())
        val saved = records.filterIsInstance<SettingsRecord>().single()
        assertEquals(
            mapOf("theme_mode" to JsonPrimitive("LIGHT"), "slideshow_interval" to JsonPrimitive(10), "analysis_only_charging" to JsonPrimitive(true)),
            saved.values,
        )
        assertEquals(SettingsRepository.BACKED_UP.map { it.key.name }, saved.keys)
        assertFalse("hidden_folders" in saved.keys)

        val newPhone = Device("nouveau")
        newPhone.settings.setThemeMode(ThemeMode.DARK)
        newPhone.settings.setGridColumns(6)
        newPhone.restore()
        val restored = newPhone.settings.settings.first()
        assertEquals(ThemeMode.LIGHT, restored.themeMode)
        assertEquals(10, restored.slideshowIntervalSeconds)
        assertTrue(restored.analysisOnlyWhileCharging)
        assertEquals("colonnes revenues à leur valeur par défaut", 4, restored.gridColumns)
    }

    @Test
    fun `une sauvegarde d'une version antérieure de l'analyse se restaure, ses photos seront relues`() = runTest {
        val newPhone = NewPhone()
        writeArchive(
            HeaderRecord(createdAt = T, appVersion = "1.7.0", faceVersion = 0),
            MediaRecord("DCIM/Camera/", "IMG_0001.jpg", 1_001, T + DAY),
            PersonRecord(id = 1, name = "Alice", createdAt = T, photos = 1),
            FaceScanRecord(media = 0, version = 0, faces = 1),
            FaceRecord(1, 0, person = 1, left = 0.1f, top = 0.1f, right = 0.2f, bottom = 0.2f, embedding = random.nextBytes(512), quality = 0.9f, manual = true),
        )
        val report = newPhone.device.restore()
        assertEquals(1, report.matched)
        val photo = newPhone.id("IMG_0001.jpg")
        val face = newPhone.device.database.faceDao().facesOfMedia(listOf(photo)).single()
        assertTrue(face.manual)
        assertEquals("Alice", newPhone.device.database.faceDao().person(requireNotNull(face.personId))?.name)
        // Version antérieure : la photo est relue, ses visages retrouvés au même endroit gardent leur personne.
        assertTrue(photo in newPhone.device.database.faceDao().pending(FaceAnalysisStep.VERSION, 100).map { it.id })
    }

    @Test
    fun `une sauvegarde d'une version ultérieure de l'analyse garde les personnes, pas les empreintes`() = runTest {
        val newPhone = NewPhone()
        writeArchive(
            HeaderRecord(createdAt = T, appVersion = "9.0.0", faceVersion = FaceAnalysisStep.VERSION + 1),
            MediaRecord("DCIM/Camera/", "IMG_0001.jpg", 1_001, T + DAY),
            PersonRecord(id = 1, name = "Alice", createdAt = T, photos = 1),
            FaceScanRecord(media = 0, version = FaceAnalysisStep.VERSION + 1, faces = 1),
            // Empreinte d'un autre modèle (256 flottants) : inutilisable ici.
            FaceRecord(1, 0, person = 1, left = 0.1f, top = 0.1f, right = 0.2f, bottom = 0.2f, embedding = random.nextBytes(1_024), quality = 0.9f),
        )
        newPhone.device.restore()
        val photo = newPhone.id("IMG_0001.jpg")
        val face = newPhone.device.database.faceDao().facesOfMedia(listOf(photo)).single()
        assertNotNull(face.personId)
        assertArrayEquals(ByteArray(512), face.embedding)
        assertTrue(photo in newPhone.device.database.faceDao().pending(FaceAnalysisStep.VERSION, 100).map { it.id })
    }

    // ── Données de test ─────────────────────────────────────────────────────────────

    /** Ancien téléphone : bibliothèque organisée, à sauvegarder. */
    private inner class OldPhone {
        val device = Device("ancien")
        val embeddings = HashMap<String, ByteArray>()
        private val ids = HashMap<String, Long>()

        init {
            runTestBlocking {
                var id = 1_001L
                for ((path, name, size) in OLD_MEDIA) {
                    // IMG_0008 : sans date de prise de vue pour MediaStore, triée à sa date d'origine.
                    device.media(id, path, name, size, taken = if (name == "IMG_0008.jpg") DATED else T + (id - 1_000) * DAY)
                    ids[name] = id++
                }
                // Dans la corbeille : ni favori, ni visage sauvegardé.
                device.media(1_050, "DCIM/Camera/", ".trashed-1760000000-IMG_0042.jpg", 42, taken = T, trashed = true)
                val db = device.database
                db.favoriteDao().insert(listOf(fav("IMG_0001.jpg"), fav("IMG_0100.jpg"), fav("IMG_0009.jpg"), FavoriteEntity(1_050, T)))
                val trip = db.albumDao().insert(AlbumEntity(name = "Vacances", createdAt = T, coverMediaId = ids.getValue("IMG_0002.jpg")))
                db.albumDao().insert(AlbumEntity(name = "Vide", createdAt = T + 1))
                db.albumDao().addMedia(listOf("IMG_0001.jpg", "IMG_0002.jpg", "IMG_0009.jpg").map { AlbumMediaEntity(trip, ids.getValue(it), T + 5) })

                val faces = db.faceDao()
                val alice = faces.insertPerson(PersonEntity(name = "Alice", pinned = true, createdAt = T))
                val stranger = faces.insertPerson(PersonEntity(createdAt = T + 1))
                val bob = faces.insertPerson(PersonEntity(name = "Bob", hidden = true, createdAt = T + 2))
                val faceIds = faces.insertFaces(
                    listOf(
                        face("IMG_0001.jpg", alice, left = 0.11f),
                        // Visage retiré à la main d'une personne : reste libre, le regroupement n'y revient pas.
                        face("IMG_0001.jpg", null, left = 0.61f, manual = true),
                        face("IMG_0002.jpg", alice, left = 0.22f),
                        face("IMG_0003.jpg", alice, left = 0.33f),
                        face("IMG_0004.jpg", stranger, left = 0.44f),
                        face("IMG_0005.jpg", stranger, left = 0.55f),
                        face("IMG_0006.jpg", stranger, left = 0.66f),
                        face("IMG_0100.jpg", bob, left = 0.17f),
                        face("IMG_0009.jpg", bob, left = 0.19f),
                        face("photo.jpg", null, left = 0.5f),
                        face(".trashed-1760000000-IMG_0042.jpg", alice, left = 0.4f),
                    ),
                )
                faces.setCover(alice, faceIds[2])
                faces.refuse(minOf(alice, stranger), maxOf(alice, stranger))
                // Toutes les photos ont été analysées ; IMG_0006 modifiée depuis.
                faces.upsertScans(
                    ids.map { (name, mediaId) ->
                        val media = requireNotNull(db.mediaDao().get(mediaId))
                        FaceScanEntity(mediaId, if (name == "IMG_0006.jpg") 1 else media.dateModified, FaceAnalysisStep.VERSION, 1)
                    },
                )
                db.mediaDao().upsertOriginalDate(MediaDateEntity(ids.getValue("IMG_0008.jpg"), DATED))
                db.searchHistoryDao().upsert(SearchHistoryEntity("plage", T + 100))
                db.searchHistoryDao().upsert(SearchHistoryEntity(SearchFilters(chips = listOf(SearchChip.Person(alice, "Alice"))).historyKey, T + 50))
                device.settings.setThemeMode(ThemeMode.DARK)
                device.settings.setGridColumns(3)
                device.settings.setAmoled(true)
                device.settings.setFolderHidden(SyntheticLibrary.bucketId("Pictures/WhatsApp/"), true)
                device.settings.setFolderHidden(OLD_EMPTY_HIDDEN, true)
            }
        }

        private fun fav(name: String) = FavoriteEntity(ids.getValue(name), T + 7)

        private fun face(name: String, person: Long?, left: Float, manual: Boolean = false): FaceEntity {
            val embedding = random.nextBytes(512)
            embeddings[name] = embedding
            return FaceEntity(
                mediaId = if (name.startsWith(".trashed")) 1_050 else ids.getValue(name),
                personId = person,
                boxLeft = left,
                boxTop = 0.1f,
                boxRight = left + 0.1f,
                boxBottom = 0.3f,
                embedding = embedding,
                quality = 0.8f,
                manual = manual,
            )
        }
    }

    /**
     * Nouveau téléphone : les mêmes photos, copiées, sous d'autres identifiants ; une photo dans un
     * autre dossier, une absente, une propre à ce téléphone, déjà analysée et rangée.
     */
    private inner class NewPhone(name: String = "nouveau") {
        val device = Device(name)
        val whatsAppBucket = 777_000L

        init {
            runTestBlocking {
                var id = 5_001L
                for ((path, name, size) in OLD_MEDIA) {
                    if (name == "IMG_0009.jpg") continue
                    // Copiée sans sa date de fichier : seule la taille et la date de prise de vue suivent.
                    val folder = if (name == "IMG_0100.jpg") "Pictures/Restauré/" else path
                    val bucket = if (path == "Pictures/WhatsApp/") whatsAppBucket else SyntheticLibrary.bucketId(folder)
                    val oldId = 1_001L + OLD_MEDIA.indexOfFirst { it.second == name }
                    device.media(id++, folder, name, size, taken = T + (oldId - 1_000) * DAY, modified = T + 400 * DAY + id, bucket = bucket)
                }
                device.media(5_900, "DCIM/Camera/", "IMG_9000.jpg", 9_000, taken = T + 300 * DAY)
                val db = device.database
                db.favoriteDao().insert(listOf(FavoriteEntity(5_900, T + 301 * DAY)))
                val album = db.albumDao().insert(AlbumEntity(name = "vacances", createdAt = T + 300 * DAY))
                db.albumDao().addMedia(listOf(AlbumMediaEntity(album, 5_900, T + 302 * DAY)))
                val faces = db.faceDao()
                val known = faces.insertPerson(PersonEntity(name = "Inconnu", createdAt = T + 300 * DAY))
                faces.insertFaces(
                    listOf(
                        FaceEntity(mediaId = id("IMG_0001.jpg"), personId = known, boxLeft = 0.9f, boxTop = 0f, boxRight = 1f, boxBottom = 0.1f, embedding = ByteArray(512), quality = 0.5f),
                        FaceEntity(mediaId = 5_900, personId = known, boxLeft = 0.2f, boxTop = 0f, boxRight = 0.3f, boxBottom = 0.1f, embedding = ByteArray(512), quality = 0.5f),
                    ),
                )
                device.settings.setThemeMode(ThemeMode.LIGHT)
                device.settings.setGridColumns(6)
                device.settings.setAutoplayVideos(false)
                device.settings.setFolderHidden(NEW_HIDDEN, true)
            }
        }

        fun id(name: String): Long = runTestBlocking {
            device.database.query("SELECT id FROM media WHERE display_name = '$name'").single().toLong()
        }
    }

    /** Un téléphone : sa base, ses réglages, sa sauvegarde des données. */
    private inner class Device(name: String) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val database: FocaleDatabase = Room.inMemoryDatabaseBuilder(context, FocaleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val settings = SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "$name-settings.preferences_pb") },
            scope,
        )
        var now = T + 500 * DAY
        var syncs = 0
        val backups = DataBackupRepository(
            context.contentResolver, database, settings, PeopleEditions(), { emptyMap() }, { syncs++ }, Dispatchers.IO,
            iterations = 1_000, appVersion = "1.9.0", clock = { now },
        )

        init {
            devices += this
        }

        suspend fun media(
            id: Long,
            path: String,
            name: String,
            size: Long,
            taken: Long,
            modified: Long = taken,
            bucket: Long = SyntheticLibrary.bucketId(path),
            trashed: Boolean = false,
        ) {
            database.mediaDao().upsert(
                listOf(
                    MediaEntity(
                        id = id, mediaType = 1, mimeType = "image/jpeg", displayName = name, relativePath = path,
                        bucketId = bucket, bucketName = path.trimEnd('/').substringAfterLast('/'), dateTaken = taken,
                        dateAdded = taken, dateModified = modified, generation = 0, size = size, width = 4_000, height = 3_000,
                        orientation = 0, duration = 0, volumeName = "external_primary", isScreenshot = false, isGif = false,
                        isTrashed = trashed, dateExpires = 0,
                    ),
                ),
            )
        }

        suspend fun inspect(): InspectedBackup = (backups.inspect(archive, password()) as DataInspectResult.Ready).backup

        suspend fun restore(categories: Set<DataCategory> = DataCategory.ALL): DataRestoreReport =
            inspect().use { (backups.restore(it, categories) as DataRestoreResult.Success).report }

        /**
         * Contenu restauré par catégorie, sans les identifiants propres au téléphone (médias,
         * personnes, visages) : de quoi comparer deux restaurations. Les visages de la corbeille,
         * hors de la sauvegarde, n'y sont pas.
         */
        suspend fun sections(): Map<DataCategory, String> {
            fun dump(vararg queries: String): String = queries.joinToString("--\n") { sql -> database.query(sql).joinToString("") { "$it\n" } }
            val current = settings.settings.first()
            return mapOf(
                DataCategory.FAVORITES to dump("SELECT m.display_name || '|' || f.added_at FROM favorite f JOIN media m ON m.id = f.media_id ORDER BY 1"),
                DataCategory.ALBUMS to dump(
                    "SELECT a.name || '|' || a.created_at || '|' || COALESCE((SELECT display_name FROM media WHERE id = a.cover_media_id), '∅') FROM album a ORDER BY 1",
                    "SELECT a.name || '|' || m.display_name || '|' || am.added_at FROM album_media am JOIN album a ON a.id = am.album_id JOIN media m ON m.id = am.media_id ORDER BY 1",
                ),
                DataCategory.PEOPLE to dump(
                    """SELECT COALESCE(p.name, '∅') || '|' || p.hidden || '|' || p.pinned || '|' || p.created_at || '|' ||
                    COALESCE((SELECT m.display_name FROM face c JOIN media m ON m.id = c.media_id WHERE c.id = p.cover_face_id), '∅')
                    FROM person p ORDER BY 1""",
                    """SELECT m.display_name || '|' || COALESCE(p.name, '∅') || '|' || COALESCE(p.created_at, '∅') || '|' || f.box_left || '|' ||
                    f.box_top || '|' || f.box_right || '|' || f.box_bottom || '|' || f.manual || '|' || hex(f.embedding) || '|' || f.quality
                    FROM face f JOIN media m ON m.id = f.media_id LEFT JOIN person p ON p.id = f.person_id
                    WHERE m.is_trashed = 0 ORDER BY 1""",
                    // Passages de la détection : à jour ou non, plutôt que la date de modification vue.
                    """SELECT m.display_name || '|' || (s.analyzed_modified = m.date_modified) || '|' || s.version || '|' || s.face_count
                    FROM face_scan s JOIN media m ON m.id = s.media_id ORDER BY 1""",
                    "SELECT COALESCE(l.name, '∅') || '|' || COALESCE(h.name, '∅') FROM person_refusal r JOIN person l ON l.id = r.person_low JOIN person h ON h.id = r.person_high ORDER BY 1",
                ),
                DataCategory.ORIGINAL_DATES to dump("SELECT m.display_name || '|' || d.date_taken || '|' || m.date_taken FROM media_date d JOIN media m ON m.id = d.media_id ORDER BY 1"),
                // Recherches : leurs personnes désignées par leur nom, leur identifiant changeant à chaque restauration.
                DataCategory.SEARCH_HISTORY to database.query("SELECT query || '\u0001' || searched_at FROM search_history").map { row ->
                    val (query, at) = row.split('\u0001')
                    SearchHistoryMerge.canonical(query) + "|" + at
                }.sorted().joinToString("\n"),
                DataCategory.SETTINGS to current.copy(hiddenFolders = emptySet()).toString(),
                DataCategory.HIDDEN_FOLDERS to current.hiddenFolders.sorted().toString(),
            )
        }

        /** Tout le contenu restauré, en un texte (voir [sections]). */
        suspend fun snapshot(): String = sections().entries.joinToString("\n--\n") { (category, content) -> "$category\n$content" }

        fun close() {
            database.close()
            scope.cancel()
        }
    }

    /** Archive écrite directement, enregistrement par enregistrement (autre version de Focale). */
    private fun writeArchive(vararg records: DataRecord) {
        PasswordArchive.deriveKeys(ArchiveFormat.DATA, password(), 1_000).use { keys ->
            BackupDocuments.openOutput(context.contentResolver, archive).use { output ->
                DataArchive.write(output, keys).use { writer ->
                    records.forEach(writer::write)
                    writer.finish()
                }
            }
        }
    }

    private fun readRecords(bytes: ByteArray): List<DataRecord> {
        val sealed = DataArchive.open(bytes.inputStream())
        return sealed.keysFor(password()).use { keys ->
            DataArchive.read(sealed, keys).use { reader -> generateSequence { reader.next() }.mapNotNull { it.decode() }.toList() }
        }
    }

    private fun password() = "cheval correct agrafe".toCharArray()

    private companion object {
        const val T = 1_700_000_000_000L
        const val DAY = 86_400_000L
        const val DATED = 1_500_000_000_000L
        const val NEW_HIDDEN = 123_456L
        const val OLD_EMPTY_HIDDEN = 99_999L

        /** Médias de l'ancien téléphone : dossier, nom, taille. */
        val OLD_MEDIA = listOf(
            Triple("DCIM/Camera/", "IMG_0001.jpg", 1_001L),
            Triple("DCIM/Camera/", "IMG_0002.jpg", 1_002L),
            Triple("DCIM/Camera/", "IMG_0003.jpg", 1_003L),
            Triple("DCIM/Camera/", "IMG_0004.jpg", 1_004L),
            Triple("DCIM/Camera/", "IMG_0005.jpg", 1_005L),
            Triple("DCIM/Camera/", "IMG_0006.jpg", 1_006L),
            Triple("DCIM/Camera/", "IMG_0007.jpg", 1_007L),
            Triple("DCIM/Camera/", "IMG_0008.jpg", 1_008L),
            Triple("DCIM/Camera/", "IMG_0009.jpg", 9_009L),
            Triple("Pictures/Ancien/", "IMG_0100.jpg", 1_100L),
            Triple("Pictures/WhatsApp/", "photo.jpg", 5_000L),
        )
    }
}

/** Lignes d'une requête, colonne unique, en texte. */
private fun FocaleDatabase.query(sql: String): List<String> = openHelper.readableDatabase.query(sql).use { cursor ->
    buildList { while (cursor.moveToNext()) add(cursor.getString(0) ?: "∅") }
}

/** Mise en place des données de test, hors d'un `runTest` (constructeurs). */
private fun <T> runTestBlocking(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }
