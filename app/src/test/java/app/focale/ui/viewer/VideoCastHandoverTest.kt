package app.focale.ui.viewer

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.media3.common.MediaItem as ExoMediaItem

/**
 * Retour d'une vidéo de la TV au téléphone, vu de sa page : la vidéo rendue n'est plus diffusée,
 * et le lecteur du téléphone reprend là où en était celui de la TV, en lecture ou en pause.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class VideoCastHandoverTest {

    @get:Rule
    val compose = createComposeRule()

    private val players = ArrayList<FakePlayer>()

    /** Page de la vidéo, lue par la TV tant que [onTv] (« Lecture sur Salon »), sinon par le téléphone. */
    private fun page(onTv: () -> Boolean) {
        compose.setContent {
            val tv = onTv()
            VideoPlayerPage(
                key = 1L,
                isCurrent = true,
                autoplay = false,
                startMuted = false,
                controlsVisible = false,
                onTap = {},
                playbackSpeed = 1f,
                onPlaybackSpeedChange = {},
                mediaItem = { ExoMediaItem.fromUri("file:///clip.mp4") },
                poster = {},
                playerKey = if (tv) "uuid:tv" else null,
                castLabel = if (tv) "Salon" else null,
                pauseInBackground = !tv,
                playerFactory = { FakePlayer(positionMs = 0, durationMs = 120_000).also(players::add) },
            )
        }
    }

    @Test
    fun `la TV rend la vidéo, le téléphone reprend à la même position, en lecture`() {
        var onTv by mutableStateOf(true)
        page { onTv }
        val tv = players.single()
        tv.play()
        tv.seekTo(42_000)

        onTv = false
        compose.waitForIdle()
        val phone = players.last()
        assertNotSame(tv, phone)
        assertTrue("lecteur de la TV libéré", tv.released)
        // Lecture en cours : la position a pu avancer de quelques millisecondes entre-temps.
        assertEquals(42_000.0, phone.positionMs.toDouble(), 500.0)
        assertEquals(true, phone.playRequests.lastOrNull())
    }

    @Test
    fun `une vidéo en pause sur la TV reprend en pause sur le téléphone`() {
        var onTv by mutableStateOf(true)
        page { onTv }
        val tv = players.single()
        tv.seekTo(75_000)
        tv.pause()

        onTv = false
        compose.waitForIdle()
        val phone = players.last()
        assertEquals(75_000L, phone.positionMs)
        assertFalse(phone.playRequests.lastOrNull() ?: false)
    }

    @Test
    fun `une vidéo rendue au téléphone par la TV n'y est plus diffusée, les autres si`() {
        val tv = VideoCast.Tv("uuid:tv", "Salon", onPhone = setOf(2L)) { error("pas de lecteur distant") }
        assertSame(tv, VideoCast.of(tv, video(1)))
        assertNull(VideoCast.of(tv, video(2)))
        val screen = VideoCast.Screen("HDMI")
        assertSame("l'écran externe montre le lecteur du téléphone", screen, VideoCast.of(screen, video(2)))
        assertNull(VideoCast.of(null, video(1)))
    }

    private fun video(id: Long) = MediaItem(
        id = id,
        uri = Uri.parse("content://media/external/video/media/$id"),
        type = MediaType.VIDEO,
        mimeType = "video/mp4",
        displayName = "VID_$id.mp4",
        relativePath = "DCIM/Camera/",
        bucketId = 1,
        bucketName = "Camera",
        dateTaken = 0,
        dateModified = 0,
        size = 1_000,
        width = 1920,
        height = 1080,
        orientation = 0,
        durationMs = 120_000,
        isGif = false,
        isScreenshot = false,
    )
}
