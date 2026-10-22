package app.focale.core.mediastore

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import app.focale.core.database.MediaEntity
import app.focale.core.database.MediaQueryBuilder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lecture brute de MediaStore (table `Files`, images + vidéos du volume externe).
 */
@Singleton
class MediaStoreSource @Inject constructor(
    private val resolver: ContentResolver,
) {

    /**
     * Parcourt toutes les lignes modifiées depuis [sinceGeneration] (ou toutes si null).
     * Un média sans date de prise de vue dans MediaStore prend celle de [originalDates]
     * (média restauré ou copié par l'application), sinon sa date de modification.
     * Fonction `inline` : [onRow] peut appeler des fonctions suspendues (écriture par lots).
     */
    inline fun scan(sinceGeneration: Long?, originalDates: Map<Long, Long> = emptyMap(), onRow: (MediaEntity) -> Unit) {
        val selection = buildString {
            append("${FileColumns.MEDIA_TYPE} IN (${FileColumns.MEDIA_TYPE_IMAGE}, ${FileColumns.MEDIA_TYPE_VIDEO})")
            if (sinceGeneration != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                append(" AND ${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?")
            }
        }
        val args = sinceGeneration?.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.R }
            ?.let { arrayOf(it.toString()) }
        query(PROJECTION, selection, args)?.use { cursor ->
            val columns = Columns(cursor)
            while (cursor.moveToNext()) {
                onRow(columns.read(cursor, originalDates))
            }
        }
    }

    /** Identifiants de tous les médias visibles (détection des suppressions). */
    fun allIds(): LongArray {
        val selection = "${FileColumns.MEDIA_TYPE} IN (${FileColumns.MEDIA_TYPE_IMAGE}, ${FileColumns.MEDIA_TYPE_VIDEO})"
        val cursor = query(arrayOf(FileColumns._ID), selection, null) ?: return LongArray(0)
        return cursor.use {
            val ids = LongArray(it.count)
            var index = 0
            while (it.moveToNext() && index < ids.size) ids[index++] = it.getLong(0)
            ids.copyOf(index)
        }
    }

    /**
     * `DATE_TAKEN` brute des médias [ids] (au plus 500), telle que MediaStore la donne : absente (ou
     * nulle) pour ceux dont le fichier n'a pas de date de prise de vue.
     */
    fun datesTaken(ids: Collection<Long>): Map<Long, Long> {
        if (ids.isEmpty()) return emptyMap()
        val selection = "$MEDIA_SELECTION AND ${FileColumns._ID} IN (${ids.joinToString(",") { "?" }})"
        val cursor = query(arrayOf(FileColumns._ID, MediaStore.MediaColumns.DATE_TAKEN), selection, ids.map(Long::toString).toTypedArray())
            ?: return emptyMap()
        return cursor.use {
            buildMap {
                while (it.moveToNext()) if (!it.isNull(1)) put(it.getLong(0), it.getLong(1))
            }
        }
    }

    /** Vrai si l'image ou la vidéo [id] est visible avec l'accès actuel (complet, ou sélection). */
    fun isVisible(id: Long): Boolean =
        query(arrayOf(FileColumns._ID), "$MEDIA_SELECTION AND ${FileColumns._ID} = ?", arrayOf(id.toString()))
            ?.use { it.count > 0 } == true

    /** Identifiant de l'image ou de la vidéo enregistrée à l'un des chemins [paths], si elle est visible. */
    @Suppress("DEPRECATION") // DATA : seule correspondance possible avec un chemin `file://`.
    fun idOfPath(paths: List<String>): Long? {
        if (paths.isEmpty()) return null
        val selection = "$MEDIA_SELECTION AND ${MediaStore.MediaColumns.DATA} IN (${paths.joinToString(",") { "?" }})"
        return query(arrayOf(FileColumns._ID), selection, paths.toTypedArray())
            ?.use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    /** Version de la base MediaStore : un changement impose une resynchronisation complète. */
    fun version(context: android.content.Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.getVersion(context, MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.getVersion(context)
        }

    @PublishedApi
    internal fun query(projection: Array<String>, selection: String, args: Array<String>?): Cursor? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bundle = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                // Inclut la corbeille système pour la gérer depuis l'index.
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            }
            resolver.query(FILES_URI, projection, bundle, null)
        } else {
            resolver.query(FILES_URI, projection, selection, args, null)
        }

    /** Index des colonnes résolus une fois par curseur. */
    @PublishedApi
    internal class Columns(cursor: Cursor) {
        private val id = cursor.getColumnIndexOrThrow(FileColumns._ID)
        private val mediaType = cursor.getColumnIndexOrThrow(FileColumns.MEDIA_TYPE)
        private val mime = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        private val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        private val path = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
        private val bucketId = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
        private val bucketName = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        private val dateTaken = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
        private val dateAdded = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        private val dateModified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        private val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        private val width = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
        private val height = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
        private val orientation = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.ORIENTATION)
        private val duration = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
        private val volume = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME)
        private val dateExpires = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_EXPIRES)
        private val generation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_MODIFIED)
        } else {
            -1
        }
        private val trashed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)
        } else {
            -1
        }

        fun read(c: Cursor, originalDates: Map<Long, Long> = emptyMap()): MediaEntity {
            val mediaId = c.getLong(id)
            val type = c.getInt(mediaType)
            val mimeType = c.getString(mime).orEmpty()
            val displayName = c.getString(name).orEmpty()
            val relativePath = c.getString(path).orEmpty()
            val modifiedSeconds = c.getLong(dateModified)
            val addedSeconds = c.getLong(dateAdded)
            val taken = c.getLong(dateTaken)
            val sortDate = when {
                taken > 0 -> taken
                else -> originalDates[mediaId] ?: if (modifiedSeconds > 0) modifiedSeconds * 1000 else addedSeconds * 1000
            }
            return MediaEntity(
                id = mediaId,
                mediaType = if (type == FileColumns.MEDIA_TYPE_VIDEO) {
                    MediaQueryBuilder.MEDIA_TYPE_VIDEO
                } else {
                    MediaQueryBuilder.MEDIA_TYPE_IMAGE
                },
                mimeType = mimeType,
                displayName = displayName,
                relativePath = relativePath,
                bucketId = c.getLong(bucketId),
                bucketName = c.getString(bucketName) ?: relativePath.trimEnd('/').substringAfterLast('/'),
                dateTaken = sortDate,
                dateAdded = addedSeconds * 1000,
                dateModified = modifiedSeconds * 1000,
                generation = if (generation >= 0) c.getLong(generation) else 0L,
                size = c.getLong(size),
                width = c.getInt(width),
                height = c.getInt(height),
                orientation = c.getInt(orientation),
                duration = c.getLong(duration),
                volumeName = c.getString(volume) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY,
                isScreenshot = isScreenshot(relativePath, displayName),
                isGif = mimeType.equals("image/gif", ignoreCase = true),
                isTrashed = trashed >= 0 && c.getInt(trashed) == 1,
                dateExpires = c.getLong(dateExpires) * 1000,
            )
        }
    }

    companion object {
        val FILES_URI: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

        private const val MEDIA_SELECTION =
            "${FileColumns.MEDIA_TYPE} IN (${FileColumns.MEDIA_TYPE_IMAGE}, ${FileColumns.MEDIA_TYPE_VIDEO})"

        @PublishedApi
        internal val PROJECTION: Array<String> = buildList {
            add(FileColumns._ID)
            add(FileColumns.MEDIA_TYPE)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.RELATIVE_PATH)
            add(MediaStore.MediaColumns.BUCKET_ID)
            add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            add(MediaStore.MediaColumns.DATE_TAKEN)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.ORIENTATION)
            add(MediaStore.MediaColumns.DURATION)
            add(MediaStore.MediaColumns.VOLUME_NAME)
            add(MediaStore.MediaColumns.DATE_EXPIRES)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.MediaColumns.GENERATION_MODIFIED)
                add(MediaStore.MediaColumns.IS_TRASHED)
            }
        }.toTypedArray()

        /** URI de contenu d'un média à partir de son identifiant et de son type. */
        fun contentUri(id: Long, isVideo: Boolean): Uri = ContentUris.withAppendedId(
            if (isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            },
            id,
        )

        /** Heuristique : dossier ou nom de fichier typique des captures d'écran. */
        fun isScreenshot(relativePath: String, displayName: String): Boolean =
            relativePath.contains("screenshot", ignoreCase = true) ||
                displayName.startsWith("screenshot", ignoreCase = true)
    }
}

/** Limites SQLite partagées par la couche MediaStore. */
object MediaRepositoryLimits {
    /** Nombre maximal de paramètres par requête (SQLite ancien : 999). */
    const val MAX_SQL_ARGS = 500
}
