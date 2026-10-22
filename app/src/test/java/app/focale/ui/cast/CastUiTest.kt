package app.focale.ui.cast

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.focale.R
import app.focale.cast.CastState
import app.focale.cast.CastTarget
import app.focale.cast.TvStatus
import app.focale.cast.display.ExternalDisplay
import app.focale.cast.dlna.DiscoveryProblem
import app.focale.cast.dlna.DlnaRenderer
import app.focale.cast.dlna.DlnaService
import app.focale.core.settings.AppSettings
import app.focale.ui.theme.FocaleTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bouton « Diffuser » (visible seulement quand il y a de quoi diffuser) et feuille de choix des
 * appareils : listes, état de la recherche, choix, arrêt, autorisation du réseau local.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class CastUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun text(id: Int, vararg args: Any): String = context.getString(id, *args)

    private val samsung = renderer("uuid:samsung", "[TV] Samsung Q60 Series (55)", "Samsung Electronics", "QE55Q60TAU")
    private val sony = renderer("uuid:sony", "KD-55XH9505", "Sony Corporation", "KD-55XH9505")
    private val hdmi = ExternalDisplay(2, "HDMI")

    @Test
    fun `bouton masqué sans écran ni TV, visible pendant une recherche et une diffusion`() {
        var state by mutableStateOf(CastState())
        var clicks = 0
        compose.setContent {
            FocaleTheme(AppSettings(dynamicColor = false)) { Row { CastButton(state, onClick = { clicks++ }) } }
        }
        compose.onNodeWithContentDescription(text(R.string.cast_action)).assertDoesNotExist()

        state = CastState(searching = true)
        compose.onNodeWithContentDescription(text(R.string.cast_action)).assertIsDisplayed().performClick()
        assertEquals(1, clicks)

        // Recherche finie sans rien trouver : le bouton repart.
        state = CastState(searching = false)
        compose.onNodeWithContentDescription(text(R.string.cast_action)).assertDoesNotExist()

        state = CastState(screens = listOf(hdmi))
        compose.onNodeWithContentDescription(text(R.string.cast_action)).assertIsDisplayed()

        state = CastState(tvs = listOf(samsung), target = CastTarget.Tv(samsung))
        compose.onNodeWithContentDescription(text(R.string.cast_action_active, samsung.name)).assertIsDisplayed()
    }

    private class Calls {
        var screen: ExternalDisplay? = null
        var tv: DlnaRenderer? = null
        var stopped: CastTarget? = null
        var searches = 0
        var permission = 0
        var volume: Int? = null
    }

    private var sheetState by mutableStateOf(CastState())
    private var blocked by mutableStateOf(false)
    private var shown = false

    /** Feuille de [state] ; une seule composition par test, l'état change ensuite. */
    private fun showSheet(state: CastState, calls: Calls, permissionBlocked: Boolean = false) {
        sheetState = state
        blocked = permissionBlocked
        if (shown) {
            compose.waitForIdle()
            return
        }
        shown = true
        compose.setContent {
            FocaleTheme(AppSettings(dynamicColor = false)) {
                CastSheetContent(
                    state = sheetState,
                    permissionBlocked = blocked,
                    onSelectScreen = { calls.screen = it },
                    onSelectTv = { calls.tv = it },
                    onSearch = { calls.searches++ },
                    onRequestPermission = { calls.permission++ },
                    onStop = { calls.stopped = it },
                    onVolume = { calls.volume = it },
                )
            }
        }
    }

    @Test
    fun `feuille, écrans et TV trouvés, choix d'un appareil`() {
        val calls = Calls()
        showSheet(CastState(screens = listOf(hdmi), tvs = listOf(sony, samsung), searching = true), calls)
        compose.onNodeWithText(text(R.string.cast_screens)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cast_tvs)).assertIsDisplayed()
        compose.onNodeWithText("Samsung Electronics · QE55Q60TAU").assertIsDisplayed()
        // Le modèle n'est pas répété quand il est aussi le nom.
        compose.onNodeWithText("Sony Corporation").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cast_privacy_note)).assertIsDisplayed()
        // Pas de relance pendant la recherche.
        compose.onNodeWithContentDescription(text(R.string.cast_search_again)).assertDoesNotExist()

        compose.onNodeWithText(samsung.name).performClick()
        assertEquals(samsung, calls.tv)
        compose.onNodeWithText("HDMI").performClick()
        assertEquals(hdmi, calls.screen)
    }

    @Test
    fun `feuille, diffusion en cours, arrêt et volume`() {
        val calls = Calls()
        showSheet(CastState(tvs = listOf(samsung), target = CastTarget.Tv(samsung), tvStatus = TvStatus.READY, volume = 30), calls)
        compose.onNodeWithText(text(R.string.cast_casting_to, samsung.name)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cast_tv_active_summary)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.cast_volume)).assertIsDisplayed()
        // La TV en cours est cochée ; la choisir à nouveau ne relance rien.
        compose.onNodeWithText(samsung.name).assertIsSelected().performClick()
        assertNull(calls.tv)
        compose.onNodeWithText(text(R.string.cast_stop)).performClick()
        assertEquals(CastTarget.Tv(samsung), calls.stopped)
    }

    @Test
    fun `feuille, écran externe en cours, sans TV dans la variante strict`() {
        val calls = Calls()
        showSheet(CastState(screens = listOf(hdmi), target = CastTarget.Screen(hdmi), tvSupported = false), calls)
        compose.onNodeWithText(text(R.string.cast_casting_to, "HDMI")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cast_screen_active_summary)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cast_tvs)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.cast_privacy_note)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.cast_stop)).performClick()
        assertEquals(CastTarget.Screen(hdmi), calls.stopped)
    }

    @Test
    fun `feuille, aucune TV, Wi-Fi absent, autorisation du réseau local`() {
        val calls = Calls()
        showSheet(CastState(), calls)
        compose.onNodeWithText(text(R.string.cast_none_found)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cast_search_again)).performClick()
        assertEquals(1, calls.searches)

        showSheet(CastState(problem = DiscoveryProblem.NO_WIFI), calls)
        compose.onNodeWithText(text(R.string.cast_no_wifi)).assertIsDisplayed()

        showSheet(CastState(problem = DiscoveryProblem.PERMISSION), calls)
        compose.onNodeWithText(text(R.string.cast_permission)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cast_permission_allow)).performClick()
        assertEquals(1, calls.permission)

        // Refus définitif : les réglages de l'application.
        showSheet(CastState(problem = DiscoveryProblem.PERMISSION), calls, permissionBlocked = true)
        compose.onNodeWithText(text(R.string.cast_permission_settings)).performClick()
        assertEquals(2, calls.permission)
    }

    private fun renderer(udn: String, name: String, manufacturer: String, model: String) = DlnaRenderer(
        udn = udn,
        name = name,
        manufacturer = manufacturer,
        model = model,
        location = "http://192.168.1.20:9197/dmr",
        avTransport = DlnaService("urn:schemas-upnp-org:service:AVTransport:1", "http://192.168.1.20:9197/upnp/control/AVTransport1"),
        renderingControl = null,
    )
}
