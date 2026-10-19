package app.focale.core.database

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
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

    @Test
    fun `la migration 1 vers 2 conserve les données et produit le schéma attendu par Room`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = context.getDatabasePath("migration-test.db").apply { parentFile?.mkdirs(); delete() }
        // Base en version 1, créée à partir du schéma exporté par Room.
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            createFromSchema(db, File(SCHEMAS, "1.json"))
            db.execSQL("INSERT INTO favorite (media_id, added_at) VALUES (42, 1000)")
            db.version = 1
        }
        // Room applique la migration puis vérifie que le schéma obtenu est celui de la version 2.
        val database = Room.databaseBuilder(context, FocaleDatabase::class.java, file.path)
            .addMigrations(*DatabaseMigrations.ALL)
            .allowMainThreadQueries()
            .build()
        try {
            val db = database.openHelper.writableDatabase
            db.query("SELECT media_id FROM favorite").use { cursor ->
                cursor.moveToFirst()
                assertEquals(42L, cursor.getLong(0))
            }
            db.execSQL("INSERT INTO media_text (media_id, analyzed_modified, version, text, text_key) VALUES (42, 0, 1, 'Café', 'cafe')")
            db.query("SELECT COUNT(*) FROM media_text").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            assertEquals(2, db.version)
        } finally {
            database.close()
        }
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
    }
}
