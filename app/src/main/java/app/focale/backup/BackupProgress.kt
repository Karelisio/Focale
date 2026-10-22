package app.focale.backup

import androidx.compose.runtime.Immutable

/** Avancement d'une sauvegarde ou d'une restauration (coffre-fort ou données). */
@Immutable
data class BackupProgress(
    val stage: Stage,
    val done: Int = 0,
    val total: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
) {
    enum class Stage {
        /** Dérivation de la clé et, à la restauration, vérification du mot de passe. */
        PREPARING,
        TRANSFERRING,
        /** Restauration : ajout des éléments vérifiés, qui ne s'interrompt pas. */
        FINISHING,
    }

    val fraction: Float
        get() = when {
            bytesTotal > 0 -> (bytesDone.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f)
            total > 0 -> (done.toFloat() / total).coerceIn(0f, 1f)
            else -> 0f
        }
}
