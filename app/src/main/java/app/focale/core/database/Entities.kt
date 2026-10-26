package app.focale.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Miroir d'une ligne MediaStore (images et vidéos).
 *
 * La clé primaire est le `_ID` MediaStore : il est unique pour toute la table `Files`
 * du volume externe, images et vidéos confondues.
 *
 * Index composés (docs/PERFORMANCE.md) : toute requête écarte la corbeille (`is_trashed = 0`)
 * et trie par date ; (`is_trashed`, `date_taken`) sert la timeline dans son ordre, sans tri
 * temporaire, ainsi que ses compteurs et son histogramme ; (`bucket_id`, …) et
 * (`media_type`, …) servent de même la grille d'un dossier (et la couverture de chaque
 * dossier), les filtres Photos et Vidéos et les files de l'analyse. Un index sur `is_trashed`
 * seul faisait trier toute la bibliothèque à chaque page.
 */
@Entity(
    tableName = "media",
    indices = [
        Index("date_taken"),
        Index("bucket_id", "is_trashed", "date_taken"),
        Index("media_type", "is_trashed", "date_taken"),
        Index("is_trashed", "date_taken"),
    ],
)
data class MediaEntity(
    @PrimaryKey val id: Long,
    /** Valeur MediaStore : 1 = image, 3 = vidéo. */
    @ColumnInfo(name = "media_type") val mediaType: Int,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "bucket_id") val bucketId: Long,
    @ColumnInfo(name = "bucket_name") val bucketName: String,
    /** Date de tri (ms) : DATE_TAKEN, sinon DATE_MODIFIED, sinon DATE_ADDED. */
    @ColumnInfo(name = "date_taken") val dateTaken: Long,
    @ColumnInfo(name = "date_added") val dateAdded: Long,
    @ColumnInfo(name = "date_modified") val dateModified: Long,
    /** GENERATION_MODIFIED (API 30+), 0 sinon. */
    val generation: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val duration: Long,
    @ColumnInfo(name = "volume_name") val volumeName: String,
    @ColumnInfo(name = "is_screenshot") val isScreenshot: Boolean,
    @ColumnInfo(name = "is_gif") val isGif: Boolean,
    /** Média dans la corbeille système (API 30+). */
    @ColumnInfo(name = "is_trashed") val isTrashed: Boolean,
    /** Date de suppression définitive programmée par le système (ms), 0 si aucune. */
    @ColumnInfo(name = "date_expires") val dateExpires: Long,
)

/**
 * Date d'origine d'un média que l'application a restauré ou copié, quand MediaStore n'en a
 * trouvé aucune : ni date de prise de vue dans le fichier, ni date de modification fixée à la
 * bonne valeur (Android 10). L'index la retient à la place de la date du jour.
 */
@Entity(tableName = "media_date")
data class MediaDateEntity(
    @PrimaryKey @ColumnInfo(name = "media_id") val mediaId: Long,
    @ColumnInfo(name = "date_taken") val dateTaken: Long,
)

/** Projection légère utilisée pour la synchronisation incrémentale. */
data class MediaSyncKey(
    val id: Long,
    @ColumnInfo(name = "date_modified") val dateModified: Long,
    val generation: Long,
    @ColumnInfo(name = "is_trashed") val isTrashed: Boolean,
)

/** Ligne d'histogramme : une période et le nombre de médias qu'elle contient. */
data class DateBucketRow(
    @ColumnInfo(name = "period_start") val periodStart: Long,
    val count: Int,
)

/** Dates extrêmes des médias d'une requête ([MediaQueryBuilder.dateRange]), nulles sans média. */
data class DateRangeRow(
    val first: Long?,
    val last: Long?,
)

/** Album dossier agrégé depuis l'index. */
data class BucketRow(
    @ColumnInfo(name = "bucket_id") val bucketId: Long,
    @ColumnInfo(name = "bucket_name") val bucketName: String,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    val count: Int,
    @ColumnInfo(name = "latest") val latest: Long,
    @ColumnInfo(name = "cover_id") val coverId: Long,
)

