package app.focale.core.database

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseMigrationsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `la migration 1 vers 5 conserve les données et produit le schéma attendu par Room`() {
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
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(j).getString("createSql").replace(TABLE_NAME, table))
            }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
    }

    private companion object {
        /** Dossier courant des tests : le module app. */
        val SCHEMAS = File("schemas/app.focale.core.database.FocaleDatabase")
        const val TABLE_NAME = "\${TABLE_NAME}"
        const val LATEST = 5
    }
}
