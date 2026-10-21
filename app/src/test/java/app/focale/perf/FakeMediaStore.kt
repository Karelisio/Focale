package app.focale.perf

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.AbstractCursor
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import app.focale.core.database.MediaEntity
import org.robolectric.Robolectric

/**
 * Faux fournisseur MediaStore (autorité `media`) pour les tests de charge : sert la table
 * `Files` à `MediaStoreSource` avec les conventions du système (DATE_ADDED et DATE_MODIFIED en
 * secondes, DATE_TAKEN absente des captures et des images de WhatsApp, GENERATION_MODIFIED),
 * et filtre `GENERATION_MODIFIED > ?` comme la synchronisation incrémentale le demande.
 *
 * Le curseur lit les lignes à la demande, sans IPC ni `CursorWindow` : le coût mesuré est celui
 * de l'application (lecture des colonnes, comparaison, écriture dans Room), pas celui du système.
 */
class FakeMediaStore : ContentProvider() {

    @Volatile
    private var rows: List<MediaEntity> = emptyList()

    @Volatile
    private var withoutDateTaken: Set<Long> = emptySet()

    /** Version de la base MediaStore (`MediaStore.getVersion`). */
    @Volatile
    var version: String = "focale-perf-1"

    /** Remplace le contenu servi : [media] tels que l'index les décrit, [noDateTaken] sans DATE_TAKEN. */
    fun publish(media: List<MediaEntity>, noDateTaken: Set<Long>) {
        rows = media.sortedBy { it.id }
        withoutDateTaken = noDateTaken
    }

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        var selected = rows
        if (selection != null && "${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?" in selection) {
            val since = requireNotNull(selectionArgs).first().toLong()
            selected = selected.filter { it.generation > since }
        }
        return MediaCursor(projection ?: ALL_COLUMNS, selected, withoutDateTaken)
    }

    /** `MediaStore.getVersion` passe par `call`. */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle =
        Bundle().apply { putString(Intent.EXTRA_TEXT, version) }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException()

    /** Curseur en lecture seule sur des lignes en mémoire, colonnes résolues une fois. */
    private class MediaCursor(
        private val columns: Array<String>,
        private val rows: List<MediaEntity>,
        private val withoutDateTaken: Set<Long>,
    ) : AbstractCursor() {

        private val kinds = IntArray(columns.size) { COLUMN_KINDS[columns[it]] ?: error("Colonne inconnue : ${columns[it]}") }

        override fun getCount(): Int = rows.size

        override fun getColumnNames(): Array<String> = columns

        private fun value(column: Int): Any? {
            val row = rows[position]
            return when (kinds[column]) {
                ID -> row.id
                MEDIA_TYPE -> row.mediaType
                MIME -> row.mimeType
                NAME -> row.displayName
                PATH -> row.relativePath
                BUCKET_ID -> row.bucketId
                BUCKET_NAME -> row.bucketName
                DATE_TAKEN -> if (row.id in withoutDateTaken) null else row.dateTaken
                DATE_ADDED -> row.dateAdded / 1000
                DATE_MODIFIED -> row.dateModified / 1000
                SIZE -> row.size
                WIDTH -> row.width
                HEIGHT -> row.height
                ORIENTATION -> row.orientation
                DURATION -> row.duration
                VOLUME -> row.volumeName
                DATE_EXPIRES -> row.dateExpires.takeIf { it > 0 }?.div(1000)
                GENERATION -> row.generation
                TRASHED -> if (row.isTrashed) 1 else 0
                else -> error("Colonne ${columns[column]}")
            }
        }

        override fun getString(column: Int): String? = value(column)?.toString()

        override fun getLong(column: Int): Long = (value(column) as? Number)?.toLong() ?: 0L

        override fun getInt(column: Int): Int = (value(column) as? Number)?.toInt() ?: 0

        override fun getShort(column: Int): Short = getInt(column).toShort()

        override fun getFloat(column: Int): Float = (value(column) as? Number)?.toFloat() ?: 0f

        override fun getDouble(column: Int): Double = (value(column) as? Number)?.toDouble() ?: 0.0

        override fun isNull(column: Int): Boolean = value(column) == null
    }

    companion object {
        private const val ID = 0
        private const val MEDIA_TYPE = 1
        private const val MIME = 2
        private const val NAME = 3
        private const val PATH = 4
        private const val BUCKET_ID = 5
        private const val BUCKET_NAME = 6
        private const val DATE_TAKEN = 7
        private const val DATE_ADDED = 8
        private const val DATE_MODIFIED = 9
        private const val SIZE = 10
        private const val WIDTH = 11
        private const val HEIGHT = 12
        private const val ORIENTATION = 13
        private const val DURATION = 14
        private const val VOLUME = 15
        private const val DATE_EXPIRES = 16
        private const val GENERATION = 17
        private const val TRASHED = 18

        private val COLUMN_KINDS: Map<String, Int> = mapOf(
            FileColumns._ID to ID,
            FileColumns.MEDIA_TYPE to MEDIA_TYPE,
            MediaStore.MediaColumns.MIME_TYPE to MIME,
            MediaStore.MediaColumns.DISPLAY_NAME to NAME,
            MediaStore.MediaColumns.RELATIVE_PATH to PATH,
            MediaStore.MediaColumns.BUCKET_ID to BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME to BUCKET_NAME,
            MediaStore.MediaColumns.DATE_TAKEN to DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED to DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED to DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE to SIZE,
            MediaStore.MediaColumns.WIDTH to WIDTH,
            MediaStore.MediaColumns.HEIGHT to HEIGHT,
            MediaStore.MediaColumns.ORIENTATION to ORIENTATION,
            MediaStore.MediaColumns.DURATION to DURATION,
            MediaStore.MediaColumns.VOLUME_NAME to VOLUME,
            MediaStore.MediaColumns.DATE_EXPIRES to DATE_EXPIRES,
            MediaStore.MediaColumns.GENERATION_MODIFIED to GENERATION,
            MediaStore.MediaColumns.IS_TRASHED to TRASHED,
        )

        private val ALL_COLUMNS: Array<String> = COLUMN_KINDS.keys.toTypedArray()

        /** Enregistre un faux fournisseur pour l'autorité de MediaStore dans le test Robolectric courant. */
        fun install(): FakeMediaStore = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
    }
}
