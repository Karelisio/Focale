package app.focale.video

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlin.math.min

/**
 * Source Media3 qui expose une plage d'octets d'un fichier comme un fichier à part entière :
 * la position 0 correspond à [start] et la lecture s'arrête après [length] octets.
 *
 * Sert à lire la vidéo d'une photo animée directement dans le JPEG, sans copie ni chargement en
 * mémoire : chaque ouverture (y compris les déplacements du lecteur dans le MP4) est traduite
 * en une ouverture de la source amont au bon décalage.
 */
@OptIn(UnstableApi::class)
class ByteRangeDataSource(
    private val upstream: DataSource,
    private val start: Long,
    private val length: Long,
) : DataSource {

    init {
        require(start >= 0 && length >= 0) { "Plage invalide : $start + $length" }
    }

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.position > length) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        val available = length - dataSpec.position
        val wanted = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else min(dataSpec.length, available)
        upstream.open(dataSpec.subrange(start, wanted))
        return wanted
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        upstream.close()
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val start: Long,
        private val length: Long,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = ByteRangeDataSource(upstream.createDataSource(), start, length)
    }
}
