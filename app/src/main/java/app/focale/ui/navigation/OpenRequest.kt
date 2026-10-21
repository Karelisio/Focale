package app.focale.ui.navigation

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import app.focale.MainActivity

/** Ouverture demandée à l'activité par une intention, traduite en destination par [FocaleApp]. */
sealed interface OpenRequest {
    /**
     * Lanceur : la galerie. Ne sert qu'à une activité lancée pour le média d'une autre application,
     * qu'Android peut ramener au premier plan (`singleTop`) quand on touche l'icône de Focale.
     */
    data object Home : OpenRequest

    /** Média de l'index (appui sur le widget), ouvert dans la timeline. */
    data class Media(val mediaId: Long) : OpenRequest

    /**
     * Photo ou vidéo d'une autre application, de type [mimeType] s'il est donné ; [closeOnBack] :
     * l'activité a été lancée pour elle, le retour ramène alors à l'application appelante.
     */
    data class External(val uri: Uri, val mimeType: String?, val closeOnBack: Boolean) : OpenRequest {
        val route: ExternalMediaRoute get() = ExternalMediaRoute(uri.toString(), mimeType, closeOnBack)
    }

    companion object {
        /** Ouvrir, et revoir la prise de vue d'un appareil photo (REVIEW_SECURE exclue : voir le manifeste). */
        private val VIEW_ACTIONS = setOf(Intent.ACTION_VIEW, MediaStore.ACTION_REVIEW, "com.android.camera.action.REVIEW")

        /** Racines du stockage partagé, seuls chemins `file://` acceptés (reçus, jamais construits ici). */
        @SuppressLint("SdCardPath")
        private val SHARED_STORAGE = listOf("/storage/", "/sdcard/")

        /**
         * Demande portée par [intent], ou null (intention sans média valable) ; [launched] : l'intention
         * vient de lancer l'activité. Les fichiers propres à Focale ([ownPackage]) sont refusés : une
         * autre application ne doit pas pouvoir les lui faire partager.
         */
        fun of(intent: Intent?, launched: Boolean, ownPackage: String): OpenRequest? {
            intent ?: return null
            return when (intent.action) {
                Intent.ACTION_MAIN -> Home.takeIf { intent.hasCategory(Intent.CATEGORY_LAUNCHER) }
                MainActivity.ACTION_OPEN_MEDIA -> intent.getLongExtra(MainActivity.EXTRA_MEDIA_ID, -1L).takeIf { it >= 0 }?.let(::Media)
                in VIEW_ACTIONS -> external(intent, launched, ownPackage)
                else -> null
            }
        }

        private fun external(intent: Intent, launched: Boolean, ownPackage: String): External? {
            val uri = intent.data ?: return null
            val type = intent.type?.lowercase()
            if (type != null && !type.startsWith("image/") && !type.startsWith("video/")) return null
            val accepted = when (uri.scheme?.lowercase()) {
                ContentResolver.SCHEME_CONTENT -> uri.authority.let { it != null && it != ownPackage && !it.startsWith("$ownPackage.") }
                ContentResolver.SCHEME_FILE -> uri.path?.let(::isSharedStorage) == true
                else -> false
            }
            return if (accepted) External(uri, type, closeOnBack = launched) else null
        }

        private fun isSharedStorage(path: String): Boolean =
            SHARED_STORAGE.any { path.startsWith(it) } && path.split('/').none { it == ".." }
    }
}
