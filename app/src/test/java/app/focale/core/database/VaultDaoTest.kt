package app.focale.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Albums du coffre : nombres, couvertures, rangement, suppression et nouveaux médias du dossier d'origine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultDaoTest {

    private lateinit var database: FocaleDatabase
    private lateinit var vault: VaultDao
    private lateinit var media: MediaDao

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FocaleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        vault = database.vaultDao()
        media = database.mediaDao()
    }

    @After
    fun close() = database.close()

    @Test
    fun `un album donne son nombre d'éléments et la miniature de son élément le plus récent`() = runTest {
        val trip = vault.insertAlbum(VaultAlbumEntity(name = "Vacances", createdAt = 1))
        val empty = vault.insertAlbum(VaultAlbumEntity(name = "Vide", createdAt = 2))
        vault.insert(item("ancienne", dateTaken = 10, albumId = trip))
        vault.insert(item("récente", dateTaken = 30, albumId = trip))
        // Plus récente encore, mais sans miniature : pas de couverture possible.
        vault.insert(item("sans miniature", dateTaken = 40, albumId = trip, thumbnail = false))
        vault.insert(item("hors album", dateTaken = 50))

        val rows = vault.observeAlbums().first()
        // Les plus récemment créés d'abord.
        assertEquals(listOf("Vide", "Vacances"), rows.map { it.album.name })
        assertEquals(0, rows[0].count)
        assertNull(rows[0].coverThumb)
        assertEquals(3, rows[1].count)
        assertEquals("récente.thumb", rows[1].coverThumb)
        assertEquals(listOf("sans miniature", "récente", "ancienne"), vault.observeInAlbum(trip).first().map { it.displayName })
        assertTrue(vault.observeInAlbum(empty).first().isEmpty())
        // « Tous les éléments » : le coffre entier, albums compris.
        assertEquals(4, vault.observeAll().first().size)
    }

    @Test
    fun `supprimer un album garde ses éléments dans le coffre, hors album`() = runTest {
        val album = vault.insertAlbum(whatsApp())
        val ids = listOf(vault.insert(item("a", albumId = album)), vault.insert(item("b", albumId = album)))
        vault.deleteAlbum(album)
        val items = vault.observeAll().first()
        assertEquals(ids.toSet(), items.map { it.id }.toSet())
        assertTrue(items.all { it.albumId == null })
        assertTrue(vault.observeAlbums().first().isEmpty())
    }

    @Test
    fun `ranger des éléments ne change que leur album`() = runTest {
        val first = vault.insertAlbum(VaultAlbumEntity(name = "Un", createdAt = 1))
        val second = vault.insertAlbum(VaultAlbumEntity(name = "Deux", createdAt = 2))
        val a = vault.insert(item("a", albumId = first))
        val b = vault.insert(item("b", albumId = first))
        val before = vault.observeAll().first().associateBy { it.id }

        vault.setAlbum(listOf(a), second)
        vault.setAlbum(listOf(b), null)
        val after = vault.observeAll().first().associateBy { it.id }
        assertEquals(second, after.getValue(a).albumId)
        assertNull(after.getValue(b).albumId)
        // Fichiers et métadonnées inchangés : seul le classement bouge.
        assertEquals(before.getValue(a).copy(albumId = second), after.getValue(a))
        // Album disparu entre-temps : l'élément se retrouve hors album, sans erreur.
        vault.setAlbum(listOf(a), 999)
        assertNull(vault.observeAll().first().single { it.id == a }.albumId)
        val orphan = vault.insertImported(item("c", albumId = 999))
        assertNull(vault.observeAll().first().single { it.id == orphan }.albumId)
    }

    @Test
    fun `nouveaux médias du dossier d'origine, arrivés depuis le dernier import et hors corbeille`() = runTest {
        val lastImport = 1_700_000_000_000
        val folder = vault.insertAlbum(whatsApp(lastImportAt = lastImport))
        val plain = vault.insertAlbum(VaultAlbumEntity(name = "Vacances", createdAt = 2))
        media.upsert(
            listOf(
                photo(1, bucket = 77, added = lastImport - 5 * 60_000),
                // Dans la marge : arrivé dans MediaStore juste avant l'import, sans y avoir été pris.
                photo(2, bucket = 77, added = lastImport - 30_000),
                photo(3, bucket = 77, added = lastImport + 60_000),
                photo(4, bucket = 77, added = lastImport + 90_000, trashed = true),
                photo(5, bucket = 12, added = lastImport + 60_000),
            ),
        )
        assertEquals(listOf(3L, 2L), vault.newInSource(folder).map { it.id })
        assertEquals(listOf(3L, 2L), vault.observeNewInSource(folder).first().map { it.id })
        // Album sans dossier d'origine : rien à proposer.
        assertTrue(vault.newInSource(plain).isEmpty())

        vault.setLastImport(folder, lastImport + 5 * 60_000)
        assertTrue(vault.newInSource(folder).isEmpty())
        // Jamais importé : tout le dossier est proposé.
        val fresh = vault.insertAlbum(VaultAlbumEntity(name = "Neuf", sourceBucketId = 77, createdAt = 3))
        assertEquals(listOf(3L, 2L, 1L), vault.newInSource(fresh).map { it.id })
        // Le plus ancien album lié au dossier est celui qu'on réutilise.
        assertEquals(folder, vault.albumOfSource(77)?.id)
        assertNull(vault.albumOfSource(12))
    }

    @Test
    fun `une sauvegarde restaurée rejoint l'album de même nom et de même origine`() = runTest {
        val existing = vault.insertAlbum(whatsApp())
        vault.insertRestored(
            linkedMapOf(
                whatsApp(createdAt = 5).copy(id = 1) to listOf(item("a"), item("b")),
                // Même nom, mais pas le même dossier : un autre album.
                VaultAlbumEntity(id = 2, name = "WhatsApp Images", createdAt = 6, lastImportAt = 7) to listOf(item("c")),
                VaultAlbumEntity(id = 3, name = "Vide", createdAt = 8) to emptyList(),
                null to listOf(item("d")),
            ),
        )
        val albums = vault.albums()
        assertEquals(3, albums.size)
        val created = albums.single { it.id != existing && it.sourceBucketId == null && it.name == "WhatsApp Images" }
        assertEquals(7L, created.lastImportAt)
        val byName = vault.observeAll().first().associate { it.displayName to it.albumId }
        assertEquals(mapOf("a" to existing, "b" to existing, "c" to created.id, "d" to null), byName)
        assertTrue(albums.any { it.name == "Vide" })
    }

    private var nextFile = 0

    /** Album-dossier lié au dossier 77. */
    private fun whatsApp(createdAt: Long = 1, lastImportAt: Long? = null) = VaultAlbumEntity(
        name = "WhatsApp Images",
        sourceBucketId = 77,
        sourcePath = "Pictures/WhatsApp Images/",
        createdAt = createdAt,
        lastImportAt = lastImportAt,
    )

    private fun item(name: String, dateTaken: Long = 0, albumId: Long? = null, thumbnail: Boolean = true) = VaultItemEntity(
        displayName = name,
        mimeType = "image/jpeg",
        mediaType = MediaQueryBuilder.MEDIA_TYPE_IMAGE,
        originalPath = "DCIM/Camera/",
        dateTaken = dateTaken,
        size = 1,
        width = 100,
        height = 100,
        duration = 0,
        fileName = "${nextFile++}.bin",
        thumbFileName = "$name.thumb".takeIf { thumbnail },
        addedAt = 0,
        albumId = albumId,
    )

    private fun photo(id: Long, bucket: Long, added: Long, trashed: Boolean = false) = MediaEntity(
        id = id, mediaType = 1, mimeType = "image/jpeg", displayName = "IMG_$id.jpg",
        relativePath = "Pictures/Dossier $bucket/", bucketId = bucket, bucketName = "Dossier $bucket", dateTaken = added - id,
        dateAdded = added, dateModified = added, generation = 0, size = 1, width = 100, height = 100,
        orientation = 0, duration = 0, volumeName = "external_primary", isScreenshot = false,
        isGif = false, isTrashed = trashed, dateExpires = 0,
    )
}
