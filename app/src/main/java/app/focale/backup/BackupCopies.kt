package app.focale.backup

import android.content.Context
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.UUID

/**
 * Copies locales des sauvegardes à restaurer ([DataBackupRepository.open]) : le document choisi
 * (souvent chez un fournisseur en ligne, lent à relire) est copié une seule fois, tel quel donc
 * toujours chiffré, dans le cache privé de l'application ; vérification et restauration lisent la
 * copie. Chaque copie est supprimée quand elle ne sert plus ([StagedBackup.close] : restauration
 * faite, abandonnée, annulée ou en échec), à la copie suivante si elle a été oubliée, et au
 * démarrage si le processus s'est arrêté avant ([clear]).
 */
internal object BackupCopies {
    private const val TAG = "BackupCopies"
    private const val DIRECTORY = "restore"
    const val SUFFIX = ".focale"

    /** Copies en service dans ce processus : les autres fichiers du dossier sont des restes. */
    private val live: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun directory(context: Context): File = File(context.cacheDir, DIRECTORY)

    /** Nouvelle copie, vide, dans [directory] ; ses restes d'une copie abandonnée sont d'abord retirés. */
    fun create(directory: File): StagedFile {
        directory.mkdirs()
        clear(directory)
        val file = File(directory, UUID.randomUUID().toString() + SUFFIX)
        live += file.absolutePath
        return StagedFile(file)
    }

    /** Supprime les copies de [directory] qu'aucune restauration en cours n'utilise. */
    fun clear(directory: File) {
        val files = directory.listFiles() ?: return
        for (file in files) {
            if (file.absolutePath in live) continue
            if (!file.delete() && file.exists()) Log.w(TAG, "Copie de sauvegarde non supprimée")
        }
    }

    /** Fichier d'une copie, oublié et supprimé à la fermeture. */
    class StagedFile(val file: File) : Closeable {
        override fun close() {
            file.delete()
            live -= file.absolutePath
        }
    }
}

/**
 * Flux du document lu une seule fois, recopié à mesure dans [copy] : ce qu'en lit la vérification
 * de l'en-tête est écrit, puis [drain] recopie le reste. [checkpoint] est appelé à chaque lecture
 * (annulation).
 */
internal class CopyingInput(
    private val document: InputStream,
    private val copy: OutputStream,
    private val checkpoint: () -> Unit,
) : InputStream() {
    private val single = ByteArray(1)

    override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xFF

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkpoint()
        val count = document.read(buffer, offset, length)
        if (count > 0) copy.write(buffer, offset, count)
        return count
    }

    /** Recopie la fin du document. */
    fun drain() {
        val buffer = ByteArray(BUFFER)
        while (read(buffer, 0, buffer.size) >= 0) Unit
    }

    private companion object {
        const val BUFFER = 256 * 1024
    }
}
