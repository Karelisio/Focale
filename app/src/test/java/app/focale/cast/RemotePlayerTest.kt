package app.focale.cast

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Lecteur qui reflète la vidéo jouée par la TV : position demandée pendant le chargement, puis
 * celle de la TV ; vidéo lâchée par la TV (refus, connexion perdue) à sa dernière position
 * connue, pour que le lecteur du téléphone la reprenne là.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemotePlayerTest {

    private val playback = MutableStateFlow<TvPlayback?>(null)
    private val seeks = ArrayList<Long>()
    private val plays = ArrayList<Boolean>()
    private val player = RemotePlayer(
        mediaId = 1,
        durationHintMs = 120_000,
        playback = playback,
        onPlaying = { plays += it },
        onSeek = { seeks += it },
    )

    @After
    fun tearDown() = player.release()

    private fun publish(value: TvPlayback?) {
        playback.value = value
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `une vidéo refusée pendant son chargement garde la position venue du téléphone`() {
        player.playWhenReady = true
        player.seekTo(42_000)
        assertEquals(listOf(42_000L), seeks)
        publish(TvPlayback(mediaId = 1))
        assertEquals(42_000L, player.currentPosition)
        assertEquals(Player.STATE_BUFFERING, player.playbackState)

        // Refusée : plus de lecture sur la TV ; la page reprend à 0:42, en lecture.
        publish(null)
        assertEquals(42_000L, player.currentPosition)
        assertTrue(player.playWhenReady)
    }

    @Test
    fun `une TV perdue en pleine lecture laisse sa dernière position mesurée, sans extrapolation`() {
        publish(TvPlayback(mediaId = 1, loading = false, playing = true, positionMs = 65_000, durationMs = 120_000, measuredAt = SystemClock.elapsedRealtime()))
        assertTrue(player.playWhenReady)
        assertEquals(Player.STATE_READY, player.playbackState)
        // Quatre secondes sans mesure : la position extrapolée avance…
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertTrue(player.currentPosition >= 69_000L)

        // … mais la TV perdue, c'est la dernière position mesurée qui compte.
        publish(null)
        assertEquals(65_000L, player.currentPosition)
        assertTrue("la lecture continue sur le téléphone", player.playWhenReady)
        assertEquals(120_000L, player.duration)
    }

    @Test
    fun `une vidéo mise en pause avant la perte de la TV reprend en pause`() {
        publish(TvPlayback(mediaId = 1, loading = false, playing = true, positionMs = 10_000, durationMs = 120_000, measuredAt = SystemClock.elapsedRealtime()))
        player.pause()
        assertEquals(listOf(false), plays)
        publish(null)
        assertFalse(player.playWhenReady)
        assertEquals(10_000L, player.currentPosition)
    }

    @Test
    fun `la lecture d'une autre vidéo n'est pas la sienne`() {
        player.seekTo(5_000)
        publish(TvPlayback(mediaId = 2, loading = false, playing = true, positionMs = 90_000))
        assertEquals(5_000L, player.currentPosition)
        assertEquals(Player.STATE_BUFFERING, player.playbackState)
    }
}
