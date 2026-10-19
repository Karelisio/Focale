package app.focale.vault

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.security.GeneralSecurityException
import kotlin.math.min

/**
 * Source Media3 lisant une vidéo chiffrée du coffre avec accès aléatoire : le lecteur
 * peut se positionner n'importe où, seuls les segments nécessaires sont déchiffrés,
 * et aucune donnée en clair n'est écrite sur le disque.
 *
 * L'URI attendue est celle du fichier chiffré (`file://…`).
 */
@OptIn(UnstableApi::class)
class EncryptedFileDataSource(private val crypto: VaultCrypto) : BaseDataSource(/* isNetwork = */ false) {

    private var channel: SeekableByteChannel? = null
    private var uri: Uri? = null
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val path = dataSpec.uri.path ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val source = try {
            crypto.openSeekable(File(path))
        } catch (error: IOException) {
            throw DataSourceException(error, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        } catch (error: GeneralSecurityException) {
            throw DataSourceException(error, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        channel = source
        // Le canal d'un jeu de clés n'identifie sa clé qu'à la première lecture : on lit
        // un octet à la position demandée (segment qui sera lu de toute façon) avant de
        // demander la taille en clair, puis on revient à cette position.
        val size = try {
            source.position(dataSpec.position)
            source.read(ByteBuffer.allocate(1))
            source.size()
        } catch (error: IOException) {
            throw DataSourceException(error, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        if (dataSpec.position > size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        source.position(dataSpec.position)
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - dataSpec.position
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val source = channel ?: return C.RESULT_END_OF_INPUT
        val wanted = min(bytesRemaining, length.toLong()).toInt()
        val read = try {
            source.read(ByteBuffer.wrap(buffer, offset, wanted))
        } catch (error: IOException) {
            throw DataSourceException(error, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        if (read < 0) return C.RESULT_END_OF_INPUT
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            channel?.close()
        } catch (error: IOException) {
            throw DataSourceException(error, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        } finally {
            channel = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(private val crypto: VaultCrypto) : DataSource.Factory {
        override fun createDataSource(): DataSource = EncryptedFileDataSource(crypto)
    }
}
