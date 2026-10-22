package app.focale.ui.editor

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.focale.core.database.FocaleDatabase
import app.focale.core.image.SeedColorExtractor
import app.focale.core.mediastore.ConsentRequester
import app.focale.core.mediastore.MediaStoreSource
import app.focale.core.mediastore.MediaSyncManager
import app.focale.core.permissions.MediaAccessManager
import app.focale.data.ImageEditor
import app.focale.data.MediaDetailsRepository
import app.focale.data.MediaOperations
import app.focale.data.MediaRepository
import app.focale.edit.Adjustment
import app.focale.edit.BlurArea
import app.focale.edit.BlurShape
import app.focale.edit.DrawTool
import app.focale.edit.DrawingStroke
import app.focale.edit.EditState
import app.focale.edit.FilterPreset
import app.focale.edit.NormRect
import app.focale.edit.SaveMode
import app.focale.people.FaceLocator
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * « Tout annuler » : par le ViewModel de l'éditeur (vraies dépendances, base en mémoire, sans
 * photo chargée), tout revient à l'original en une étape qu'« Annuler » défait ; puis le bouton
 * de la barre du haut et sa confirmation légère.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w600dp-h1200dp-mdpi")
class EditorResetTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var database: FocaleDatabase
    private lateinit var viewModel: EditorViewModel

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, FocaleDatabase::class.java).allowMainThreadQueries().build()
        val access = MediaAccessManager(context)
        val sync = MediaSyncManager(
            context = context,
            database = database,
            dao = database.mediaDao(),
            source = MediaStoreSource(context.contentResolver),
            accessManager = access,
            stateStore = PreferenceDataStoreFactory.create { File(folder.root, "state.preferences_pb") },
            scope = scope,
            io = Dispatchers.IO,
        )
        val operations = MediaOperations(
            context, context.contentResolver, ConsentRequester(), sync, database.trashDao(), database.favoriteDao(),
            database.mediaDao(), Dispatchers.IO,
        )
        viewModel = EditorViewModel(
            savedStateHandle = SavedStateHandle(mapOf("mediaId" to 1L)),
            mediaRepository = MediaRepository(database.mediaDao(), Dispatchers.IO, database.invalidationTracker),
            editor = ImageEditor(context, context.contentResolver, operations, MediaDetailsRepository(context, access, Dispatchers.IO), Dispatchers.IO),
            seedExtractor = SeedColorExtractor(context, Dispatchers.Default),
            faceLocator = FaceLocator(),
            dispatcher = Dispatchers.Default,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    private val state: EditState get() = viewModel.state.value

    /** Une modification de chaque sorte : rotation, recadrage, filtre, réglage, trait, texte, zone floutée. */
    private fun editEverything() {
        viewModel.rotate()
        viewModel.beginGesture()
        viewModel.dragCrop(NormRect(0.1f, 0.1f, 0.9f, 0.8f))
        viewModel.setFilter(FilterPreset.MONO)
        viewModel.selectAdjustment(Adjustment.WARMTH)
        viewModel.beginGesture()
        viewModel.setAdjustmentValue(0.4f)
        viewModel.addStroke(DrawingStroke(DrawTool.PEN, INK, 0.01f, floatArrayOf(0.1f, 0.1f, 0.5f, 0.5f)))
        viewModel.selectTool(EditorTool.TEXT)
        viewModel.textTool.startNew(0.5f, 0.5f)
        viewModel.textTool.type("Bonjour")
        viewModel.textTool.confirm()
        viewModel.selectTool(EditorTool.BLUR)
        viewModel.blurTool.add(BlurArea.Box(BlurShape.ELLIPSE, 0.3f, 0.3f, 0.1f, 0.1f))
    }

    @Test
    fun `Tout annuler ramène l'original en une étape, qu'Annuler défait et que Rétablir refait`() {
        editEverything()
        val edited = state
        assertEquals(7, edited.changeCount)
        assertEquals(1, edited.quarterTurns)
        viewModel.blurTool.select(edited.blurs.single().id)

        viewModel.resetAll()
        assertEquals(EditState(), state)
        assertFalse(viewModel.hasChanges)
        assertNull(viewModel.blurTool.selected.value)
        assertTrue(viewModel.canUndo.value)

        viewModel.undo()
        assertEquals(edited, state)
        viewModel.redo()
        assertEquals(EditState(), state)
        viewModel.undo()
        // Les étapes d'avant restent : une à une jusqu'à l'original.
        var steps = 0
        while (viewModel.canUndo.value) {
            viewModel.undo()
            steps++
        }
        assertEquals(7, steps)
        assertEquals(EditState(), state)
    }

    @Test
    fun `une saisie en cours est validée puis annulée avec le reste, et revient avec Annuler`() {
        viewModel.setFilter(FilterPreset.SEPIA)
        viewModel.selectTool(EditorTool.TEXT)
        viewModel.textTool.startNew(0.2f, 0.2f)
        viewModel.textTool.type("Encore")
        assertTrue(viewModel.hasChanges)
        viewModel.resetAll()
        assertEquals(EditState(), state)
        assertNull(viewModel.textTool.draft.value)
        viewModel.undo()
        assertEquals("Encore", state.texts.single().text)
        assertEquals(FilterPreset.SEPIA, state.filter)
    }

    @Test
    fun `Annuler pendant une saisie l'abandonne seulement, enregistrer la valide`() {
        viewModel.setFilter(FilterPreset.SEPIA)
        viewModel.selectTool(EditorTool.TEXT)
        viewModel.textTool.startNew(0.2f, 0.2f)
        viewModel.textTool.type("Brouillon")
        viewModel.undo()
        assertNull(viewModel.textTool.draft.value)
        assertEquals(FilterPreset.SEPIA, state.filter)
        assertTrue(state.texts.isEmpty())
        // Changer d'outil, ou enregistrer, valide la saisie.
        viewModel.textTool.startNew(0.2f, 0.2f)
        viewModel.textTool.type("Gardé")
        viewModel.selectTool(EditorTool.CROP)
        assertEquals("Gardé", state.texts.single().text)
        viewModel.selectTool(EditorTool.TEXT)
        viewModel.textTool.startNew(0.6f, 0.6f)
        viewModel.textTool.type("Aussi")
        viewModel.save(SaveMode.COPY)
        assertEquals(listOf("Gardé", "Aussi"), state.texts.map { it.text })
    }

    @Test
    fun `peu de modifications, le bouton agit aussitôt, beaucoup, une confirmation d'abord`() {
        var changes by mutableStateOf(2)
        var resets = 0
        compose.setContent {
            EditorTopBar(
                canUndo = true,
                canRedo = false,
                canSave = true,
                canReplace = true,
                onClose = {},
                onUndo = {},
                onRedo = {},
                onSave = {},
                changes = changes,
                onReset = { resets++ },
            )
        }
        compose.onNodeWithContentDescription("Undo all").performClick()
        compose.waitForIdle()
        assertEquals(1, resets)
        compose.onNodeWithText("Undo all edits?").assertDoesNotExist()

        changes = RESET_CONFIRMATION + 2
        compose.onNodeWithContentDescription("Undo all").performClick()
        compose.onNodeWithText("7 edits will be removed. Undo brings them back.").assertExists()
        compose.onNodeWithText("Keep").performClick()
        compose.waitForIdle()
        assertEquals(1, resets)
        compose.onNodeWithContentDescription("Undo all").performClick()
        compose.onNodeWithText("Undo all").performClick()
        compose.waitForIdle()
        assertEquals(2, resets)
        compose.onNodeWithText("Undo all edits?").assertDoesNotExist()

        changes = 0
        compose.onNodeWithContentDescription("Undo all").assertIsNotEnabled()
    }

    private companion object {
        const val INK = -0x1
    }
}
