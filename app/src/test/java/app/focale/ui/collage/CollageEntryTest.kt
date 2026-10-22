package app.focale.ui.collage

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import app.focale.R
import app.focale.collage.CollagePick
import app.focale.collage.CollageSelection
import app.focale.core.settings.AppSettings
import app.focale.ui.actions.SelectionActionsBar
import app.focale.ui.navigation.CollageRoute
import app.focale.ui.navigation.MainRoute
import app.focale.ui.navigation.ViewerRoute
import app.focale.ui.navigation.openCollage
import app.focale.ui.navigation.openSavedCollage
import app.focale.ui.theme.FocaleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Entrée du collage depuis la sélection multiple : « Créer un collage » dans le menu de la barre
 * de sélection (commune à la timeline, aux albums, à la recherche et aux personnes) dès deux
 * éléments ; photos retenues dans l'ordre de la sélection, vidéos ignorées, neuf au plus.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class CollageEntryTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var collages = 0

    private fun showBar(count: Int) {
        compose.setContent {
            FocaleTheme(AppSettings(dynamicColor = false)) {
                SelectionActionsBar(
                    visible = true,
                    selectionCount = count,
                    onShare = {},
                    onFavorite = {},
                    onAddToAlbum = {},
                    onTrash = {},
                    onCopy = {},
                    onMove = {},
                    onRename = {},
                    onMoveToVault = {},
                    onSelectAll = {},
                    onCollage = { collages++ },
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.action_more)).performClick()
    }

    @Test
    fun `le menu de la sélection propose le collage dès deux éléments`() {
        showBar(count = 3)
        compose.onNodeWithText(context.getString(R.string.collage_action)).performClick()
        compose.waitForIdle()
        assertEquals(1, collages)
        // Menu refermé.
        compose.onNodeWithText(context.getString(R.string.collage_action)).assertDoesNotExist()
    }

    @Test
    fun `pas de collage pour un seul élément`() {
        showBar(count = 1)
        compose.onNodeWithText(context.getString(R.string.action_copy_to)).assertExists()
        compose.onNodeWithText(context.getString(R.string.collage_action)).assertDoesNotExist()
    }

    @Test
    fun `photos dans l'ordre de la sélection, vidéos ignorées`() {
        val items = listOf(
            CollageTestPhotos.item(7),
            CollageTestPhotos.item(3, video = true),
            CollageTestPhotos.item(5),
            CollageTestPhotos.item(9),
        )
        assertEquals(CollagePick.Ready(listOf(7L, 5L, 9L), truncated = false), CollageSelection.pick(items))
    }

    @Test
    fun `pas assez de photos, moins de deux une fois les vidéos ignorées`() {
        val items = listOf(CollageTestPhotos.item(1), CollageTestPhotos.item(2, video = true), CollageTestPhotos.item(3, video = true))
        assertEquals(CollagePick.NotEnough, CollageSelection.pick(items))
        assertEquals(CollagePick.NotEnough, CollageSelection.pick(emptyList()))
    }

    @Test
    fun `la route du collage transmet ses photos, puis cède la place à la visionneuse du collage enregistré`() {
        lateinit var nav: NavHostController
        var opened: CollageRoute? = null
        compose.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = MainRoute) {
                composable<MainRoute> { Text("grille") }
                composable<CollageRoute> { entry ->
                    opened = entry.toRoute<CollageRoute>()
                    Text("collage")
                }
                composable<ViewerRoute> { entry -> Text("visionneuse ${entry.toRoute<ViewerRoute>().mediaId}") }
            }
        }
        compose.runOnIdle { nav.openCollage(listOf(7L, 5L, 9L), truncated = true) }
        compose.onNodeWithText("collage").assertExists()
        assertEquals(CollageRoute(listOf(7L, 5L, 9L), truncated = true), opened)

        compose.runOnIdle { nav.openSavedCollage(CollageTestPhotos.item(42)) }
        compose.onNodeWithText("visionneuse 42").assertExists()
        // Le retour ramène à la grille, pas à l'écran de collage.
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithText("grille").assertExists()
    }

    @Test
    fun `au-delà de neuf photos, les neuf premières, et le collage le signale`() {
        val items = (1L..12L).map { CollageTestPhotos.item(it) }
        assertEquals(CollagePick.Ready((1L..9L).toList(), truncated = true), CollageSelection.pick(items))
        val message = context.resources.getQuantityString(R.plurals.collage_truncated, 9, 9)
        assertEquals("Collage limited to 9 photos: the first ones selected", message)
    }
}
