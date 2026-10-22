package app.focale.screenshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.focale.cast.CastState
import app.focale.cast.CastTarget
import app.focale.cast.TvStatus
import app.focale.cast.display.ExternalDisplay
import app.focale.cast.dlna.DlnaRenderer
import app.focale.cast.dlna.DlnaService
import app.focale.core.settings.AppSettings
import app.focale.core.settings.ThemeMode
import app.focale.ui.cast.CastSheetContent
import app.focale.ui.theme.FocaleTheme
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Captures de référence de la feuille « Diffuser » : choix d'un appareil pendant la recherche des
 * TV, en français et en thème clair ; diffusion en cours vers une TV, avec son volume, en anglais
 * et en thème sombre (couleurs Focale). La feuille est reproduite sans sa fenêtre : poignée, forme
 * et contenu. Régénérer avec `./gradlew recordRoborazziStandardDebug` quand le changement est voulu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class CastScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private val samsung = renderer("uuid:samsung", "[TV] Samsung Q60 Series (55)", "Samsung Electronics", "QE55Q60TAU", volume = true)
    private val lg = renderer("uuid:lg", "[LG] webOS TV OLED55C1PUB", "LG Electronics", "OLED55C1PUB", volume = true)
    private val kodi = renderer("uuid:kodi", "Kodi (salon)", "XBMC Foundation", "Kodi", volume = false)

    @Test
    @Config(qualifiers = "fr-w400dp-h800dp-xxhdpi")
    fun choixAppareilClair() = capture(
        dark = false,
        state = CastState(screens = listOf(ExternalDisplay(2, "HDMI")), tvs = listOf(lg, kodi, samsung), searching = true),
    )

    @Test
    fun diffusionEnCoursSombre() = capture(
        dark = true,
        state = CastState(
            tvs = listOf(lg, samsung),
            target = CastTarget.Tv(samsung),
            tvStatus = TvStatus.READY,
            volume = 35,
        ),
    )

    private fun capture(dark: Boolean, state: CastState) {
        compose.setContent {
            FocaleTheme(AppSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false)) {
                Surface(
                    color = BottomSheetDefaults.ContainerColor,
                    shape = BottomSheetDefaults.ExpandedShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        BottomSheetDefaults.DragHandle()
                        CastSheetContent(
                            state = state,
                            onSelectScreen = {},
                            onSelectTv = {},
                            onSearch = {},
                            onRequestPermission = {},
                            onStop = {},
                            onVolume = {},
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    private fun renderer(udn: String, name: String, manufacturer: String, model: String, volume: Boolean) = DlnaRenderer(
        udn = udn,
        name = name,
        manufacturer = manufacturer,
        model = model,
        location = "http://192.168.1.20:9197/dmr",
        avTransport = DlnaService("urn:schemas-upnp-org:service:AVTransport:1", "http://192.168.1.20:9197/upnp/control/AVTransport1"),
        renderingControl = if (volume) DlnaService("urn:schemas-upnp-org:service:RenderingControl:1", "http://192.168.1.20:9197/upnp/control/RenderingControl1") else null,
    )
}
