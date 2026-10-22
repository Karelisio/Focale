package app.focale.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream

/** Documents des sauvegardes, créés ou choisis par les sélecteurs du système (coffre-fort et données). */
internal object BackupDocuments {

    private const val TAG = "BackupDocuments"

    /** Flux d'écriture du document, tronqué (« wt ») quand le fournisseur le permet. */
    fun openOutput(resolver: ContentResolver, target: Uri): OutputStream =
        runCatching { resolver.openOutputStream(target, "wt") }.getOrNull()
            ?: resolver.openOutputStream(target, "w")
            ?: throw FileNotFoundException("Écriture impossible : $target")

    /** Supprime un fichier de sauvegarde inachevé ; faux s'il est resté en place. */
    fun delete(resolver: ContentResolver, target: Uri): Boolean = try {
        if (target.scheme == ContentResolver.SCHEME_FILE) {
            val file = File(requireNotNull(target.path))
            !file.exists() || file.delete()
        } else {
            DocumentsContract.deleteDocument(resolver, target)
        }
    } catch (error: Exception) {
        Log.w(TAG, "Fichier inachevé non supprimé : $target", error)
        false
    }

    /** Plus de place sur le support écrit (ENOSPC), à travers les exceptions qui l'enveloppent. */
    fun isNoSpace(error: Throwable): Boolean = generateSequence(error) { it.cause }.any { cause ->
        (cause is ErrnoException && cause.errno == OsConstants.ENOSPC) || cause.message?.contains("ENOSPC") == true
    }
}
