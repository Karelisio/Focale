package app.focale.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import me.saket.telephoto.flick.FlickToDismiss
import me.saket.telephoto.flick.FlickToDismissState
import me.saket.telephoto.flick.rememberFlickToDismissState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.media3.common.MediaItem as ExoMediaItem

/**
 * Double appui réel sur une page vidéo de la visionneuse, lue par un lecteur factice : sauts,
 * cumul, bornes, appui simple, et cohabitation avec les commandes, le pager, le glisser pour
 * fermer et TalkBack. L'horloge avance à la main : délais du double appui et de la série.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class VideoSeekGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private var taps = 0

    /** Page seule, plein écran, commandes masquées sauf [controlsVisible]. */
    private fun show(player: FakePlayer, controlsVisible: Boolean = false) {
        compose.setContent { Page(player, controlsVisible = controlsVisible) }
        compose.mainClock.autoAdvance = false
    }

    @Composable
    private fun Page(player: FakePlayer, isCurrent: Boolean = true, controlsVisible: Boolean = false) {
        VideoPlayerPage(
            key = "clip",
            isCurrent = isCurrent,
            autoplay = false,
            startMuted = true,
            controlsVisible = controlsVisible,
            onTap = { taps++ },
            playbackSpeed = VideoSpeeds.NORMAL,
            onPlaybackSpeedChange = {},
            mediaItem = { ExoMediaItem.fromUri("file:///clip.mp4") },
            poster = {},
            modifier = Modifier.testTag(VIDEO),
            contentDescription = "clip.mp4",
            playerFactory = { player },
        )
    }

    private fun touch(block: TouchInjectionScope.() -> Unit) {
        compose.onNodeWithTag(VIDEO).performTouchInput(block)
    }

    private fun TouchInjectionScope.right() = Offset(width * 0.75f, height / 2f)

    private fun TouchInjectionScope.left() = Offset(width * 0.25f, height / 2f)

    /**
     * Une image, comme à l'écran : l'état écrit hors composition (geste, délai écoulé) n'est
     * signalé qu'une fois la boucle principale passée, puis l'image le recompose.
     */
    private fun frame() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
    }

    /** Laisse passer [millis] d'horloge image par image : délais, effacement et animations. */
    private fun elapse(millis: Long) {
        val end = compose.mainClock.currentTime + millis
        while (compose.mainClock.currentTime < end) frame()
    }

    @Test
    fun `double appui à droite, 10 s de plus sans toucher aux commandes`() {
        val player = FakePlayer(positionMs = 20_000, durationMs = 60_000)
        show(player)

        touch { doubleClick(right()) }
        assertEquals(30_000L, player.currentPosition)
        frame()
        compose.onNodeWithText("+10 s").assertExists()

        // La série finie, le cumul s'efface ; le double appui n'a jamais compté pour un appui simple.
        elapse(SERIES_END)
        compose.onNodeWithText("+10 s").assertDoesNotExist()
        assertEquals(0, taps)
    }

    @Test
    fun `les appuis rapprochés cumulent, de l'autre côté le cumul repart`() {
        val player = FakePlayer(positionMs = 20_000, durationMs = 120_000)
        show(player)

        touch {
            doubleClick(right())
            advanceEventTime(250)
            click(right())
            advanceEventTime(250)
            click(right())
        }
        assertEquals(50_000L, player.currentPosition)
        frame()
        compose.onNodeWithText("+30 s").assertExists()

        touch {
            advanceEventTime(250)
            click(left())
        }
        assertEquals(40_000L, player.currentPosition)
        frame()
        compose.onNodeWithText("\u221210 s").assertExists()

        elapse(SERIES_END)
        assertEquals(0, taps)
    }

    @Test
    fun `double appui à gauche, borné au début de la vidéo`() {
        val start = FakePlayer(positionMs = 4_000, durationMs = 60_000)
        show(start)
        touch {
            doubleClick(left())
            advanceEventTime(250)
            click(left())
        }
        assertEquals(0L, start.currentPosition)
        frame()
        // Le second appui ne pouvait plus rien déplacer : le cumul ne bouge pas.
        compose.onNodeWithText("\u221210 s").assertExists()
    }

    @Test
    fun `en fin de vidéo, les sauts s'arrêtent à la fin sans erreur`() {
        val player = FakePlayer(positionMs = 55_000, durationMs = 60_000)
        show(player)
        touch {
            doubleClick(right())
            advanceEventTime(250)
            click(right())
            advanceEventTime(250)
            click(right())
        }
        assertEquals(60_000L, player.currentPosition)
        frame()
        compose.onNodeWithText("+10 s").assertExists()

        // Série finie, à la fin : un double appui de plus ne fait rien, ni n'affiche rien.
        elapse(SERIES_END)
        touch { doubleClick(right()) }
        frame()
        assertEquals(60_000L, player.currentPosition)
        compose.onNodeWithText("+10 s").assertDoesNotExist()
        elapse(SERIES_END)
        assertEquals(0, taps)
    }

    @Test
    fun `un appui simple affiche ou masque les commandes, une fois le double appui écarté`() {
        val player = FakePlayer(positionMs = 20_000, durationMs = 60_000)
        show(player)

        touch { click(right()) }
        assertEquals(0, taps)
        elapse(DOUBLE_TAP_END)
        assertEquals(1, taps)
        assertEquals(20_000L, player.currentPosition)

        // Après une série, une fois le délai passé, l'appui simple retrouve son rôle.
        touch { doubleClick(right()) }
        elapse(SERIES_END)
        touch { click(right()) }
        elapse(DOUBLE_TAP_END)
        assertEquals(2, taps)
        assertEquals(30_000L, player.currentPosition)
    }

    @Test
    fun `deux appuis sur des moitiés différentes restent deux appuis simples`() {
        val player = FakePlayer(positionMs = 20_000, durationMs = 60_000)
        show(player)

        touch {
            click(left())
            advanceEventTime(150)
            click(right())
        }
        assertEquals(1, taps)
        elapse(DOUBLE_TAP_END)
        assertEquals(2, taps)
        assertEquals(20_000L, player.currentPosition)
    }

    @Test
    fun `un doigt qui glisse n'est ni un appui ni un saut`() {
        val player = FakePlayer(positionMs = 20_000, durationMs = 60_000)
        show(player)

        touch {
            down(right())
            moveBy(Offset(0f, height / 10f))
            up()
            advanceEventTime(150)
            click(right())
        }
        elapse(DOUBLE_TAP_END)
        // Le glissement ne compte pas : le second appui reste un appui simple, sans saut.
        assertEquals(1, taps)
        assertEquals(20_000L, player.currentPosition)
    }

    @Test
    fun `deux doigts, comme pour tourner une photo, ne font ni appui ni saut`() {
        val player = FakePlayer(positionMs = 20_000, durationMs = 60_000)
        show(player)

        repeat(2) {
            touch {
                down(0, right())
                down(1, left())
                up(1)
                up(0)
                advanceEventTime(100)
            }
        }
        elapse(DOUBLE_TAP_END)
        assertEquals(0, taps)
        assertEquals(20_000L, player.currentPosition)
    }

    @Test
    fun `les commandes gardent leurs appuis, le curseur compris`() {
        val player = FakePlayer(positionMs = 20_000, durationMs = 60_000)
        show(player, controlsVisible = true)

        // Bouton de lecture, au centre (moitié droite) : lecture puis pause, aucun saut.
        compose.onNodeWithContentDescription("Play").performTouchInput { doubleClick() }
        assertEquals(listOf(true, false), player.playRequests.takeLast(2))
        assertEquals(20_000L, player.currentPosition)

        // Curseur : un appui aux trois quarts y place la lecture, sans saut de 10 s.
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .performTouchInput { doubleClick(Offset(width * 0.75f, height / 2f)) }
        assertTrue("position ${player.currentPosition}", player.currentPosition in 40_000L..50_000L)
        frame()
        compose.onNodeWithText("+10 s").assertDoesNotExist()
        elapse(SERIES_END)
        assertEquals(0, taps)
    }

    @Test
    fun `dans la visionneuse, double appui à la vidéo, balayage au pager, glisser vers le bas pour fermer`() {
        val player = FakePlayer(positionMs = 20_000, durationMs = 60_000)
        lateinit var pager: PagerState
        lateinit var flick: FlickToDismissState
        compose.setContent {
            flick = rememberFlickToDismissState(dismissThresholdRatio = 0.15f, rotateOnDrag = false)
            pager = rememberPagerState { 2 }
            // Comme la visionneuse : glisser pour fermer autour du pager.
            FlickToDismiss(state = flick) {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                    if (page == 0) Page(player, isCurrent = pager.settledPage == 0) else Box(Modifier.fillMaxSize())
                }
            }
        }

        touch { doubleClick(right()) }
        assertEquals(30_000L, player.currentPosition)
        compose.mainClock.advanceTimeBy(SERIES_END)

        // Glisser lent vers le bas, relâché à l'arrêt avant le seuil : ni appui, ni saut.
        touch {
            down(center)
            repeat(8) { moveBy(Offset(0f, height * 0.0125f), delayMillis = 50) }
        }
        assertTrue(flick.offsetFraction > 0f)
        touch {
            advanceEventTime(300)
            up()
        }
        compose.mainClock.advanceTimeBy(SERIES_END)

        // Balayage, en pleine série : page suivante, ni appui, ni saut.
        touch { doubleClick(right()) }
        touch { swipeLeft() }
        compose.waitForIdle()
        assertEquals(1, pager.settledPage)
        assertEquals(40_000L, player.positionMs)
        assertEquals(0, taps)
    }

    @Test
    fun `TalkBack propose d'avancer et de reculer de 10 s`() {
        val player = FakePlayer(positionMs = 20_000, durationMs = 60_000)
        show(player)

        compose.onNodeWithTag(VIDEO).performCustomAccessibilityActionWithLabel("Fast forward 10 seconds")
        assertEquals(30_000L, player.currentPosition)
        frame()
        compose.onNodeWithText("+10 s").assertExists()

        elapse(SERIES_END)
        compose.onNodeWithTag(VIDEO).performCustomAccessibilityActionWithLabel("Rewind 10 seconds")
        assertEquals(20_000L, player.currentPosition)
    }

    @Test
    @Config(qualifiers = "+fr")
    fun `actions TalkBack en français`() {
        val player = FakePlayer(positionMs = 20_000, durationMs = 60_000)
        show(player)

        compose.onNodeWithTag(VIDEO).performCustomAccessibilityActionWithLabel("Reculer de 10\u00a0secondes")
        assertEquals(10_000L, player.currentPosition)
        frame()
        compose.onNodeWithText("\u221210\u00a0s").assertExists()
        compose.onNodeWithTag(VIDEO).performCustomAccessibilityActionWithLabel("Avancer de 10\u00a0secondes")
        assertEquals(20_000L, player.currentPosition)
    }

    private companion object {
        const val VIDEO = "video"

        /** Au-delà du délai d'un double appui (300 ms). */
        const val DOUBLE_TAP_END = 400L

        /** Au-delà de la série et de l'effacement de son cumul. */
        const val SERIES_END = VideoSeekMath.SERIES_TIMEOUT_MS + 500L
    }
}
