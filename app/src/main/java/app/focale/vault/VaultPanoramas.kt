package app.focale.vault

import android.graphics.ImageDecoder
import android.util.Log
import app.focale.core.di.IoDispatcher
import app.focale.panorama.Panorama
import app.focale.panorama.Panoramas
import app.focale.panorama.PhotoStreams
import app.focale.panorama.SphericalVideoHeaders
import app.focale.panorama.SphericalVideoRules
import app.focale.panorama.SphericalVideos
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Photos et vidéos 360° du coffre, et panoramas, comme dans la galerie ([Panoramas],
 * [SphericalVideos]) : les en-têtes sont lus dans le fichier chiffré par le canal déchiffrant à
 * accès aléatoire de la lecture vidéo, les dimensions et l'orientation d'une photo par un flux
 * déchiffrant, la photo elle-même décodée en mémoire ([photoStreams]). Rien n'est écrit en clair
 * sur le disque, ni gardé hors de la visionneuse (le résultat vit avec elle).
 */
@Singleton
class VaultPanoramas @Inject constructor(
    private val crypto: VaultCrypto,
    private val storage: VaultStorage,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Vue proposée pour [item], ou null : média ordinaire, illisible, ou coffre verrouillé entre-temps. */
    suspend fun find(item: VaultItem): Panorama? = withContext(io) {
        try {
            if (item.isVideo && !SphericalVideos.isCandidate(item.mimeType)) return@withContext null
            crypto.openSeekable(storage.file(item.fileName)).use { channel ->
                if (item.isVideo) {
                    SphericalVideoRules.classify(item.id, SphericalVideoHeaders.read { offset, length -> channel.readAt(offset, length) })
                } else {
                    Panoramas.detect(item.id, item.mimeType, photoStreams(item)) { offset, length -> channel.readAt(offset, length) }
                }
            }
        } catch (error: Exception) {
            // Fichier absent, déchiffrement refusé (Keystore, authentification d'un segment)…
            Log.d(TAG, "Lecture impossible : ${item.id}", error)
            null
        }
    }

    /** Octets de la photo [item], déchiffrés à la volée, en mémoire seulement. */
    internal fun photoStreams(item: VaultItem): PhotoStreams = DecryptedPhoto(crypto, storage.file(item.fileName))

    private class DecryptedPhoto(private val crypto: VaultCrypto, private val file: File) : PhotoStreams {
        override fun open(): InputStream = crypto.openDecrypting(file)

        // ImageDecoder ne lit pas un flux à la demande : la photo compressée est déchiffrée en mémoire.
        override fun decoderSource(): ImageDecoder.Source = ImageDecoder.createSource(ByteBuffer.wrap(open().use { it.readBytes() }))
    }

    private companion object {
        const val TAG = "VaultPanoramas"

        /** [length] octets en clair à [offset], null au-delà de la fin. */
        fun SeekableByteChannel.readAt(offset: Long, length: Int): ByteArray? {
            if (offset < 0 || length < 0) return null
            position(offset)
            val buffer = ByteBuffer.allocate(length)
            while (buffer.hasRemaining()) {
                if (read(buffer) < 0) return null
            }
            return buffer.array()
        }
    }
}
