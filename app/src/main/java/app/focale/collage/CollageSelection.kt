package app.focale.collage

import app.focale.core.model.MediaItem

/** Photos retenues pour un collage, ou sélection qui n'en compte pas assez. */
sealed interface CollagePick {
    /** [truncated] : la sélection comptait plus de [CollageLayouts.MAX_PHOTOS] photos. */
    data class Ready(val ids: List<Long>, val truncated: Boolean) : CollagePick

    data object NotEnough : CollagePick
}

/** Choix des photos d'un collage dans une sélection de la galerie. */
object CollageSelection {
    /**
     * Photos de [items], dans l'ordre de la sélection : les vidéos sont ignorées, et seules les
     * [CollageLayouts.MAX_PHOTOS] premières sont gardées. Moins de [CollageLayouts.MIN_PHOTOS]
     * photos : pas de collage.
     */
    fun pick(items: List<MediaItem>): CollagePick {
        val photos = items.filterNot { it.isVideo }.map { it.id }.distinct()
        return if (photos.size < CollageLayouts.MIN_PHOTOS) {
            CollagePick.NotEnough
        } else {
            CollagePick.Ready(photos.take(CollageLayouts.MAX_PHOTOS), truncated = photos.size > CollageLayouts.MAX_PHOTOS)
        }
    }
}
