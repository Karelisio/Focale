package app.focale.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Retour depuis un média ouvert pour une autre application : refermer l'activité, ou dépiler. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RootBackTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Serializable
    data object Gallery

    @Serializable
    data class Opened(val closeOnBack: Boolean)

    private lateinit var nav: NavHostController

    private fun show(start: Any) {
        compose.setContent {
            nav = rememberNavController()
            CompositionLocalProvider(LocalRootNavController provides nav) {
                NavHost(navController = nav, startDestination = start) {
                    composable<Gallery> { Text("galerie") }
                    composable<Opened> { entry ->
                        Button(onClick = rememberRootBack(entry.toRoute<Opened>().closeOnBack)) { Text("retour") }
                    }
                }
            }
        }
    }

    @Test
    fun `lancée pour une autre application, l'activité se referme au retour`() {
        show(start = Opened(closeOnBack = true))

        compose.onNodeWithText("retour").performClick()

        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun `le retour système referme aussi l'activité`() {
        show(start = Opened(closeOnBack = true))

        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun `reçue par l'activité déjà ouverte, l'ouverture se dépile vers l'écran courant`() {
        show(start = Gallery)
        compose.runOnIdle { nav.navigate(Opened(closeOnBack = false)) }

        compose.onNodeWithText("retour").performClick()

        compose.onNodeWithText("galerie").assertExists()
        assertFalse(compose.activity.isFinishing)
    }
}
