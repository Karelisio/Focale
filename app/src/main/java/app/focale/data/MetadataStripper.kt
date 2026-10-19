package app.focale.data

import android.net.Uri
import app.focale.core.model.MediaItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Retire les métadonnées sensibles (EXIF, GPS) avant un partage.
 * L'implémentation complète est fournie avec les fonctions de vie privée.
 */
@Singleton
class MetadataStripper @Inject constructor() {
    fun prepareForSharing(items: List<MediaItem>): List<Uri> = items.map { it.uri }
}