@Entity(tableName = "favorite")
data class FavoriteEntity(
    @PrimaryKey @ColumnInfo(name = "media_id") val mediaId: Long,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/** Album virtuel créé par l'utilisateur. */
@Entity(tableName = "album")
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "cover_media_id") val coverMediaId: Long? = null,
)

@Entity(
    tableName = "album_media",
    primaryKeys = ["album_id", "media_id"],
    indices = [Index("media_id")],
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["album_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AlbumMediaEntity(
    @ColumnInfo(name = "album_id") val albumId: Long,
    @ColumnInfo(name = "media_id") val mediaId: Long,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/** Résultat de l'analyse d'un média (ML, empreinte, netteté, GPS). */
@Entity(tableName = "media_analysis", indices = [Index("place_key"), Index("country"), Index("blur_score")])
data class MediaAnalysisEntity(
    @PrimaryKey @ColumnInfo(name = "media_id") val mediaId: Long,
    /** `date_modified` du média au moment de l'analyse : une édition relance l'analyse. */
    @ColumnInfo(name = "analyzed_modified") val analyzedModified: Long,
    /** Version de l'algorithme d'analyse, pour pouvoir tout recalculer après une évolution. */
    val version: Int,
    /** dHash 64 bits, null si non calculable. */
    val phash: Long?,
    /** Variance du Laplacien (plus c'est bas, plus c'est flou). */
    @ColumnInfo(name = "blur_score") val blurScore: Float?,
    val latitude: Double?,
    val longitude: Double?,
    /** Ville la plus proche (géocodage hors ligne). */
    val place: String?,
    /**
     * Clé de recherche du lieu : ses noms normalisés (minuscules, sans accents), celui de
     * GeoNames d'abord puis ses formes française et anglaise (« lisbon | lisbonne »,
     * `PlaceTranslations.searchKey`).
     */
    @ColumnInfo(name = "place_key") val placeKey: String?,
    /** Code pays ISO 3166-1 alpha-2. */
    val country: String?,
)

/**
 * Étiquette ML Kit (libellé anglais canonique) attachée à un média. Index (`label`, `confidence`,
 * `media_id`) : recherche par étiquette et comptage des étiquettes par l'index seul, photo la
 * plus sûre de chaque étiquette en tête de la sienne (docs/PERFORMANCE.md).
 */
@Entity(
    tableName = "media_label",
    primaryKeys = ["media_id", "label"],
    indices = [Index("label", "confidence", "media_id")],
)
data class MediaLabelEntity(
    @ColumnInfo(name = "media_id") val mediaId: Long,
    val label: String,
    val confidence: Float,
)

/**
 * Nombre de médias hors corbeille (présents dans `media`) qui portent chaque étiquette : les
 * étiquettes de l'onglet Recherche sans relire les 150 000 lignes de `media_label`. Tenu à jour
 * dans la transaction de chaque écriture des médias ou des étiquettes ([LabelCountQueries]) ;
 * recalculable, il n'entre pas dans la sauvegarde des données (docs/PERFORMANCE.md).
 */
@Entity(tableName = "label_count")
data class LabelCountEntity(
    @PrimaryKey val label: String,
    val count: Int,
)

/**
 * Mots des noms de fichiers (index plein texte FTS4) : chaque suite de lettres du nom et ses
 * suffixes ([MediaNames.document]), d'où la recherche d'un mot dans les noms par l'index au lieu
 * d'un `LIKE` sur toute la table `media`. Tenu à jour avec l'index des médias ([MediaDao.upsert]).
 */
@Fts4
@Entity(tableName = "media_name")
data class MediaNameEntity(
    /** `_ID` MediaStore du média. */
    @PrimaryKey @ColumnInfo(name = "rowid") val mediaId: Long,
    val words: String,
)

/**
 * Média dont les mots du nom restent à écrire dans `media_name` : la première indexation les laisse
 * en attente ([MediaDao.upsertNamesLater]) et les écrit ensuite en arrière-plan
 * ([MediaDao.indexPendingNames]). La recherche par nom compare ces médias par `LIKE`, sans l'index
 * ([MediaNames]) : elle reste exacte entre-temps. Recalculable, sans sauvegarde.
 */
@Entity(tableName = "media_name_pending")
data class MediaNamePendingEntity(
    @PrimaryKey @ColumnInfo(name = "media_id") val mediaId: Long,
)

/**
 * Nombre d'analyses (`media_analysis`) qui portent chaque clé de lieu, corbeille et médias absents
 * compris : la recherche d'un mot parmi les lieux lit ces quelques dizaines de clés distinctes, puis
 * les médias des clés trouvées par l'index de `place_key`, au lieu d'un `LIKE` sur chaque analyse.
 * Tenu à jour comme `label_count` ([PlaceCountQueries]) ; recalculable, sans sauvegarde
 * (docs/PERFORMANCE.md).
 */
@Entity(tableName = "place_count")
data class PlaceCountEntity(
    @PrimaryKey @ColumnInfo(name = "place_key") val placeKey: String,
    val count: Int,
)

/** Élément de la corbeille applicative (Android 10 uniquement). */
@Entity(tableName = "trash_item")
data class TrashItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "media_type") val mediaType: Int,
    @ColumnInfo(name = "date_taken") val dateTaken: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val duration: Long,
    /** Nom du fichier dans le stockage privé de l'application. */
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "was_favorite") val wasFavorite: Boolean,
    @ColumnInfo(name = "trashed_at") val trashedAt: Long,
)

