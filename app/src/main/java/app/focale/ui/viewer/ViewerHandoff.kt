package app.focale.ui.viewer

import android.util.LruCache
import app.focale.core.model.MediaItem

/**
 * Transmet en mémoire le média touché dans une grille à la visionneuse, pour que
 * celle-ci puisse afficher son image dès la première frame (indispensable à la
 * transition d'élément partagé). En cas de mort du processus, la visionneuse
 * retombe simplement sur une lecture en base.
 */
object ViewerHandoff {
    private val items = LruCache<Long, MediaItem>(16)

    fun put(item: MediaItem) {
        items.put(item.id, item)
    }

    fun get(id: Long): MediaItem? = items.get(id)
}
