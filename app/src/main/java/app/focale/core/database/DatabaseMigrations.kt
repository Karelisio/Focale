package app.focale.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migrations de [FocaleDatabase] ; le SQL reprend celui exporté par Room dans app/schemas. */
object DatabaseMigrations {

    /** Version 2 : tables des étapes d'analyse complémentaires (texte, visages, empreintes). */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            CREATE_V2.forEach(db::execSQL)
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)

    private val CREATE_V2 = listOf(
        "CREATE TABLE IF NOT EXISTS `media_text` (`media_id` INTEGER NOT NULL, `analyzed_modified` INTEGER NOT NULL, `version` INTEGER NOT NULL, `text` TEXT NOT NULL, `text_key` TEXT NOT NULL, PRIMARY KEY(`media_id`))",
        "CREATE TABLE IF NOT EXISTS `face_scan` (`media_id` INTEGER NOT NULL, `analyzed_modified` INTEGER NOT NULL, `version` INTEGER NOT NULL, `face_count` INTEGER NOT NULL, PRIMARY KEY(`media_id`))",
        "CREATE TABLE IF NOT EXISTS `face` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `media_id` INTEGER NOT NULL, `person_id` INTEGER, `box_left` REAL NOT NULL, `box_top` REAL NOT NULL, `box_right` REAL NOT NULL, `box_bottom` REAL NOT NULL, `embedding` BLOB NOT NULL, `quality` REAL NOT NULL, `manual` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_face_media_id` ON `face` (`media_id`)",
        "CREATE INDEX IF NOT EXISTS `index_face_person_id` ON `face` (`person_id`)",
        "CREATE TABLE IF NOT EXISTS `person` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT, `cover_face_id` INTEGER, `hidden` INTEGER NOT NULL, `created_at` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `media_embedding` (`media_id` INTEGER NOT NULL, `analyzed_modified` INTEGER NOT NULL, `version` INTEGER NOT NULL, `vector` BLOB, PRIMARY KEY(`media_id`))",
    )
}