/**
 * Album du coffre-fort : de la métadonnée seulement, ses éléments restant chiffrés un par un.
 * Un album-dossier garde son dossier d'origine, pour en proposer les médias arrivés depuis.
 */
@Entity(tableName = "vault_album")
data class VaultAlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** `BUCKET_ID` MediaStore du dossier d'origine, null hors album-dossier. */
    @ColumnInfo(name = "source_bucket_id") val sourceBucketId: Long? = null,
    /** `RELATIVE_PATH` du dossier d'origine. */
    @ColumnInfo(name = "source_path") val sourcePath: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** Dernier import depuis le dossier d'origine (ms), null si aucun. */
    @ColumnInfo(name = "last_import_at") val lastImportAt: Long? = null,
)

/**
 * Média déplacé dans le coffre-fort (fichier chiffré dans le stockage privé).
 *
 * Journal de l'import : tant que l'original n'a pas quitté MediaStore, [sourceMediaId] le
 * désigne. Une copie faite par un import en cours ([importing]) reste cachée ; elle entre dans le
 * coffre une fois l'original supprimé, ou est effacée s'il reste dans la galerie (refus). Une
 * copie visible dont l'original est encore là (import interrompu) est signalée dans le coffre.
 */
@Entity(
    tableName = "vault_item",
    indices = [Index("album_id"), Index("source_media_id")],
    foreignKeys = [
        ForeignKey(
            entity = VaultAlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["album_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class VaultItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "media_type") val mediaType: Int,
    @ColumnInfo(name = "original_path") val originalPath: String,
    @ColumnInfo(name = "date_taken") val dateTaken: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val duration: Long,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "thumb_file_name") val thumbFileName: String?,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    /** Album du coffre, null si l'élément n'est rangé dans aucun. */
    @ColumnInfo(name = "album_id") val albumId: Long? = null,
    /** Original (`_ID` MediaStore) peut-être encore dans la galerie, null une fois supprimé. */
    @ColumnInfo(name = "source_media_id") val sourceMediaId: Long? = null,
    /** Copie d'un import inachevé, cachée jusqu'à la suppression de son original. */
    @ColumnInfo(name = "importing", defaultValue = "0") val importing: Boolean = false,
    /** Mise à la corbeille du coffre (ms), null hors corbeille ; effacé 30 jours après. */
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    @ColumnInfo(name = "searched_at") val searchedAt: Long,
)
