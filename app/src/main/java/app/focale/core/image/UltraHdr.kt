package app.focale.core.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Gainmap
import android.net.Uri
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi

/** Photos Ultra HDR : image SDR accompagnée d'une gainmap, affichable en HDR (Android 14+). */
object UltraHdr {

    private val candidates = setOf("image/jpeg", "image/heic", "image/heif", "image/avif")

    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** Formats pouvant porter une gainmap. */
    fun isCandidate(mimeType: String): Boolean = mimeType.lowercase() in candidates

    /**
     * Vrai si [uri] porte une gainmap. Le décodage se fait au seizième de la taille :
     * quelques millisecondes, gainmap comprise.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun hasGainmap(resolver: ContentResolver, uri: Uri): Boolean = params(resolver, uri) != null

    /** Paramètres de la gainmap de [uri], lus sur le même décodage au seizième ; null sans gainmap. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun params(resolver: ContentResolver, uri: Uri): GainmapParams? = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            val bitmap = BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = SAMPLE_SIZE })
            bitmap?.gainmap?.let(::params).also { bitmap?.recycle() }
        }
    }.getOrNull()

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun params(gainmap: Gainmap): GainmapParams = GainmapParams(
        ratioMin = gainmap.ratioMin.toList(),
        ratioMax = gainmap.ratioMax.toList(),
        gamma = gainmap.gamma.toList(),
        epsilonSdr = gainmap.epsilonSdr.toList(),
        epsilonHdr = gainmap.epsilonHdr.toList(),
        displayRatioHdr = gainmap.displayRatioForFullHdr,
        displayRatioSdr = gainmap.minDisplayRatioForHdrTransition,
        channels = if (gainmap.gainmapContents.config == Bitmap.Config.ALPHA_8) 1 else 3,
    )

    /** Gainmap de contenu [contents] (ALPHA_8 pour un canal, ARGB_8888 pour trois) et de paramètres [params]. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun gainmap(contents: Bitmap, params: GainmapParams): Gainmap = Gainmap(contents).apply {
        setRatioMin(params.ratioMin[0], params.ratioMin[1], params.ratioMin[2])
        setRatioMax(params.ratioMax[0], params.ratioMax[1], params.ratioMax[2])
        setGamma(params.gamma[0], params.gamma[1], params.gamma[2])
        setEpsilonSdr(params.epsilonSdr[0], params.epsilonSdr[1], params.epsilonSdr[2])
        setEpsilonHdr(params.epsilonHdr[0], params.epsilonHdr[1], params.epsilonHdr[2])
        displayRatioForFullHdr = params.displayRatioHdr
        minDisplayRatioForHdrTransition = params.displayRatioSdr
    }

    private const val SAMPLE_SIZE = 16
}

/**
 * Paramètres d'une gainmap Ultra HDR, ceux de [Gainmap], par canal rouge, vert, bleu. Une
 * valeur stockée v (de 0 à 1) vaut un gain G tel que ln G = ln [ratioMin] + (ln [ratioMax] −
 * ln [ratioMin]) · v^(1 / [gamma]) ; l'image HDR est (SDR + [epsilonSdr]) · G^p − [epsilonHdr],
 * p allant de 0 à 1 quand la marge HDR de l'écran passe de [displayRatioSdr] à [displayRatioHdr].
 *
 * @param channels 1 (gainmap en niveaux de gris, ALPHA_8) ou 3.
 */
data class GainmapParams(
    val ratioMin: List<Float>,
    val ratioMax: List<Float>,
    val gamma: List<Float>,
    val epsilonSdr: List<Float>,
    val epsilonHdr: List<Float>,
    val displayRatioHdr: Float,
    val displayRatioSdr: Float,
    val channels: Int,
)
