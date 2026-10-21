package app.focale.ui.vault

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.focale.R
import app.focale.core.settings.AppSettings
import app.focale.ui.theme.FocaleTheme
import app.focale.vault.VaultAlbum
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Albums du coffre : l'appui long sur une tuile ouvre les options de son menu (renommer, sortir du
 * coffre, supprimer), chacune après son dialogue ; l'appui simple ouvre toujours l'album. Écran de
 * 600 dp de large, comme pour l'onglet Albums (`AlbumOptionsTest`) : à 400 dp, le dialogue du
 * renommage ne laisse jamais Compose inactif sous Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w600dp-h960dp-xxhdpi")
class VaultAlbumOptionsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val vacances = VaultAlbum(id = 2, name = "Vacances 2025", count = 57, coverThumbFileName = null, sourcePath = null, createdAt = 3)
    private val papiers = VaultAlbum(id = 3, name = "Papiers", count = 0, coverThumbFileName = null, sourcePath = null, createdAt = 2)

    private val opened = ArrayList<Long>()
    private val renamed = ArrayList<Pair<Long, String>>()
    private val restored = ArrayList<Long>()
    private val deleted = ArrayList<Long>()

    private val restoreLabel get() = context.getString(R.string.vault_album_restore_action)
    private val deleteLabel get() = context.getString(R.string.album_delete_title)

    private fun show() {
        compose.setContent {
            FocaleTheme(AppSettings(dynamicColor = false)) {
                VaultAlbumsShelf(
                    albums = listOf(vacances, papiers),
                    onOpen = { opened += it.id },
                    onRename = { album, name -> renamed += album.id to name },
                    onRestore = { restored += it.id },
                    onDelete = { deleted += it.id },
                )
            }
        }
    }

    private fun longPress(name: String) = compose.onNodeWithText(name).performTouchInput { longClick() }

    @Test
    fun `sortir l'album du coffre, après confirmation`() {
        show()
        longPress("Vacances 2025")
        compose.onNodeWithText(restoreLabel).performClick()
        compose.onNodeWithText(context.getString(R.string.vault_album_restore_title, "Vacances 2025")).assertExists()
        compose.onNodeWithText(context.getString(R.string.vault_album_restore_confirm)).performClick()
        compose.waitForIdle()

        assertEquals(listOf(2L), restored)
        assertEquals(emptyList<Long>(), opened)
    }

    @Test
    fun `renommer puis supprimer l'album, après confirmation`() {
        show()
        longPress("Vacances 2025")
        compose.onNodeWithText(context.getString(R.string.album_rename)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Été")
        compose.onNodeWithText(context.getString(R.string.action_rename)).performClick()

        longPress("Papiers")
        compose.onNodeWithText(deleteLabel).performClick()
        compose.onNodeWithText(context.getString(R.string.vault_album_delete_message)).assertExists()
        compose.onNodeWithText(context.getString(R.string.action_delete)).performClick()
        compose.waitForIdle()

        assertEquals(listOf(2L to "Été"), renamed)
        assertEquals(listOf(3L), deleted)
    }

    @Test
    fun `un album vide ne sort pas du coffre, l'appui simple l'ouvre`() {
        show()
        longPress("Papiers")
        compose.onNodeWithText(restoreLabel).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.action_cancel)).assertDoesNotExist()

        compose.onNodeWithText(context.getString(R.string.album_rename)).performClick()
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        compose.onNodeWithText("Vacances 2025").performClick()
        compose.waitForIdle()
        assertEquals(listOf(2L), opened)
    }

    @Test
    fun `TalkBack propose les options de la tuile`() {
        show()
        val label = context.getString(R.string.album_options)
        compose.onNodeWithText("Vacances 2025").assert(
            SemanticsMatcher("appui long « $label »") { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == label },
        ).assert(
            SemanticsMatcher("action « $label »") { node -> node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().any { it.label == label } },
        )
    }
}
