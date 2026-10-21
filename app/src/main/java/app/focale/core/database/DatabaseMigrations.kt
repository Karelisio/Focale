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

    /** Version 3 : personnes épinglées et paires refusées des suggestions de fusion. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            CHANGES_V3.forEach(db::execSQL)
        }
    }

    /** Version 4 : albums du coffre, auxquels un élément du coffre peut appartenir. */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            CHANGES_V4.forEach(db::execSQL)
        }
    }

    /**
     * Version 5 : dates d'origine des médias restaurés ou copiés que MediaStore n'a pas retrouvées ;
     * journal de l'import dans le coffre (original encore dans la galerie, copie en cours).
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            CHANGES_V5.forEach(db::execSQL)
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

    private val CREATE_V2 = listOf(
        "CREATE TABLE IF NOT EXISTS `media_text` (`media_id` INTEGER NOT NULL, `analyzed_modified` INTEGER NOT NULL, `version` INTEGER NOT NULL, `text` TEXT NOT NULL, `text_key` TEXT NOT NULL, PRIMARY KEY(`media_id`))",
        "CREATE TABLE IF NOT EXISTS `face_scan` (`media_id` INTEGER NOT NULL, `analyzed_modified` INTEGER NOT NULL, `version` INTEGER NOT NULL, `face_count` INTEGER NOT NULL, PRIMARY KEY(`media_id`))",
        "CREATE TABLE IF NOT EXISTS `face` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `media_id` INTEGER NOT NULL, `person_id` INTEGER, `box_left` REAL NOT NULL, `box_top` REAL NOT NULL, `box_right` REAL NOT NULL, `box_bottom` REAL NOT NULL, `embedding` BLOB NOT NULL, `quality` REAL NOT NULL, `manual` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_face_media_id` ON `face` (`media_id`)",
        "CREATE INDEX IF NOT EXISTS `index_face_person_id` ON `face` (`person_id`)",
        "CREATE TABLE IF NOT EXISTS `person` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT, `cover_face_id` INTEGER, `hidden` INTEGER NOT NULL, `created_at` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `media_embedding` (`media_id` INTEGER NOT NULL, `analyzed_modified` INTEGER NOT NULL, `version` INTEGER NOT NULL, `vector` BLOB, PRIMARY KEY(`media_id`))",
    )

    private val CHANGES_V3 = listOf(
        "ALTER TABLE `person` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0",
        "CREATE TABLE IF NOT EXISTS `person_refusal` (`person_low` INTEGER NOT NULL, `person_high` INTEGER NOT NULL, PRIMARY KEY(`person_low`, `person_high`), FOREIGN KEY(`person_low`) REFERENCES `person`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`person_high`) REFERENCES `person`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_person_refusal_person_high` ON `person_refusal` (`person_high`)",
    )

    private val CHANGES_V4 = listOf(
        "CREATE TABLE IF NOT EXISTS `vault_album` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `source_bucket_id` INTEGER, `source_path` TEXT, `created_at` INTEGER NOT NULL, `last_import_at` INTEGER)",
        // Colonne ajoutée en place, avec la clé étrangère du schéma exporté : les éléments
        // existants restent hors album, la table n'est pas recopiée.
        "ALTER TABLE `vault_item` ADD COLUMN `album_id` INTEGER REFERENCES `vault_album`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL",
        "CREATE INDEX IF NOT EXISTS `index_vault_item_album_id` ON `vault_item` (`album_id`)",
    )

    private val CHANGES_V5 = listOf(
        "CREATE TABLE IF NOT EXISTS `media_date` (`media_id` INTEGER NOT NULL, `date_taken` INTEGER NOT NULL, PRIMARY KEY(`media_id`))",
        "ALTER TABLE `vault_item` ADD COLUMN `source_media_id` INTEGER",
        "ALTER TABLE `vault_item` ADD COLUMN `importing` INTEGER NOT NULL DEFAULT 0",
        "CREATE INDEX IF NOT EXISTS `index_vault_item_source_media_id` ON `vault_item` (`source_media_id`)",
    )
}
