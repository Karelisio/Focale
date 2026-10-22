package app.focale.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.focale.core.database.FocaleDatabase
import app.focale.core.database.MediaAnalysisEntity
import app.focale.core.database.MediaEntity
import app.focale.core.database.MediaLabelEntity
import app.focale.core.database.MediaQueryBuilder
import app.focale.core.database.MediaTextEntity
import app.focale.core.model.MediaQuery
import app.focale.core.model.SearchTerm
import app.focale.core.model.TextCriteria
import app.focale.data.SmartAlbumKind.ANIMALS
import app.focale.data.SmartAlbumKind.CELEBRATIONS
import app.focale.data.SmartAlbumKind.DOCUMENTS
import app.focale.data.SmartAlbumKind.FOOD
import app.focale.data.SmartAlbumKind.LANDSCAPES
import app.focale.data.SmartAlbumKind.RECEIPTS
import app.focale.data.SmartAlbumKind.SPORT
import app.focale.data.SmartAlbumKind.VEHICLES
import app.focale.ml.LabelDictionary
import app.focale.ml.text.RecognizedText
import app.focale.ml.text.TextRecognitionStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Albums automatiques : catégories → requêtes, puis nombres et couvertures sur une vraie base Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmartAlbumsTest {

    private lateinit var database: FocaleDatabase
    private lateinit var repository: SmartAlbumRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FocaleDatabase::class.java).allowMainThreadQueries().build()
        val media = MediaRepository(database.mediaDao(), Dispatchers.IO, database.invalidationTracker)
        repository = SmartAlbumRepository(database.mediaDao(), media, Dispatchers.IO)
        runBlocking { library() }
    }

    @After
    fun tearDown() = database.close()

    // ── Catégories → requêtes ─────────────────────────────────────────────────────────

    @Test
    fun `six à huit catégories, toutes faites d'étiquettes du dictionnaire`() {
        assertTrue(SmartAlbumKind.entries.size in 6..8)
        SmartAlbumKind.entries.forEach { kind ->
            val labels = SmartAlbums.term(kind).labels
            assertTrue("$kind sans étiquette", labels.isNotEmpty())
            labels.forEach { assertTrue("$kind : $it inconnue", LabelDictionary.entry(it) != null) }
        }
    }

    @Test
    fun `les catégories reprennent les hyperonymes du dictionnaire`() {
        val animals = SmartAlbums.term(ANIMALS).labels
        // Races de chiens et de chats comprises, comme pour la recherche « animaux ».
        assertTrue(listOf("Animal", "Dog", "Shetland sheepdog", "Cat", "Ragdoll", "Bird", "Horse").all { it in animals })
        assertTrue("Boat" in SmartAlbums.term(VEHICLES).labels && "Car" in SmartAlbums.term(VEHICLES).labels)
        val landscapes = SmartAlbums.term(LANDSCAPES).labels
        assertTrue(listOf("Sky", "Mountain", "Beach", "Sunset", "Lake").all { it in landscapes })
        // Une fleur ou une plante en gros plan n'est pas un paysage.
        assertTrue(SmartAlbums.vegetation.none { it in landscapes })
        assertEquals(SmartAlbums.documentLabels, SmartAlbums.term(DOCUMENTS).labels)
        assertEquals(listOf("Receipt"), SmartAlbums.term(RECEIPTS).labels)
    }

    @Test
    fun `un mot cherché dans l'onglet Recherche mène aux étiquettes de son album`() {
        mapOf(
            "chiens" to ANIMALS, "chatons" to ANIMALS, "oiseau" to ANIMALS,
            "pizza" to FOOD, "vin" to FOOD,
            "plage" to LANDSCAPES, "ocean" to LANDSCAPES, "coucher" to LANDSCAPES, "montagnes" to LANDSCAPES,
            "noel" to CELEBRATIONS, "bateau" to VEHICLES, "football" to SPORT,
        ).forEach { (word, kind) ->
            val matched = LabelDictionary.match(word)
            assertTrue(word, matched.isNotEmpty() && matched.all { it in SmartAlbums.term(kind).labels })
        }
        assertTrue(LabelDictionary.match("fleurs").none { it in SmartAlbums.term(LANDSCAPES).labels })
    }

    @Test
    fun `les indices de reçu reprennent les synonymes de l'étiquette Receipt`() {
        val receipt = SmartAlbums.term(RECEIPTS).recognizedText!!
        assertEquals(SmartAlbums.RECEIPT_MIN_KEYWORDS, receipt.minKeywords)
        val expected = listOf("Receipt", "Reçu", "ticket", "facture", "total", "tva", "ttc", "montant", "€")
        assertTrue(receipt.keywords.containsAll(expected))
        assertEquals(SmartAlbums.DOCUMENT_MIN_LENGTH, SmartAlbums.term(DOCUMENTS).recognizedText!!.minLength)
    }

    @Test
    fun `le texte reconnu est cherché en mots entiers, hors captures d'écran`() {
        val args = mutableListOf<Any?>()
        val where = MediaQueryBuilder.where(SmartAlbums.query(RECEIPTS, excludedBuckets = listOf(7L)), args)
        assertTrue(where.contains("m.bucket_id NOT IN (7)"))
        assertTrue(where.contains("(m.is_screenshot = 0 AND m.id IN (SELECT media_id FROM media_text WHERE length(text_key) >= ?"))
        assertTrue(where.contains("((' ' || text_key || ' ') LIKE ?) + "))
        assertTrue(where.contains("(text LIKE ? ESCAPE '\\')"))
        assertTrue(args.containsAll(listOf("Receipt", "% recu %", "% tva %", "%€%", "%\$%", SmartAlbums.RECEIPT_MIN_KEYWORDS)))
    }

    @Test
    fun `un indice n'est compté qu'une fois, quelle que soit sa graphie`() {
        val args = mutableListOf<Any?>()
        val criteria = TextCriteria(keywords = listOf("Reçu", "recu", " RECU ", "€", "€", " "), minKeywords = 2)
        MediaQueryBuilder.where(MediaQuery(terms = listOf(SearchTerm(recognizedText = criteria))), args)
        assertEquals(listOf<Any?>(1, "% recu %", "%€%", 2), args)
    }

    @Test
    fun `une seule requête résume tous les albums, sous la limite de paramètres de SQLite`() {
        val queries = SmartAlbumKind.entries.map { SmartAlbums.query(it, excludedBuckets = listOf(3L, 9L)) }
        val summary = MediaQueryBuilder.summaries(queries)
        assertEquals(queries.size - 1, Regex(" UNION ALL ").findAll(summary.sql).count())
        assertTrue(summary.argCount < 999)
        // Mêmes arguments que le compteur et la couverture de chaque album.
        val separate = queries.sumOf { MediaQueryBuilder.count(it).argCount + MediaQueryBuilder.bestCover(it).argCount }
        assertEquals(separate, summary.argCount)
    }

    // ── Nombres et couvertures sur la bibliothèque de test ────────────────────────────

    @Test
    fun `nombres de chaque album, seuil d'apparition compris`() = runTest {
        val albums = repository.observe().first().associateBy { it.kind }
        // Nourriture (2 photos) n'atteint pas le seuil ; ni fête, ni véhicule, ni sport.
        assertEquals(listOf(DOCUMENTS, RECEIPTS, ANIMALS, LANDSCAPES), albums.keys.toList())
        assertEquals(setOf(30L, 31L, 40L), ids(albums.getValue(DOCUMENTS)))
        assertEquals(setOf(40L, 41L, 42L), ids(albums.getValue(RECEIPTS)))
        // Races et vidéo comprises, corbeille exclue.
        assertEquals(setOf(1L, 2L, 3L, 4L, 6L, 7L), ids(albums.getValue(ANIMALS)))
        assertEquals(setOf(3L, 20L, 21L), ids(albums.getValue(LANDSCAPES)))
        albums.values.forEach { album -> assertEquals(album.kind.name, ids(album).size, album.count) }
    }

    @Test
    fun `la couverture est la photo la plus nette de l'album`() = runTest {
        val albums = repository.observe().first().associateBy { it.kind }
        assertEquals(2L, albums.getValue(ANIMALS).cover?.id)
        albums.values.forEach { album -> assertTrue(album.cover!!.id in ids(album)) }
    }

    @Test
    fun `les dossiers masqués sont exclus, comme dans la timeline`() = runTest {
        val animals = repository.observe(excludedBuckets = listOf(WHATSAPP)).first().single { it.kind == ANIMALS }
        assertEquals(5, animals.count)
        assertEquals(listOf(WHATSAPP), animals.query.excludedBuckets)
        assertTrue(6L !in ids(animals))
    }

    @Test
    fun `les albums suivent l'avancement de l'analyse`() = runTest {
        repository.observe().test {
            assertTrue(awaitItem().none { it.kind == FOOD })
            label(12, "Cake")
            assertEquals(setOf(10L, 11L, 12L), ids(awaitItem().single { it.kind == FOOD }))
            // Texte reconnu plus tard : un quatrième reçu.
            text(13, "TICKET N° 0042\nSOUS-TOTAL 8,40\nTVA 10 % 0,76")
            assertEquals(4, awaitItem().single { it.kind == RECEIPTS }.count)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Médias de la grille ouverte depuis la tuile : sa requête, telle quelle. */
    private suspend fun ids(album: SmartAlbum): Set<Long> =
        database.mediaDao().ids(MediaQueryBuilder.ids(album.query)).toSet()

    private suspend fun library() {
        // Animaux : races, vidéo, corbeille et dossier masquable.
        media(1); label(1, "Dog", blur = 50f)
        media(2); label(2, "Cat", blur = 300f)
        media(3); label(3, "Bird", "Sky", blur = 120f)
        media(4); label(4, "Shetland sheepdog")
        media(5, trashed = true); label(5, "Dog", blur = 900f)
        media(6, bucket = WHATSAPP); label(6, "Horse")
        media(7, video = true); label(7, "Cat", blur = 800f)
        // Nourriture : deux photos seulement ; la 12 n'est pas encore étiquetée.
        media(10); label(10, "Pizza")
        media(11); label(11, "Sushi")
        media(12)
        // Paysages : une fleur n'en est pas un.
        media(20); label(20, "Mountain")
        media(21); label(21, "Beach", "Sand")
        media(22); label(22, "Flower", "Plant")
        // Documents : texte conséquent, étiquette de document ; ni capture ni enseigne.
        media(30); text(30, LETTER)
        media(31); label(31, "Paper"); text(31, "Note : rappeler le plombier")
        media(32, screenshot = true); text(32, LETTER)
        media(33); text(33, "BOULANGERIE PATISSERIE")
        // Reçus : étiquette, ou au moins deux indices.
        media(40); label(40, "Receipt")
        media(41); text(41, "SUPER U\nTOTAL TTC 12,50 €\nCB SANS CONTACT")
        media(42); text(42, "Montant : 45,00 EUR\nTVA 20 %")
        media(43); text(43, "PLAT DU JOUR 14,50 €\nDESSERT 6 €")
        media(44); text(44, "TOTAL\nACCESS")
        media(45, screenshot = true); text(45, "Total 12 € TTC")
        media(46); text(46, "Livraison totalement offerte dès 5 €")
        media(13)
    }

    private suspend fun media(
        id: Long,
        bucket: Long = CAMERA,
        screenshot: Boolean = false,
        video: Boolean = false,
        trashed: Boolean = false,
    ) = database.mediaDao().upsert(
        listOf(
            MediaEntity(
                id = id, mediaType = if (video) MediaQueryBuilder.MEDIA_TYPE_VIDEO else MediaQueryBuilder.MEDIA_TYPE_IMAGE,
                mimeType = if (video) "video/mp4" else "image/jpeg", displayName = "IMG_$id.jpg",
                relativePath = if (screenshot) "Pictures/Screenshots/" else "DCIM/Camera/", bucketId = bucket,
                bucketName = "Camera", dateTaken = id * 1_000, dateAdded = id * 1_000, dateModified = 1_000L, generation = 0,
                size = 1, width = 4000, height = 3000, orientation = 0, duration = 0, volumeName = "external_primary",
                isScreenshot = screenshot, isGif = false, isTrashed = trashed, dateExpires = 0,
            ),
        ),
    )

    private suspend fun label(id: Long, vararg labels: String, blur: Float? = null) = database.analysisDao().save(
        listOf(MediaAnalysisEntity(id, 1_000L, 2, null, blur, null, null, null, null, null)),
        labels.map { MediaLabelEntity(id, it, 0.9f) },
    )

    private suspend fun text(id: Long, text: String) = database.textDao().upsert(
        listOf(MediaTextEntity(id, 1_000L, TextRecognitionStep.VERSION, text, RecognizedText.searchKey(text))),
    )

    private companion object {
        const val CAMERA = 1L
        const val WHATSAPP = 2L

        /** Courrier photographié : bien plus de 200 caractères. */
        const val LETTER = "Madame, Monsieur,\nSuite à notre entretien du 12 mars, je vous confirme ma demande de " +
            "résiliation du contrat n° 4521-B à compter du 1er avril.\nJe vous prie de bien vouloir m'adresser une " +
            "attestation de fin de contrat.\nVeuillez agréer, Madame, Monsieur, mes salutations distinguées."
    }
}
