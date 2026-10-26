package app.focale.cast

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.focale.cast.dlna.BytesMedia
import app.focale.cast.dlna.DlnaRenderer
import app.focale.cast.dlna.FakeRenderer
import app.focale.cast.dlna.LanHttpClient
import app.focale.cast.dlna.MediaServer
import app.focale.cast.dlna.RendererClient
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaType
import java.net.InetAddress
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Touches de volume du téléphone pendant une diffusion ([TvVolumeKeys], [TvRemoteVolume]), contre
 * la TV factice : chaque touche que le système transmet au fournisseur de volume de la session
 * (`onAdjustVolume`, comme pour une vraie touche) devient un SetVolume de RenderingControl ; le
 * curseur du système aussi. Volume changé à la télécommande : suivi. Diffusion finie, ou TV sans
 * volume : plus de session, les touches règlent le téléphone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TvVolumeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fake = FakeRenderer()
    private var session: TvSession? = null

    @After
    fun tearDown() {
        session?.close(sendStop = false)
        fake.close()
    }

    private fun volumeTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            session?.close(sendStop = false)
            session = null
        }
    }

    /** Diffusion d'une photo vers [renderer], et ses touches de volume suivies jusqu'à l'annulation du travail rendu. */
    private fun TestScope.cast(keys: TvVolumeKeys, renderer: DlnaRenderer = fake.renderer): Job {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tv = TvSession(
            renderer = renderer,
            client = RendererClient(renderer, LanHttpClient(hostAllowed = { it.isLoopbackAddress }, connectTimeoutMs = 500, readTimeoutMs = 2_000)),
            server = MediaServer(InetAddress.getByName("127.0.0.1")),
            content = MutableStateFlow(photo()),
            prepare = { item -> PreparedMedia(BytesMedia(ByteArray(64), item.mimeType)) },
            events = {},
            onEnd = { ending, lost -> ending.close(sendStop = !lost) },
            appScope = backgroundScope,
            io = dispatcher,
            clock = { testScheduler.currentTime },
        )
        session = tv
        val following = backgroundScope.launch { keys.follow(tv) }
        tv.start()
        runCurrent()
        return following
    }

    /** Une touche, telle que le système la passe à la session de l'activité. */
    private fun TvVolumeKeys.press(direction: Int) = requireNotNull(provider).onAdjustVolume(direction)

    /** Laisse partir la commande (regroupée sur [TvSession.VOLUME_DEBOUNCE_MS]). */
    private fun TestScope.settle() {
        advanceTimeBy(TvSession.VOLUME_DEBOUNCE_MS + 1)
        runCurrent()
    }

    @Test
    fun `les touches règlent la TV pendant la diffusion, par pas de 2`() = volumeTest {
        val keys = TvVolumeKeys(context, StandardTestDispatcher(testScheduler))
        cast(keys)
        assertNotNull("session confiée à l'activité", keys.controller.value)
        // Volume de la TV (20) sur l'échelle du système (0 à 50).
        assertEquals(10, keys.provider?.currentVolume)
        assertEquals(TvRemoteVolume.MAX_STEPS, keys.provider?.maxVolume)

        keys.press(AudioManager.ADJUST_RAISE)
        keys.press(AudioManager.ADJUST_RAISE)
        settle()
        // Deux appuis rapprochés : une seule commande, au volume final.
        assertEquals(listOf(24), fake.volumes)
        assertEquals(12, keys.provider?.currentVolume)

        keys.press(AudioManager.ADJUST_LOWER)
        settle()
        assertEquals(listOf(24, 22), fake.volumes)
        // Couper le son : sans effet sur une TV.
        keys.press(AudioManager.ADJUST_TOGGLE_MUTE)
        keys.press(AudioManager.ADJUST_MUTE)
        settle()
        assertEquals(listOf(24, 22), fake.volumes)

        // Curseur du volume du système, en pas.
        requireNotNull(keys.provider).onSetVolumeTo(5)
        settle()
        assertEquals(listOf(24, 22, 10), fake.volumes)
    }

    @Test
    fun `volume changé à la télécommande, la touche suivante part de lui`() = volumeTest {
        val keys = TvVolumeKeys(context, StandardTestDispatcher(testScheduler))
        cast(keys)
        fake.volume = 40
        advanceTimeBy(TvSession.VOLUME_REFRESH_MS + 1)
        runCurrent()
        assertEquals(20, keys.provider?.currentVolume)
        keys.press(AudioManager.ADJUST_RAISE)
        settle()
        assertEquals(listOf(42), fake.volumes)
    }

    @Test
    fun `diffusion finie, les touches reviennent au téléphone`() = volumeTest {
        val keys = TvVolumeKeys(context, StandardTestDispatcher(testScheduler))
        val following = cast(keys)
        val provider = requireNotNull(keys.provider)
        following.cancel()
        runCurrent()
        assertNull(keys.controller.value)
        assertNull(keys.provider)
        session?.close(sendStop = false)
        runCurrent()
        // Un appui tardif sur l'ancienne session ne joint plus la TV.
        provider.onAdjustVolume(AudioManager.ADJUST_RAISE)
        settle()
        assertTrue(fake.volumes.isEmpty())
    }

    @Test
    fun `TV sans volume, aucune session, les touches règlent le téléphone`() = volumeTest {
        val keys = TvVolumeKeys(context, StandardTestDispatcher(testScheduler))
        cast(keys, renderer = fake.renderer.copy(renderingControl = null))
        advanceTimeBy(TvSession.VOLUME_REFRESH_MS + 1)
        runCurrent()
        assertNull(keys.controller.value)
        assertTrue("GetVolume" !in fake.actions)
    }

    private fun photo() = MediaItem(
        id = 1,
        uri = Uri.parse("content://media/external/file/1"),
        type = MediaType.IMAGE,
        mimeType = "image/jpeg",
        displayName = "media1",
        relativePath = "DCIM/Camera/",
        bucketId = 1,
        bucketName = "Camera",
        dateTaken = 0,
        dateModified = 0,
        size = 64,
        width = 1920,
        height = 1080,
        orientation = 0,
        durationMs = 0,
        isGif = false,
        isScreenshot = false,
    )
}
