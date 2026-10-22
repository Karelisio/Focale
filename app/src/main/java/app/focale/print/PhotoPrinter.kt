package app.focale.print

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Log
import app.focale.R
import app.focale.core.model.MediaItem

/**
 * Ouvre l'aperçu d'impression d'Android pour des photos de la galerie (jamais du coffre : leur
 * contenu déchiffré partirait vers le spouleur). Le papier est proposé dans le sens de la
 * première photo ; chaque page prend ensuite celui de la sienne.
 */
object PhotoPrinter {

    private const val TAG = "PhotoPrinter"

    /**
     * Lance l'impression depuis [context] (une activité : l'aperçu s'ouvre par-dessus) ; faux si
     * l'appareil n'a pas de service d'impression.
     */
    fun print(context: Context, photos: List<MediaItem>): Boolean {
        if (photos.isEmpty()) return false
        val manager = context.getSystemService(PrintManager::class.java) ?: return false
        val first = photos.first()
        val name = if (photos.size == 1) {
            first.displayName.substringBeforeLast('.').ifBlank { first.displayName }
        } else {
            context.resources.getQuantityString(R.plurals.print_job_name, photos.size, photos.size)
        }
        val attributes = PrintAttributes.Builder()
            .setMediaSize(if (first.displayWidth > first.displayHeight) PrintAttributes.MediaSize.UNKNOWN_LANDSCAPE else PrintAttributes.MediaSize.UNKNOWN_PORTRAIT)
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .build()
        return runCatching {
            manager.print(name, PhotoPrintAdapter(context.applicationContext.contentResolver, photos, name), attributes)
        }.onFailure { Log.w(TAG, "Impression indisponible", it) }.isSuccess
    }
}
