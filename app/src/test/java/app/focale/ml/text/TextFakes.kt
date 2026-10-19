package app.focale.ml.text

import android.net.Uri
import app.focale.core.database.MediaEntity
import app.focale.core.database.MediaTextEntity
import app.focale.core.database.TextDao
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaType

/** Photo de test (l'URI exige Robolectric). */
fun photo(id: Long, modified: Long = 1_000L, screenshot: Boolean = false) = MediaItem(
    id = id,
    uri = Uri.parse("content://media/external/images/media/$id"),
    type = MediaType.IMAGE,
    mimeType = "image/jpeg",
    displayName = "IMG_$id.jpg",
    relativePath = "DCIM/Camera/",
    bucketId = 1,
    bucketName = "Camera",
    dateTaken = modified,
    dateModified = modified,
    size = 1,
    width = 4000,
    height = 3000,
    orientation = 0,
    durationMs = 0,
    isGif = false,
    isScreenshot = screenshot,
)

/** Lecteur scripté : texte, null (image illisible) ou exception, par identifiant. */
class FakeTextReader(private val results: Map<Long, Any?>) : PhotoTextReader {
    val reads = mutableListOf<Long>()
    var released = 0

    override suspend fun read(item: MediaItem): String? {
        reads += item.id
        return when (val result = results[item.id]) {
            is Exception -> throw result
            else -> result as String?
        }
    }

    override fun release() {
        released++
    }
}

/** Table `media_text` en mémoire. */
class FakeTextDao(var pendingRows: List<MediaEntity> = emptyList()) : TextDao {
    val rows = linkedMapOf<Long, MediaTextEntity>()
    var upserts = 0

    override suspend fun pending(version: Int, limit: Int): List<MediaEntity> = pendingRows.take(limit)

    override suspend fun upsert(texts: List<MediaTextEntity>) {
        upserts++
        texts.forEach { rows[it.mediaId] = it }
    }

    override suspend fun get(mediaId: Long): MediaTextEntity? = rows[mediaId]
}
