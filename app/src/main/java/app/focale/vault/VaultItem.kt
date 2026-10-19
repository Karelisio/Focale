package app.focale.vault

import android.content.Context
import android.os.storage.StorageManager
import androidx.compose.runtime.Immutable
import app.focale.core.database.MediaQueryBuilder
import app.focale.core.database.VaultItemEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Média du coffre-fort, tel qu'affiché une fois le coffre déverrouillé. */
@Immutable
data class VaultItem(
    val id: Long,
    val displayName: String,
    val mimeType: String,
    val isVideo: Boolean,
    /** Dossier d'origine (`RELATIVE_PATH`), utilisé pour la restauration. */
    val originalPath: String,
    val dateTaken: Long,
    val size: Long,
    val durationMs: Long,
    val fileName: String,
    val thumbFileName: String?,
) {
    /** Fichier complet, pour Coil. */
    fun image(): VaultImage = VaultImage(fileName)
}

fun VaultItemEntity.toModel(): VaultItem = VaultItem(
    id = id,
    displayName = displayName,
    mimeType = mimeType,
    isVideo = mediaType == MediaQueryBuilder.MEDIA_TYPE_VIDEO,
    originalPath = originalPath,
    dateTaken = dateTaken,
    size = size,
    durationMs = duration,
    fileName = fileName,
    thumbFileName = thumbFileName,
)

/**
 * Emplacement des fichiers chiffrés : stockage privé de l'application, hors de
 * MediaStore et exclu des sauvegardes.
 */
@Singleton
class VaultStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val directory: File by lazy { File(context.noBackupFilesDir, "vault").apply { mkdirs() } }

    fun file(name: String): File = File(directory, name)

    /** Place suffisante pour [bytes] octets, marge comprise (caches purgeables inclus). */
    fun hasRoomFor(bytes: Long): Boolean {
        val needed = bytes + MIN_FREE_BYTES
        return runCatching {
            val manager = context.getSystemService(StorageManager::class.java)
            manager.getAllocatableBytes(manager.getUuidForPath(directory)) >= needed
        }.getOrElse { directory.usableSpace >= needed }
    }

    private companion object {
        const val MIN_FREE_BYTES = 64L * 1024 * 1024
    }
}
