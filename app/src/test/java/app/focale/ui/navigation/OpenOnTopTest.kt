package app.focale.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Photo demandée par le widget ou une autre application alors que la visionneuse est déjà ouverte
 * sur une autre : c'est elle qui s'affiche. La visionneuse lit sa photo dans sa route une fois, à
 * la création de son ViewModel, comme [Viewer] ici ; vu en 1.10.0 sur l'émulateur, la photo
 * précédente restait affichée (`launchSingleTop` réutilisait l'écran et son ViewModel).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenOnTopTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Serializable
    data object Gallery

    @Serializable
    data class Photo(val mediaId: Long)

    /** Comme ViewerViewModel : la photo de départ vient de la route, lue à la création. */
    class Viewer(handle: SavedStateHandle) : ViewModel() {
        val mediaId = handle.toRoute<Photo>().mediaId
    }

    private lateinit var nav: NavHostController

    private fun show(start: Any) {
        compose.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = start) {
                composable<Gallery> { Text("galerie") }
                composable<Photo> {
                    val viewer = viewModel { Viewer(createSavedStateHandle()) }
                    Text("photo ${viewer.mediaId}")
                }
            }
        }
    }

    @Test
    fun `la visionneuse ouverte sur une autre photo est remplacée par la photo demandée`() {
        show(start = Gallery)
        compose.runOnIdle { nav.openOnTop(Photo(1)) }
        compose.onNodeWithText("photo 1").assertExists()

        compose.runOnIdle { nav.openOnTop(Photo(2)) }

        compose.onNodeWithText("photo 2").assertExists()
        compose.onNodeWithText("photo 1").assertDoesNotExist()
        // Remplacée, pas empilée : le retour ramène à la galerie.
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithText("galerie").assertExists()
    }

    @Test
    fun `depuis un autre écran, la photo s'ouvre par-dessus`() {
        show(start = Gallery)

        compose.runOnIdle { nav.openOnTop(Photo(3)) }

        compose.onNodeWithText("photo 3").assertExists()
        compose.runOnIdle { assertTrue(nav.previousBackStackEntry?.destination?.hasRoute<Gallery>() == true) }
    }

    @Test
    fun `une visionneuse racine reste dessous, son retour refermant l'activité`() {
        show(start = Photo(1))

        compose.runOnIdle { nav.openOnTop(Photo(2)) }

        compose.onNodeWithText("photo 2").assertExists()
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithText("photo 1").assertExists()
    }
}
