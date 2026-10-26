package app.focale.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.focale.ml.text.RecognizedText

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
     * journal de l'import dans le coffre (original encore dans la galerie, copie en cours) ;
     * corbeille du coffre.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            CHANGES_V5.forEach(db::execSQL)
        }
    }

    /** Version 6 : index composés de la timeline, des dossiers et des filtres de type. */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            CHANGES_V6.forEach(db::execSQL)
        }
    }

    /**
     * Version 7 : index des étiquettes (`label`, `confidence`, `media_id`), qui sert seul la
     * recherche par étiquette, le comptage des étiquettes et la couverture de chacune.
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            CHANGES_V7.forEach(db::execSQL)
        }
    }

    /**
     * Version 8 : comptes des étiquettes (`label_count`) et mots des noms de fichiers (`media_name`,
     * FTS4), calculés pour les médias et les étiquettes déjà là ; recalculables, sans sauvegarde.
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            CHANGES_V8.forEach(db::execSQL)
            // Mots des noms calculés en Kotlin, comme à la synchronisation (MediaNames.document).
            db.compileStatement("INSERT INTO `media_name` (`rowid`, `words`) VALUES (?, ?)").use { insert ->
                db.query("SELECT id, display_name FROM media").use { cursor ->
                    while (cursor.moveToNext()) {
                        insert.bindLong(1, cursor.getLong(0))
                        insert.bindString(2, MediaNames.document(cursor.getString(1)))
                        insert.executeInsert()
                    }
                }
            }
        }
    }

    /**
     * Version 9 : index plein texte du texte reconnu (`media_text_fts`, FTS4 à contenu externe, et
     * ses déclencheurs), comptes des clés de lieux (`place_count`), mots des noms en attente
     * (`media_name_pending`, vide : ceux des médias déjà là sont écrits depuis la version 8) ;
     * calculés pour les textes et les lieux déjà là, recalculables, sans sauvegarde. Les clés des
     * textes déjà lus gagnent d'abord leurs lettres et chiffres collés, séparés
     * ([RecognizedText.searchKey]), sans nouvelle lecture des photos.
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            refreshTextKeys(db)
            CHANGES_V9.forEach(db::execSQL)
        }
    }

    /**
     * Recalcule en Kotlin la clé de recherche des textes déjà lus ([RecognizedText.searchKey]), par
     * tranches dans l'ordre des identifiants ; seules celles qui changent sont réécrites. À faire
     * avant de créer l'index du texte, que ses déclencheurs tiendraient sinon à jour ligne à ligne.
     * Renvoie le nombre de clés réécrites.
     */
    fun refreshTextKeys(db: SupportSQLiteDatabase): Int {
        var updated = 0
        var after = Long.MIN_VALUE
        db.compileStatement("UPDATE media_text SET text_key = ? WHERE media_id = ?").use { update ->
            while (true) {
                val rows = db.query(
                    "SELECT media_id, text, text_key FROM media_text WHERE media_id > ? AND text != '' ORDER BY media_id LIMIT $TEXT_KEYS_BATCH",
                    arrayOf<Any?>(after),
                ).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(Triple(cursor.getLong(0), cursor.getString(1), cursor.getString(2))) }
                }
                for ((id, text, oldKey) in rows) {
                    val key = RecognizedText.searchKey(text)
                    if (key == oldKey) continue
                    update.bindString(1, key)
                    update.bindLong(2, id)
                    update.executeUpdateDelete()
                    updated++
                }
                if (rows.size < TEXT_KEYS_BATCH) return updated
                after = rows.last().first
            }
        }
    }

    /** Textes relus par tranche ([refreshTextKeys]) : quelques Mo au plus pour des captures très bavardes. */
    private const val TEXT_KEYS_BATCH = 500

    val ALL: Array<Migration> =
        arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)

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
        "ALTER TABLE `vault_item` ADD COLUMN `deleted_at` INTEGER",
    )

    private val CHANGES_V6 = listOf(
        // Timeline, dossiers et filtres dans l'ordre de la grille, sans trier toute la bibliothèque
        // (docs/PERFORMANCE.md).
        "DROP INDEX IF EXISTS `index_media_is_trashed`",
        "DROP INDEX IF EXISTS `index_media_bucket_id`",
        "DROP INDEX IF EXISTS `index_media_media_type`",
        "CREATE INDEX IF NOT EXISTS `index_media_is_trashed_date_taken` ON `media` (`is_trashed`, `date_taken`)",
        "CREATE INDEX IF NOT EXISTS `index_media_bucket_id_is_trashed_date_taken` ON `media` (`bucket_id`, `is_trashed`, `date_taken`)",
        "CREATE INDEX IF NOT EXISTS `index_media_media_type_is_trashed_date_taken` ON `media` (`media_type`, `is_trashed`, `date_taken`)",
    )

    private val CHANGES_V7 = listOf(
        // Étiquettes de l'onglet Recherche par l'index seul (docs/PERFORMANCE.md).
        "DROP INDEX IF EXISTS `index_media_label_label`",
        "CREATE INDEX IF NOT EXISTS `index_media_label_label_confidence_media_id` ON `media_label` (`label`, `confidence`, `media_id`)",
    )

    private val CHANGES_V8 = listOf(
        // Étiquettes de l'onglet Recherche et noms de fichiers sans parcourir leurs tables (docs/PERFORMANCE.md).
        "CREATE TABLE IF NOT EXISTS `label_count` (`label` TEXT NOT NULL, `count` INTEGER NOT NULL, PRIMARY KEY(`label`))",
        "INSERT INTO `label_count` (`label`, `count`) SELECT l.label, COUNT(*) FROM media_label l " +
            "WHERE +l.media_id IN (SELECT id FROM media WHERE is_trashed = 0) GROUP BY l.label",
        "CREATE VIRTUAL TABLE IF NOT EXISTS `media_name` USING FTS4(`words` TEXT NOT NULL)",
    )

    private val CHANGES_V9 = listOf(
        // Texte reconnu, lieux et noms de la recherche par mot sans parcourir leurs tables (docs/PERFORMANCE.md).
        "CREATE VIRTUAL TABLE IF NOT EXISTS `media_text_fts` USING FTS4(`text_key` TEXT NOT NULL, " +
            "tokenize=unicode61 `remove_diacritics=1`, content=`media_text`)",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_media_text_fts_BEFORE_UPDATE BEFORE UPDATE ON `media_text` " +
            "BEGIN DELETE FROM `media_text_fts` WHERE `docid`=OLD.`rowid`; END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_media_text_fts_BEFORE_DELETE BEFORE DELETE ON `media_text` " +
            "BEGIN DELETE FROM `media_text_fts` WHERE `docid`=OLD.`rowid`; END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_media_text_fts_AFTER_UPDATE AFTER UPDATE ON `media_text` " +
            "BEGIN INSERT INTO `media_text_fts`(`docid`, `text_key`) VALUES (NEW.`rowid`, NEW.`text_key`); END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_media_text_fts_AFTER_INSERT AFTER INSERT ON `media_text` " +
            "BEGIN INSERT INTO `media_text_fts`(`docid`, `text_key`) VALUES (NEW.`rowid`, NEW.`text_key`); END",
        // Textes déjà lus : l'index se construit depuis sa table de contenu.
        "INSERT INTO `media_text_fts`(`media_text_fts`) VALUES ('rebuild')",
        "CREATE TABLE IF NOT EXISTS `place_count` (`place_key` TEXT NOT NULL, `count` INTEGER NOT NULL, PRIMARY KEY(`place_key`))",
        "INSERT INTO `place_count` (`place_key`, `count`) SELECT place_key, COUNT(*) FROM media_analysis " +
            "WHERE place_key IS NOT NULL GROUP BY place_key",
        "CREATE TABLE IF NOT EXISTS `media_name_pending` (`media_id` INTEGER NOT NULL, PRIMARY KEY(`media_id`))",
    )
}
