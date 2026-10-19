package app.focale.core.image

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi

/** Photos Ultra HDR : image SDR accompagnée d'une gainmap, affichable en HDR (Android 14+). */
object UltraHdr {

    private val candidates = setOf("image/jpeg", "image/heic", "image/heif", "image/avif")

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** Formats pouvant porter une gainmap. */
    fun isCandidate(mimeType: String): Boolean = mimeType.lowercase() in candidates

    /**
     * Vrai si [uri] porte une gainmap. Le décodage se fait au seizième de la taille :
     * quelques millisecondes, gainmap comprise.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun hasGainmap(resolver: ContentResolver, uri: Uri): Boolean = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            val bitmap = BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = SAMPLE_SIZE })
            bitmap?.hasGainmap().also { bitmap?.recycle() }
        }
    }.getOrNull() == true

    private const val SAMPLE_SIZE = 16
}
