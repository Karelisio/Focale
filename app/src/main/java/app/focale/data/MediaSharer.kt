package app.focale.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.focale.core.di.IoDispatcher
import app.focale.core.model.MediaItem
import app.focale.core.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Prépare les intentions de partage. Si l'option est activée, les métadonnées EXIF /
 * GPS sont retirées de copies temporaires partagées à la place des originaux.
 */
@Singleton
class MediaSharer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val metadataStripper: MetadataStripper,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun shareIntent(items: List<MediaItem>): Intent? = withContext(io) {
        if (items.isEmpty()) return@withContext null
        val uris: List<Uri> = if (settings.current.value.stripMetadataOnShare) {
            metadataStripper.prepareForSharing(items)
        } else {
            items.map { it.uri }
        }
        val mimeType = commonMimeType(items)
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.type = mimeType
        // ClipData : nécessaire pour transmettre la permission de lecture de chaque URI.
        send.clipData = ClipData.newUri(context.contentResolver, null, uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun commonMimeType(items: List<MediaItem>): String {
        val types = items.map { it.mimeType.ifBlank { if (it.isVideo) "video/*" else "image/*" } }.distinct()
        if (types.size == 1) return types.first()
        val families = types.map { it.substringBefore('/') }.distinct()
        return if (families.size == 1) "${families.first()}/*" else "*/*"
    }
}
