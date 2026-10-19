package app.focale.core.image

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import app.focale.core.di.DefaultDispatcher
import app.focale.core.model.MediaItem
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.materialkolor.ktx.themeColorOrNull
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Extrait la couleur dominante d'une photo (quantification Celebi + score Material
 * Color Utilities, via MaterialKolor) à partir d'une miniature de 112 px.
 * Les résultats sont mis en cache par média et par date de modification.
 */
@Singleton
class SeedColorExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    private val cache = LruCache<String, Int>(512)

    suspend fun seedFor(item: MediaItem): Color? = seedFor(item.thumbnail())

    suspend fun seedFor(data: Any, cacheKey: String = data.toString()): Color? {
        cache.get(cacheKey)?.let { return Color(it) }
        val request = ImageRequest.Builder(context)
            .data(data)
            .size(SAMPLE_SIZE)
            .allowHardware(false)
            .build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
        val bitmap = (result.image as? BitmapImage)?.bitmap ?: result.image.toBitmap()
        val color = withContext(dispatcher) {
            val software = if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }
            software.asImageBitmap().themeColorOrNull()
        }
        color?.let { cache.put(cacheKey, it.toArgb()) }
        return color
    }

    private companion object {
        const val SAMPLE_SIZE = 112
    }
}
