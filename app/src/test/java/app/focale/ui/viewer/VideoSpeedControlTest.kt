package app.focale.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.media3.common.MediaItem as ExoMediaItem

/**
 * Vitesse de lecture des vidéos, dans une visionneuse montée comme celles de l'application
 * (vitesse retenue par l'écran, pages vidéo dans un pager) et lue par des lecteurs factices :
 * menu et choix, vidéo suivante, recréation de l'activité, sortie de la visionneuse, sauts
 * de 10 s et TalkBack.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class VideoSpeedControlTest {

    @get:Rule
    val compose = createComposeRule()

    /** Lecteurs créés par les pages, dans l'ordre : un neuf pour chaque page devenue courante. */
    private val players = mutableListOf<FakePlayer>()

    private var taps = 0

    @Serializable
    data object Gallery

    @Serializable
    data object Opened

    /** Visionneuse de [pager].pageCount vidéos, commandes affichées ; en pause, la position ne bouge qu'aux sauts. */
    @Composable
    private fun Viewer(pager: PagerState = rememberPagerState { 1 }) {
        var speed by rememberVideoSpeed()
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            VideoPlayerPage(
                key = page,
                isCurrent = page == pager.settledPage,
                autoplay = false,
                startMuted = false,
                controlsVisible = true,
                onTap = { taps++ },
                playbackSpeed = speed,
                onPlaybackSpeedChange = { speed = it },
                mediaItem = { ExoMediaItem.fromUri("file:///clip$page.mp4") },
                poster = {},
                modifier = Modifier.testTag("$VIDEO$page"),
                contentDescription = "clip$page.mp4",
                playerFactory = { FakePlayer(positionMs = 20_000, durationMs = 60_000).also(players::add) },
            )
        }
    }

    private fun speedButton(label: String) = compose.onNodeWithContentDescription("Playback speed: $label")

    /** Ouvre le menu depuis le bouton (vitesse en cours [from]) et choisit [label]. */
    private fun choose(label: String, from: String = "1×") {
        speedButton(from).performClick()
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
    }

    @Test
    fun `une vidéo s'ouvre à 1×, choisir 1,5× règle le lecteur sans changer la hauteur du son`() {
        compose.setContent { Viewer() }
        val player = players.single()
        assertEquals(PlaybackParameters.DEFAULT, player.playbackParameters)

        choose("1.5×")

        assertEquals(PlaybackParameters(1.5f, 1f), player.playbackParameters)
        // Menu refermé, bouton renommé ; ni appui sur la vidéo, ni saut.
        compose.onNodeWithText("0.25×").assertDoesNotExist()
        speedButton("1.5×").assertExists()
        assertEquals(0, taps)
        assertEquals(20_000L, player.currentPosition)

        // Retour à la normale.
        choose("1×", from = "1.5×")
        assertEquals(PlaybackParameters.DEFAULT, player.playbackParameters)
        speedButton("1×").assertExists()
    }

    @Test
    fun `le menu propose les huit vitesses, la vitesse en cours cochée`() {
        compose.setContent { Viewer() }

        speedButton("1×").performClick()
        val labels = listOf("0.25×", "0.5×", "0.75×", "1×", "1.25×", "1.5×", "1.75×", "2×")
        labels.forEach { label ->
            compose.onNodeWithText(label)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .apply { if (label == "1×") assertIsSelected() else assertIsNotSelected() }
        }
        compose.onNodeWithText("0.5×").performClick()
        compose.waitForIdle()

        speedButton("0.5×").performClick()
        compose.onNodeWithText("0.5×").assertIsSelected()
        compose.onNodeWithText("1×").assertIsNotSelected()
    }

    @Test
    fun `TalkBack lit le nom du bouton, le titre du menu, et relit le bouton à chaque vitesse`() {
        compose.setContent { Viewer() }

        speedButton("1×")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Playback speed")).assertExists()
        compose.onNodeWithText("2×").performClick()
        compose.waitForIdle()

        speedButton("2×").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Playback speed")).assertDoesNotExist()
    }

    @Test
    fun `la vitesse choisie vaut pour la vidéo suivante de la visionneuse`() {
        lateinit var pager: PagerState
        compose.setContent {
            pager = rememberPagerState { 2 }
            Viewer(pager)
        }
        choose("2×")
        val first = players.single()
        assertEquals(2f, first.playbackParameters.speed)

        compose.onNodeWithTag("${VIDEO}0").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals(1, pager.settledPage)
        val second = players.last()
        assertNotSame(first, second)
        assertTrue(first.released)
        assertEquals(PlaybackParameters(2f, 1f), second.playbackParameters)
        speedButton("2×").assertExists()
    }

    @Test
    fun `la vitesse survit à une recréation de l'activité, avec un lecteur neuf`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Viewer() }
        choose("1.5×")

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertEquals(2, players.size)
        assertEquals(1.5f, players.last().playbackParameters.speed)
        speedButton("1.5×").assertExists()
    }

    @Test
    fun `quitter la visionneuse la remet à 1× pour la prochaine ouverture`() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = Gallery) {
                composable<Gallery> { Box(Modifier.fillMaxSize()) }
                composable<Opened> { Viewer() }
            }
        }
        compose.runOnIdle { nav.navigate(Opened) }
        choose("1.5×")
        assertEquals(1.5f, players.single().playbackParameters.speed)

        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()
        assertTrue(players.single().released)
        compose.runOnIdle { nav.navigate(Opened) }
        compose.waitForIdle()

        assertEquals(2, players.size)
        assertEquals(PlaybackParameters.DEFAULT, players.last().playbackParameters)
        speedButton("1×").assertExists()
    }

    @Test
    fun `à 2×, un double appui saute toujours de 10 s de vidéo`() {
        compose.setContent { Viewer() }
        choose("2×")

        compose.onNodeWithTag("${VIDEO}0").performTouchInput { doubleClick(Offset(width * 0.75f, height * 0.3f)) }

        assertEquals(30_000L, players.single().currentPosition)
        assertEquals(0, taps)
    }

    /** Plus petite fenêtre d'une activité redimensionnable (220 dp) : 88 dp seulement au-dessus du bas du bouton. */
    @Test
    @Config(qualifiers = "w800dp-h220dp")
    fun `dans la plus petite fenêtre, le menu garde deux options en vue et s'ouvre sur la vitesse en cours`() {
        compose.setContent { Viewer() }

        speedButton("1×").performClick()
        compose.waitForIdle()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Playback speed")).assertHeightIsAtLeast(96.dp)
        compose.onNodeWithText("1×").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("0.25×").assertIsNotDisplayed()
        // Les autres vitesses au défilement.
        compose.onNodeWithText("1.75×").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1.75f, players.single().playbackParameters.speed)

        speedButton("1.75×").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("1.75×").assertIsDisplayed().assertIsSelected()
    }

    @Test
    @Config(qualifiers = "+fr")
    fun `en français, virgule décimale et bouton nommé « Vitesse de lecture »`() {
        compose.setContent { Viewer() }

        compose.onNodeWithContentDescription("Vitesse de lecture : 1×").performClick()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Vitesse de lecture")).assertExists()
        compose.onNodeWithText("0,75×").assertExists()
        compose.onNodeWithText("1,5×").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Vitesse de lecture : 1,5×").assertExists()
        assertEquals(1.5f, players.single().playbackParameters.speed)
    }

    @Test
    @androidx.annotation.OptIn(UnstableApi::class)
    fun `ExoPlayer ne change que le tempo, la hauteur du son reste celle de la vidéo`() {
        val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).setUsePlatformDiagnostics(false).build()
        try {
            player.setPlaybackSpeed(1.75f)
            assertEquals(PlaybackParameters(1.75f, 1f), player.playbackParameters)
        } finally {
            player.release()
        }
    }

    private companion object {
        const val VIDEO = "video"
    }
}
