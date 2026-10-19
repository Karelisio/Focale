package app.focale.video

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Objets Media3 de la fonctionnalité : élément rogné pour l'export, source de la vidéo intégrée. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoMediaTest {

    private val uri = Uri.parse("content://media/external/video/media/42")

    @Test
    fun `seule la coupe demandée est transmise à Transformer`() {
        val full = TrimRange(10_000)

        assertEquals(MediaItem.ClippingConfiguration.UNSET, TrimRequest(full, removeAudio = true).toMediaItem(uri).clippingConfiguration)

        val end = TrimRequest(full.withEnd(8_000), removeAudio = false).toMediaItem(uri).clippingConfiguration
        assertEquals(0L, end.startPositionMs)
        assertEquals(8_000L, end.endPositionMs)

        val start = TrimRequest(full.withStart(1_500), removeAudio = false).toMediaItem(uri).clippingConfiguration
        assertEquals(1_500L, start.startPositionMs)
        assertEquals(C.TIME_END_OF_SOURCE, start.endPositionMs)
    }

    @Test
    fun `la source ne lit que la plage de la vidéo`() {
        val file = ByteArray(100) { it.toByte() }
        val source = ByteRangeDataSource(ByteArrayDataSource(file), start = 60, length = 30)

        assertEquals(30L, source.open(DataSpec(uri)))
        val buffer = ByteArray(64)
        var total = 0
        while (true) {
            val read = source.read(buffer, total, buffer.size - total)
            if (read == C.RESULT_END_OF_INPUT) break
            total += read
        }
        source.close()
        assertEquals(30, total)
        assertArrayEquals(file.copyOfRange(60, 90), buffer.copyOf(30))
    }

    @Test
    fun `un déplacement dans la vidéo est décalé du début de la plage`() {
        val file = ByteArray(100) { it.toByte() }
        val source = ByteRangeDataSource(ByteArrayDataSource(file), start = 60, length = 30)

        assertEquals(5L, source.open(DataSpec.Builder().setUri(uri).setPosition(20).setLength(5).build()))
        val buffer = ByteArray(5)
        assertEquals(5, source.read(buffer, 0, 5))
        source.close()
        assertArrayEquals(file.copyOfRange(80, 85), buffer)

        // Une longueur qui dépasse la plage est ramenée à sa fin.
        assertEquals(10L, source.open(DataSpec.Builder().setUri(uri).setPosition(20).setLength(50).build()))
        source.close()
    }

    @Test(expected = DataSourceException::class)
    fun `une position au-delà de la plage est refusée`() {
        ByteRangeDataSource(ByteArrayDataSource(ByteArray(100)), start = 60, length = 30)
            .open(DataSpec.Builder().setUri(uri).setPosition(31).build())
    }
}
