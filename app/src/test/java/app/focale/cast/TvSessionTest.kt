package app.focale.cast

import android.net.Uri
import app.focale.cast.dlna.BytesMedia
import app.focale.cast.dlna.FakeRenderer
import app.focale.cast.dlna.LanHttpClient
import app.focale.cast.dlna.MediaServer
import app.focale.cast.dlna.RendererClient
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaType
import java.net.InetAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Diffusion vers la TV factice ([FakeRenderer], boucle locale), en temps virtuel : vidéo refusée
 * ou jamais lue, rendue au téléphone pendant que les médias suivants vont toujours à la TV ;
 * envoi impossible un instant, retenté ensuite ; TV qui disparaît en pleine vidéo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TvSessionTest {

    private val fake = FakeRenderer()
    private val content = MutableStateFlow<MediaItem?>(null)
    private val events = ArrayList<CastEvent>()

    /** Fins demandées par la session : vrai pour une TV perdue. */
    private val ended = ArrayList<Boolean>()
    private var session: TvSession? = null

    @After
    fun tearDown() {
        session?.close(sendStop = false)
        fake.close()
    }

    /**
     * Test en temps virtuel : la session est refermée à la fin, sans quoi `runTest` ferait tourner
     * son interrogation de la TV indéfiniment.
     */
    private fun sessionTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            session?.close(sendStop = false)
            session = null
        }
    }

    private fun TestScope.start(): TvSession {
        val tv = TvSession(
            renderer = fake.renderer,
            client = RendererClient(fake.renderer, LanHttpClient(hostAllowed = { it.isLoopbackAddress }, connectTimeoutMs = 500, readTimeoutMs = 2_000)),
            server = MediaServer(InetAddress.getByName("127.0.0.1")),
            content = content,
            prepare = { item -> PreparedMedia(BytesMedia(ByteArray(64), item.mimeType, video = item.isVideo)) },
            events = { events += it },
            onEnd = { ending, lost ->
                ended += lost
                ending.close(sendStop = !lost)
            },
            appScope = backgroundScope,
            io = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
        )
        session = tv
        tv.start()
        runCurrent()
        return tv
    }

    /** Montre [item] (la visionneuse change de page), puis laisse la session l'envoyer. */
    private fun TestScope.show(item: MediaItem) {
        content.value = item
        runCurrent()
    }

    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `une vidéo refusée passe au téléphone, la diffusion continue pour les médias suivants`() = sessionTest {
        fake.refuses = { it.endsWith(".mkv") }
        content.value = photo(1)
        val tv = start()
        assertTrue(fake.uri.orEmpty().endsWith("/media.jpg"))
        assertEquals("PLAYING", fake.state)
        assertEquals(TvStatus.READY, tv.info.value.status)

        show(video(2, "video/x-matroska"))
        assertEquals(listOf<CastEvent>(CastEvent.Refused(video = true)), events)
        assertEquals(setOf(2L), tv.phoneVideos.value)
        assertNull("rien à suivre sur la TV", tv.playback.value)
        // La photo précédente n'y reste pas pendant que le téléphone lit la vidéo.
        assertEquals("STOPPED", fake.state)
        assertEquals(TvStatus.READY, tv.info.value.status)

        show(photo(3))
        assertTrue(fake.uri.orEmpty().endsWith("/media.jpg"))
        assertEquals("PLAYING", fake.state)

        // Revenir sur la vidéo refusée : elle n'est plus proposée à la TV.
        val before = fake.actions.size
        show(video(2, "video/x-matroska"))
        assertFalse("SetAVTransportURI" in fake.actionsSince(before))
        assertNull(tv.playback.value)
        assertEquals(1, events.size)

        // Une autre vidéo, dans un format lu par la TV, y est jouée.
        show(video(4, "video/mp4"))
        assertTrue(fake.uri.orEmpty().endsWith("/media.mp4"))
        assertEquals(true, tv.playback.value?.loading)
        // Première interrogation : au plus tard au rythme des photos.
        wait(TvSession.PHOTO_POLL_MS)
        val playing = requireNotNull(tv.playback.value)
        assertEquals(4L, playing.mediaId)
        assertFalse(playing.loading)
        assertTrue(playing.playing)
        assertEquals(setOf(2L), tv.phoneVideos.value)
        assertTrue("diffusion toujours active", ended.isEmpty())
    }

    @Test
    fun `une vidéo acceptée que la TV ne lit jamais passe au téléphone après le délai`() = sessionTest {
        fake.neverPlays = { it.endsWith(".mov") }
        content.value = video(5, "video/quicktime")
        val tv = start()
        assertEquals(true, tv.playback.value?.loading)
        wait(TvSession.REFUSE_AFTER_MS / 2)
        assertTrue("encore en chargement", tv.phoneVideos.value.isEmpty())

        wait(TvSession.REFUSE_AFTER_MS / 2 + TvSession.VIDEO_POLL_MS)
        assertEquals(setOf(5L), tv.phoneVideos.value)
        assertEquals(listOf<CastEvent>(CastEvent.Refused(video = true)), events)
        assertNull(tv.playback.value)

        show(photo(6))
        assertTrue(fake.uri.orEmpty().endsWith("/media.jpg"))
        assertEquals("PLAYING", fake.state)
        assertTrue(ended.isEmpty())
    }

    @Test
    fun `un envoi impossible un instant passe au téléphone, et sera retenté en y revenant`() = sessionTest {
        content.value = photo(1)
        val tv = start()
        fake.offline = true
        show(video(7, "video/mp4"))
        assertEquals(listOf<CastEvent>(CastEvent.Failed(videoOnPhone = true)), events)
        assertEquals(setOf(7L), tv.phoneVideos.value)
        fake.offline = false

        show(photo(8))
        assertTrue("retentée plus tard", tv.phoneVideos.value.isEmpty())
        show(video(7, "video/mp4"))
        assertTrue(fake.uri.orEmpty().endsWith("/media.mp4"))
        assertEquals(7L, tv.playback.value?.mediaId)
        assertTrue(ended.isEmpty())
    }

    @Test
    fun `la TV disparaît en pleine vidéo, la diffusion s'arrête et la vidéo reprend sur le téléphone`() = sessionTest {
        content.value = video(9, "video/mp4")
        val tv = start()
        wait(TvSession.PHOTO_POLL_MS + 2 * TvSession.VIDEO_POLL_MS)
        val last = requireNotNull(tv.playback.value)
        assertTrue(last.playing)
        assertTrue(last.positionMs >= 3_000L)
        assertEquals(fake.positionMs, last.positionMs)

        fake.tv.unplug()
        wait(TvSession.MAX_FAILURES * TvSession.VIDEO_POLL_MS)
        assertEquals(listOf(true), ended)
        assertEquals(listOf<CastEvent>(CastEvent.Lost("Salon", videoOnPhone = true)), events)
        // Dernière position mesurée gardée : la page du téléphone la reprend (RemotePlayer).
        assertEquals(last.positionMs, tv.playback.value?.positionMs)
    }

    @Test
    fun `la TV disparaît pendant une photo, la diffusion s'arrête sans vidéo à reprendre`() = sessionTest {
        content.value = photo(1)
        start()
        fake.tv.unplug()
        wait(TvSession.MAX_FAILURES * TvSession.PHOTO_POLL_MS)
        assertEquals(listOf(true), ended)
        assertEquals(listOf<CastEvent>(CastEvent.Lost("Salon", videoOnPhone = false)), events)
    }

    @Test
    fun `position et pause demandées avant la lecture sont appliquées dès que la TV joue`() = sessionTest {
        content.value = video(10, "video/mp4")
        val tv = start()
        tv.seek(10, 42_000)
        tv.setPlaying(10, false)
        wait(TvSession.PHOTO_POLL_MS)
        assertNotNull(tv.playback.value)
        assertTrue("Seek" in fake.actions)
        assertEquals("PAUSED_PLAYBACK", fake.state)
        assertEquals(42_000L, fake.positionMs)
    }

    private fun photo(id: Long) = item(id, MediaType.IMAGE, "image/jpeg")

    private fun video(id: Long, mimeType: String) = item(id, MediaType.VIDEO, mimeType)

    private fun item(id: Long, type: MediaType, mimeType: String) = MediaItem(
        id = id,
        uri = Uri.parse("content://media/external/file/$id"),
        type = type,
        mimeType = mimeType,
        displayName = "media$id",
        relativePath = "DCIM/Camera/",
        bucketId = 1,
        bucketName = "Camera",
        dateTaken = 0,
        dateModified = 0,
        size = 64,
        width = 1920,
        height = 1080,
        orientation = 0,
        durationMs = if (type == MediaType.VIDEO) 120_000 else 0,
        isGif = false,
        isScreenshot = false,
    )
}
