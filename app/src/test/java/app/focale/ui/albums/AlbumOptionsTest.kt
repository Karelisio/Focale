package app.focale.ui.albums

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.focale.R
import app.focale.core.model.Album
import app.focale.core.settings.AppSettings
import app.focale.ui.theme.FocaleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Onglet Albums : l'appui long sur un album ou un dossier ouvre ses options (feuille, retour
 * haptique), l'appui simple ouvre toujours l'album ; renommage, dossier masqué dans Photos,
 * suppression confirmée, coffre-fort et diaporama ; accès par TalkBack. Écran haut : toutes les
 * cartes sont affichées, hors du bouton « Nouvel album ». Écran de 600 dp de large : à 400 dp,
 * Robolectric remesure sans fin la fenêtre d'un dialogue qui contient un champ de texte (celui du
 * renommage), et Compose n'est jamais inactif.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w600dp-h1200dp-xxhdpi")
class AlbumOptionsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val vacances = Album.User(id = 7, name = "Vacances", count = 57, cover = null)
    private val projets = Album.User(id = 8, name = "Projets", count = 0, cover = null)
    private val camera = Album.Folder(bucketId = 11, name = "Camera", relativePath = "DCIM/Camera/", count = 1_284, cover = null)
    private val whatsapp = Album.Folder(bucketId = 12, name = "WhatsApp Images", relativePath = "Pictures/WhatsApp Images/", count = 342, cover = null)

    private val opened = ArrayList<Album>()
    private val slideshows = ArrayList<Album>()
    private val renamed = ArrayList<Pair<Long, String>>()
    private val deleted = ArrayList<Long>()
    private val hidden = ArrayList<Pair<Long, Boolean>>()
    private val movedToVault = ArrayList<Album>()
    private val haptics = ArrayList<HapticFeedbackType>()

    private val optionsLabel get() = context.getString(R.string.album_options)
    private val slideshowLabel get() = context.getString(R.string.action_slideshow)
    private val renameLabel get() = context.getString(R.string.album_rename)
    private val deleteLabel get() = context.getString(R.string.album_delete_title)
    private val hideLabel get() = context.getString(R.string.folder_hide_from_timeline)
    private val showLabel get() = context.getString(R.string.folder_show_in_timeline)
    private val vaultLabel get() = context.getString(R.string.vault_album_move_action)

    private fun show() {
        compose.setContent {
            FocaleTheme(AppSettings(dynamicColor = false)) {
                CompositionLocalProvider(LocalHapticFeedback provides HapticsRecorder(haptics)) {
                    AlbumsContent(
                        counts = CollectionCounts(),
                        people = emptyList(),
                        smartAlbums = emptyList(),
                        userAlbums = listOf(vacances, projets),
                        folders = listOf(camera, whatsapp),
                        hiddenFolders = setOf(whatsapp.bucketId),
                        snackbarHostState = SnackbarHostState(),
                        onOpenCollection = { _, _, _ -> },
                        onOpenTrash = {},
                        onOpenPerson = {},
                        onSeeAllPeople = {},
                        onOpenAlbum = { opened += it },
                        onCreateAlbum = {},
                        onSlideshow = { slideshows += it },
                        onRename = { album, name -> renamed += album.id to name },
                        onDelete = { deleted += it.id },
                        onHiddenChange = { folder, value -> hidden += folder.bucketId to value },
                        onMoveToVault = { movedToVault += it },
                    )
                }
            }
        }
    }

    private fun longPress(name: String) = compose.onNodeWithText(name).performTouchInput { longClick() }

    @Test
    fun `un appui long ouvre la feuille d'options de l'album, avec un retour haptique`() {
        show()
        longPress("Vacances")

        // En tête : couverture, nom et nombre d'éléments, comme sur la carte.
        compose.onAllNodesWithText(context.resources.getQuantityString(R.plurals.media_count, 57, 57)).assertCountEquals(2)
        listOf(slideshowLabel, renameLabel, vaultLabel, deleteLabel).forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText(hideLabel).assertDoesNotExist()
        assertEquals(listOf(HapticFeedbackType.LongPress), haptics)
        assertEquals(emptyList<Album>(), opened)
    }

    @Test
    fun `un appui simple ouvre toujours l'album`() {
        show()
        compose.onNodeWithText("Camera").performClick()
        compose.waitForIdle()

        assertEquals(listOf<Album>(camera), opened)
        compose.onNodeWithText(vaultLabel).assertDoesNotExist()
    }

    @Test
    fun `renommer l'album depuis sa feuille`() {
        show()
        longPress("Vacances")
        compose.onNodeWithText(renameLabel).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Été 2026")
        compose.onNodeWithText(context.getString(R.string.action_rename)).performClick()
        compose.waitForIdle()

        assertEquals(listOf(7L to "Été 2026"), renamed)
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText(renameLabel).assertDoesNotExist()
    }

    @Test
    fun `la suppression de l'album est confirmée`() {
        show()
        longPress("Vacances")
        compose.onNodeWithText(deleteLabel).performClick()
        compose.onNodeWithText(context.getString(R.string.album_delete_message)).assertExists()
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        compose.waitForIdle()
        assertEquals(emptyList<Long>(), deleted)

        longPress("Vacances")
        compose.onNodeWithText(deleteLabel).performClick()
        compose.onNodeWithText(context.getString(R.string.action_delete)).performClick()
        compose.waitForIdle()

        assertEquals(listOf(7L), deleted)
        compose.onNodeWithText(context.getString(R.string.album_delete_message)).assertDoesNotExist()
    }

    @Test
    fun `un dossier se masque dans Photos, ou s'y affiche s'il l'est`() {
        show()
        longPress("Camera")
        // Ni renommage ni suppression pour un dossier de l'appareil.
        compose.onNodeWithText(renameLabel).assertDoesNotExist()
        compose.onNodeWithText(deleteLabel).assertDoesNotExist()
        compose.onNodeWithText(hideLabel).performClick()

        longPress("WhatsApp Images")
        compose.onNodeWithText(showLabel).performClick()
        // L'option s'applique une fois la feuille refermée.
        compose.waitForIdle()

        assertEquals(listOf(11L to true, 12L to false), hidden)
        compose.onNodeWithText(showLabel).assertDoesNotExist()
    }

    @Test
    fun `diaporama et coffre-fort reçoivent l'album`() {
        show()
        longPress("Vacances")
        compose.onNodeWithText(slideshowLabel).performClick()
        longPress("Camera")
        compose.onNodeWithText(vaultLabel).performClick()
        compose.waitForIdle()

        assertEquals(listOf<Album>(vacances), slideshows)
        assertEquals(listOf<Album>(camera), movedToVault)
    }

    @Test
    fun `un album vide ne propose ni diaporama ni coffre-fort`() {
        show()
        longPress("Projets")

        compose.onNodeWithText(renameLabel).assertExists()
        compose.onNodeWithText(deleteLabel).assertExists()
        compose.onNodeWithText(slideshowLabel).assertDoesNotExist()
        compose.onNodeWithText(vaultLabel).assertDoesNotExist()
    }

    @Test
    fun `TalkBack propose les options par l'appui long et une action personnalisée`() {
        show()
        val card = compose.onNodeWithText("Vacances")
        card.assert(
            SemanticsMatcher("appui long « $optionsLabel »") { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == optionsLabel },
        )
        val action = card.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == optionsLabel }
        compose.runOnIdle { action.action() }

        compose.onNodeWithText(renameLabel).assertExists()
        assertEquals(emptyList<Album>(), opened)
    }

    /** Retours haptiques joués, à la place du vibreur. */
    private class HapticsRecorder(private val played: MutableList<HapticFeedbackType>) : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            played += hapticFeedbackType
        }
    }
}
