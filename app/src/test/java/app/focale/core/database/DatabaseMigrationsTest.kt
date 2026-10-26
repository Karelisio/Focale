package app.focale.core.database

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.focale.ml.SearchQueryParser
import app.focale.ml.text.RecognizedText
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseMigrationsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `la migration 1 vers 9 conserve les données et produit le schéma attendu par Room`() {
        val file = databaseAt(version = 1) { db ->
            db.execSQL("INSERT INTO favorite (media_id, added_at) VALUES (42, 1000)")
            db.execSQL(vaultItem(id = 3))
        }
        // Room applique les migrations puis vérifie que le schéma obtenu est celui de la dernière version.
        openMigrated(file) { db ->
            assertEquals(42L, db.longOf("SELECT media_id FROM favorite"))
            db.execSQL("INSERT INTO media_text (media_id, analyzed_modified, version, text, text_key) VALUES (42, 0, 1, 'Café', 'cafe')")
            assertEquals(1L, db.longOf("SELECT COUNT(*) FROM media_text"))
            db.execSQL("INSERT INTO person (id, name, cover_face_id, hidden, created_at) VALUES (1, NULL, NULL, 0, 0)")
            assertEquals(0L, db.longOf("SELECT pinned FROM person WHERE id = 1"))
            assertEquals(1L, db.longOf("SELECT COUNT(*) FROM vault_item WHERE id = 3 AND album_id IS NULL"))
            db.execSQL("INSERT INTO vault_album (id, name, created_at) VALUES (1, 'Vacances', 0)")
            assertEquals(1L, db.longOf("SELECT COUNT(*) FROM vault_album"))
            assertEquals(LATEST, db.version)
        }
    }

    @Test
    fun `la migration 3 vers 4 ajoute les albums du coffre sans toucher à ses éléments`() {
        val file = databaseAt(version = 3) { db ->
            db.execSQL(vaultItem(id = 5))
            db.execSQL(vaultItem(id = 6))
        }
        openMigrated(file) { db ->
            // Éléments existants intacts, hors album : ils restent dans « Tous les éléments ».
            assertEquals("5.bin", db.stringOf("SELECT file_name FROM vault_item WHERE id = 5"))
            assertEquals(2L, db.longOf("SELECT COUNT(*) FROM vault_item WHERE album_id IS NULL"))
            db.execSQL(
                "INSERT INTO vault_album (id, name, source_bucket_id, source_path, created_at, last_import_at) " +
                    "VALUES (1, 'WhatsApp Images', 77, 'Pictures/WhatsApp Images/', 3000, 3000)",
            )
            db.execSQL("UPDATE vault_item SET album_id = 1")
            assertEquals("Pictures/WhatsApp Images/", db.stringOf("SELECT source_path FROM vault_album WHERE id = 1"))
            // Album supprimé : ses éléments restent dans le coffre, hors album (clé étrangère active).
            db.execSQL("DELETE FROM vault_album WHERE id = 1")
            assertEquals(2L, db.longOf("SELECT COUNT(*) FROM vault_item WHERE album_id IS NULL"))
            assertEquals(LATEST, db.version)
        }
    }

    @Test
    fun `la migration depuis la version 2 ajoute l'épinglage et les paires refusées`() {
        val file = databaseAt(version = 2) { db ->
            db.execSQL("INSERT INTO person (id, name, cover_face_id, hidden, created_at) VALUES (7, 'Léa', NULL, 0, 0)")
            db.execSQL("INSERT INTO person (id, name, cover_face_id, hidden, created_at) VALUES (8, NULL, NULL, 1, 0)")
        }
        openMigrated(file) { db ->
            // Les personnes existantes ne sont pas épinglées et gardent leurs réglages.
            assertEquals(0L, db.longOf("SELECT COUNT(*) FROM person WHERE pinned = 1"))
            assertEquals("Léa", db.query("SELECT name FROM person WHERE id = 7").use { it.moveToFirst(); it.getString(0) })
            assertEquals(1L, db.longOf("SELECT hidden FROM person WHERE id = 8"))
            // Paire refusée, supprimée avec l'une des deux personnes (clé étrangère active).
            db.execSQL("INSERT INTO person_refusal (person_low, person_high) VALUES (7, 8)")
            db.execSQL("DELETE FROM person WHERE id = 8")
            assertEquals(0L, db.longOf("SELECT COUNT(*) FROM person_refusal"))
            assertEquals(LATEST, db.version)
        }
    }

    @Test
    fun `la migration 4 vers 5 ajoute les dates d'origine, le journal des imports et la corbeille du coffre`() {
        val file = databaseAt(version = 4) { db ->
            db.execSQL(vaultItem(id = 9))
        }
        openMigrated(file) { db ->
            // Éléments existants : dans le coffre, sans original connu, hors corbeille.
            assertEquals(
                1L,
                db.longOf("SELECT COUNT(*) FROM vault_item WHERE id = 9 AND importing = 0 AND source_media_id IS NULL AND deleted_at IS NULL"),
            )
            db.execSQL("INSERT INTO media_date (media_id, date_taken) VALUES (12, 1519979400000)")
            assertEquals(1_519_979_400_000L, db.longOf("SELECT date_taken FROM media_date WHERE media_id = 12"))
            assertEquals(LATEST, db.version)
        }
    }

    @Test
    fun `la migration 5 vers 6 donne à la timeline, aux dossiers et aux filtres des index composés`() {
        // Base de la 1.7.0, avec les index simples de la corbeille, des dossiers et des types.
        val file = databaseAt(version = 5) { db ->
            db.execSQL(
                "INSERT INTO media (id, media_type, mime_type, display_name, relative_path, bucket_id, bucket_name, date_taken, " +
                    "date_added, date_modified, generation, size, width, height, orientation, duration, volume_name, " +
                    "is_screenshot, is_gif, is_trashed, date_expires) " +
                    "VALUES (7, 1, 'image/jpeg', 'IMG_7.jpg', 'DCIM/Camera/', 42, 'Camera', 1000, 1000, 1000, 1, 1, 1, 1, 0, 0, " +
                    "'external_primary', 0, 0, 0, 0)",
            )
        }
        openMigrated(file) { db ->
            // L'index seul de la corbeille (qui faisait trier toute la bibliothèque), ceux des dossiers
            // et des types sont remplacés par des index qui finissent par la date.
            assertEquals(
                setOf(
                    "index_media_date_taken",
                    "index_media_is_trashed_date_taken",
                    "index_media_bucket_id_is_trashed_date_taken",
                    "index_media_media_type_is_trashed_date_taken",
                ),
                db.indexNames("media"),
            )
            assertEquals(listOf("is_trashed", "date_taken"), db.indexColumns("index_media_is_trashed_date_taken"))
            assertEquals(listOf("bucket_id", "is_trashed", "date_taken"), db.indexColumns("index_media_bucket_id_is_trashed_date_taken"))
            assertEquals(listOf("media_type", "is_trashed", "date_taken"), db.indexColumns("index_media_media_type_is_trashed_date_taken"))
            assertEquals(7L, db.longOf("SELECT id FROM media WHERE bucket_id = 42 AND is_trashed = 0"))
            // Timeline, dossier et filtre Vidéos se lisent dans l'ordre de leur index, sans tri temporaire.
            val order = "ORDER BY m.date_taken DESC, m.id DESC LIMIT 10"
            val timeline = db.plan("SELECT * FROM media m WHERE m.is_trashed = 0 $order")
            assertTrue(timeline, "index_media_is_trashed_date_taken" in timeline && "TEMP B-TREE" !in timeline)
            val folder = db.plan("SELECT * FROM media m WHERE m.is_trashed = 0 AND m.bucket_id = 42 $order")
            assertTrue(folder, "index_media_bucket_id_is_trashed_date_taken" in folder && "TEMP B-TREE" !in folder)
            val videos = db.plan("SELECT * FROM media m WHERE m.is_trashed = 0 AND m.media_type = 3 $order")
            assertTrue(videos, "index_media_media_type_is_trashed_date_taken" in videos && "TEMP B-TREE" !in videos)
            assertEquals(LATEST, db.version)
        }
    }

    @Test
    fun `la migration 6 vers 7 donne aux étiquettes un index qui suffit à les compter`() {
        // Base de la 1.9.0 (version 6 publiée), avec l'index simple des étiquettes.
        val file = databaseAt(version = 6) { db ->
            db.execSQL("INSERT INTO media_label (media_id, label, confidence) VALUES (7, 'Dog', 0.9), (8, 'Dog', 0.7), (7, 'Beach', 0.8)")
        }
        openMigrated(file) { db ->
            assertEquals(setOf("index_media_label_label_confidence_media_id", "sqlite_autoindex_media_label_1"), db.indexNames("media_label"))
            assertEquals(listOf("label", "confidence", "media_id"), db.indexColumns("index_media_label_label_confidence_media_id"))
            assertEquals(3L, db.longOf("SELECT COUNT(*) FROM media_label"))
            // Recherche par étiquette et photo la plus sûre de chacune par l'index seul.
            val search = db.plan("SELECT media_id FROM media_label WHERE label IN ('Dog', 'Poodle')")
            assertTrue(search, "COVERING INDEX index_media_label_label_confidence_media_id" in search)
            assertEquals(7L, db.longOf("SELECT media_id FROM media_label WHERE label = 'Dog' ORDER BY confidence DESC LIMIT 1"))
            // Les index des médias de la version 6 restent.
            assertTrue("index_media_is_trashed_date_taken" in db.indexNames("media"))
            assertEquals(LATEST, db.version)
        }
    }

    @Test
    fun `la migration 7 vers 8 compte les étiquettes et indexe les mots des noms des médias déjà là`() {
        // Base de la 1.10.0 (version 7 publiée) : un média dans la corbeille, des étiquettes d'un média
        // absent de l'index (accès partiel).
        val file = databaseAt(version = 7) { db ->
            db.execSQL(media(id = 7, name = "Screenshot_20240101-120000.png"))
            db.execSQL(media(id = 8, name = "IMG_20240102_090000.jpg"))
            db.execSQL(media(id = 9, name = "facture_EDF.jpg", trashed = true))
            db.execSQL(
                "INSERT INTO media_label (media_id, label, confidence) " +
                    "VALUES (7, 'Dog', 0.9), (8, 'Dog', 0.7), (8, 'Beach', 0.8), (9, 'Dog', 0.6), (12, 'Beach', 0.9)",
            )
        }
        val database = Room.databaseBuilder(context, FocaleDatabase::class.java, file.path)
            .addMigrations(*DatabaseMigrations.ALL)
            .allowMainThreadQueries()
            .build()
        try {
            val db = database.openHelper.writableDatabase
            assertEquals(LATEST, db.version)
            // Médias de l'index, hors corbeille : Dog 2, Beach 1, comme le recalcul complet.
            val counts = runBlocking { database.analysisDao().labelCounts() }
            assertEquals(listOf(LabelTally("Beach", 1), LabelTally("Dog", 2)), counts)
            runBlocking { database.analysisDao().rebuildLabelCounts() }
            assertEquals(counts, runBlocking { database.analysisDao().labelCounts() })
            // Mots des noms : chaque média indexé, cherché par l'index plein texte.
            assertEquals(3L, db.longOf("SELECT COUNT(*) FROM media_name"))
            assertEquals(MediaNames.document("Screenshot_20240101-120000.png"), db.stringOf("SELECT words FROM media_name WHERE rowid = 7"))
            assertEquals(7L, db.longOf("SELECT rowid FROM media_name WHERE media_name MATCH '\"shot*\"'"))
            assertEquals(9L, db.longOf("SELECT rowid FROM media_name WHERE media_name MATCH '\"edf*\"'"))
            // Écritures suivantes tenues à jour : le média sort de la corbeille.
            runBlocking {
                database.mediaDao().upsert(listOf(database.mediaDao().get(9)!!.copy(isTrashed = false)))
                assertEquals(listOf(LabelTally("Beach", 1), LabelTally("Dog", 3)), database.analysisDao().labelCounts())
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `la migration 8 vers 9 indexe les textes déjà lus et compte les lieux déjà analysés`() {
        // Base de la 1.11.0 (version 8 publiée) : textes lus, lieux analysés (dont un média absent de
        // l'index, accès partiel), mots des noms déjà indexés.
        val file = databaseAt(version = 8) { db ->
            db.execSQL(media(id = 7, name = "IMG_7.jpg"))
            db.execSQL(media(id = 8, name = "IMG_8.jpg"))
            db.execSQL(media(id = 9, name = "Screenshot_9.png"))
            db.execSQL("INSERT INTO media_name (rowid, words) VALUES (7, 'IMG'), (8, 'IMG'), (9, '${MediaNames.document("Screenshot_9.png")}')")
            db.execSQL(
                "INSERT INTO media_text (media_id, analyzed_modified, version, text, text_key) VALUES " +
                    "(7, 1000, 1, 'FACTURE N° 2024-118', 'facture n 2024 118 2024118'), (8, 1000, 1, 'WIFI gratuit · Code CAFE2024', " +
                    "'wifi gratuit code cafe2024'), " +
                    "(9, 1000, 1, 'Réseau Wi-Fi', 'reseau wi fi wifi')",
            )
            db.execSQL(
                "INSERT INTO media_analysis (media_id, analyzed_modified, version, phash, blur_score, latitude, longitude, place, place_key, country) " +
                    "VALUES (7, 1000, 2, NULL, NULL, 47.2, -1.5, 'Nantes', 'nantes', 'FR'), (8, 1000, 2, NULL, NULL, 47.2, -1.5, 'Nantes', 'nantes', 'FR'), " +
                    "(9, 1000, 2, NULL, NULL, NULL, NULL, NULL, NULL, NULL), (12, 1000, 2, NULL, NULL, 48.6, -2.0, 'Saint-Malo', 'saint malo', 'FR')",
            )
        }
        val database = Room.databaseBuilder(context, FocaleDatabase::class.java, file.path)
            .addMigrations(*DatabaseMigrations.ALL)
            .allowMainThreadQueries()
            .build()
        try {
            val db = database.openHelper.writableDatabase
            assertEquals(LATEST, db.version)
            // Textes déjà lus : cherchés par l'index plein texte, par début de mot.
            assertEquals(7L, db.longOf("SELECT docid FROM media_text_fts WHERE media_text_fts MATCH '\"fact*\"'"))
            assertEquals(listOf("8", "9"), db.strings("SELECT docid FROM media_text_fts WHERE media_text_fts MATCH '\"wifi*\"' ORDER BY docid"))
            // Clés recalculées d'abord : lettres et chiffres collés, aussi séparés.
            assertEquals(RecognizedText.searchKey("WIFI gratuit · Code CAFE2024"), db.stringOf("SELECT text_key FROM media_text WHERE media_id = 8"))
            assertEquals(listOf("7", "8"), db.strings("SELECT docid FROM media_text_fts WHERE media_text_fts MATCH '\"2024*\"' ORDER BY docid"))
            // Lieux : une clé par lieu, médias absents compris, comme le recalcul complet.
            val analysis = database.analysisDao()
            val tallies = "SELECT place_key || '=' || count FROM place_count ORDER BY place_key"
            assertEquals(listOf("nantes=2", "saint malo=1"), db.strings(tallies))
            runBlocking { analysis.rebuildPlaceCounts() }
            assertEquals(listOf("nantes=2", "saint malo=1"), db.strings(tallies))
            assertEquals(emptyList<String>(), runBlocking { analysis.checkCounts() })
            // Aucun nom en attente : ceux des médias déjà là sont indexés depuis la version 8.
            assertEquals(0L, db.longOf("SELECT COUNT(*) FROM media_name_pending"))
            runBlocking {
                val dao = database.mediaDao()
                fun search(word: String) = MediaQueryBuilder.ids(SearchQueryParser.parse(word))
                assertEquals(listOf(7L), dao.ids(search("fact")))
                assertEquals(listOf(9L), dao.ids(search("shot")))
                assertEquals(listOf(8L, 7L), dao.ids(search("nantes")))
                // Écritures suivantes tenues à jour : l'index du texte suit ses déclencheurs.
                database.textDao().upsert(listOf(MediaTextEntity(8, 2000, 1, "Facture EDF", "facture edf")))
                database.textDao().upsert(listOf(MediaTextEntity(7, 2000, 1, "Menu", "menu")))
                assertEquals(listOf(8L), dao.ids(search("fact")))
                assertEquals(listOf(7L), dao.ids(search("menu")))
                dao.deleteByIds(listOf(9L))
                dao.deleteOrphanTexts()
                assertEquals(0L, db.longOf("SELECT COUNT(*) FROM media_text_fts WHERE media_text_fts MATCH '\"wifi*\"'"))
            }
        } finally {
            database.close()
        }
    }

    private fun SupportSQLiteDatabase.strings(sql: String): List<String> = query(sql).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    private fun SupportSQLiteDatabase.indexNames(table: String): Set<String> = query("PRAGMA index_list(`$table`)").use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
    }

    private fun SupportSQLiteDatabase.indexColumns(index: String): List<String> = query("PRAGMA index_info(`$index`)").use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
    }

    private fun SupportSQLiteDatabase.plan(sql: String): String = query("EXPLAIN QUERY PLAN $sql").use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }.joinToString(" | ")
    }

    private fun media(id: Long, name: String, trashed: Boolean = false) =
        "INSERT INTO media (id, media_type, mime_type, display_name, relative_path, bucket_id, bucket_name, date_taken, " +
            "date_added, date_modified, generation, size, width, height, orientation, duration, volume_name, " +
            "is_screenshot, is_gif, is_trashed, date_expires) " +
            "VALUES ($id, 1, 'image/jpeg', '$name', 'DCIM/Camera/', 42, 'Camera', 1000, 1000, 1000, 1, 1, 1, 1, 0, 0, " +
            "'external_primary', 0, 0, ${if (trashed) 1 else 0}, 0)"

    private fun vaultItem(id: Long) =
        "INSERT INTO vault_item (id, display_name, mime_type, media_type, original_path, date_taken, size, width, height, " +
            "duration, file_name, thumb_file_name, added_at) " +
            "VALUES ($id, 'IMG_$id.jpg', 'image/jpeg', 1, 'DCIM/Camera/', 1000, 42, 4000, 3000, 0, '$id.bin', '$id.thumb', 2000)"

    /** Base en version [version], créée à partir du schéma exporté par Room. */
    private fun databaseAt(version: Int, fill: (SQLiteDatabase) -> Unit): File {
        val file = context.getDatabasePath("migration-test-$version.db").apply { parentFile?.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            createFromSchema(db, File(SCHEMAS, "$version.json"))
            fill(db)
            db.version = version
        }
        return file
    }

    private fun openMigrated(file: File, check: (SupportSQLiteDatabase) -> Unit) {
        val database = Room.databaseBuilder(context, FocaleDatabase::class.java, file.path)
            .addMigrations(*DatabaseMigrations.ALL)
            .allowMainThreadQueries()
            .build()
        try {
            check(database.openHelper.writableDatabase)
        } finally {
            database.close()
        }
    }

    private fun SupportSQLiteDatabase.longOf(sql: String): Long = query(sql).use { cursor ->
        cursor.moveToFirst()
        cursor.getLong(0)
    }

    private fun SupportSQLiteDatabase.stringOf(sql: String): String = query(sql).use { cursor ->
        cursor.moveToFirst()
        cursor.getString(0)
    }

    private fun createFromSchema(db: SQLiteDatabase, schemaFile: File) {
        val schema = JSONObject(schemaFile.readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace(TABLE_NAME, table))
            val indices = entity.optJSONArray("indices")
            for (j in 0 until (indices?.length() ?: 0)) {
                db.execSQL(indices!!.getJSONObject(j).getString("createSql").replace(TABLE_NAME, table))
            }
            // Index plein texte à contenu externe : ses déclencheurs, créés par Room avec la table.
            val triggers = entity.optJSONArray("contentSyncTriggers")
            for (j in 0 until (triggers?.length() ?: 0)) db.execSQL(triggers!!.getString(j))
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
    }

    private companion object {
        /** Dossier courant des tests : le module app. */
        val SCHEMAS = File("schemas/app.focale.core.database.FocaleDatabase")
        const val TABLE_NAME = "\${TABLE_NAME}"
        const val LATEST = 9
    }
}
