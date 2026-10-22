package app.focale.ui.settings

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.focale.R
import app.focale.backup.AutoBackupError
import app.focale.backup.AutoBackupFrequency
import app.focale.backup.AutoBackupSettings
import app.focale.backup.DataBackupSummary
import app.focale.core.settings.AppSettings
import app.focale.ui.theme.FocaleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Section « Sauvegarde des données » des réglages : ses trois actions et l'état de la sauvegarde
 * automatique ; résumé d'une sauvegarde vérifiée, avant sa restauration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class DataBackupSectionTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clicks = ArrayList<String>()
    private val actions = DataBackupActions(onBackup = { clicks += "sauvegarder" }, onRestore = { clicks += "restaurer" }, onAutoBackup = { clicks += "auto" })

    /** Section affichée avec le réglage [settings], qui peut changer ensuite. */
    private fun section(settings: AutoBackupSettings): MutableState<AutoBackupSettings> {
        val state = mutableStateOf(settings)
        compose.setContent {
            FocaleTheme(AppSettings(dynamicColor = false)) {
                Column { DataBackupSection(state.value, actions) }
            }
        }
        return state
    }

    @Test
    fun `chaque action de la section ouvre la sienne`() {
        section(AutoBackupSettings())
        compose.onNodeWithText(context.getString(R.string.data_backup_section)).assertExists()
        compose.onNodeWithText(context.getString(R.string.auto_backup_off)).assertExists()
        compose.onNodeWithText(context.getString(R.string.data_backup_now)).performClick()
        compose.onNodeWithText(context.getString(R.string.data_backup_restore)).performClick()
        compose.onNodeWithText(context.getString(R.string.auto_backup_title)).performClick()
        assertEquals(listOf("sauvegarder", "restaurer", "auto"), clicks)
    }

    @Test
    fun `la sauvegarde automatique montre sa fréquence et son dossier, ou son échec`() {
        val enabled = AutoBackupSettings(frequency = AutoBackupFrequency.WEEKLY, folder = FOLDER, folderName = "Sauvegardes")
        val settings = section(enabled)
        compose.onNodeWithText("Every week · Sauvegardes").assertExists()

        settings.value = enabled.copy(error = AutoBackupError.FOLDER_UNAVAILABLE, errorAt = FAILED_AT)
        compose.onNodeWithText("Failed on", substring = true).assertExists()
        compose.onNodeWithText("Every week · Sauvegardes").assertDoesNotExist()
    }

    @Test
    fun `le résumé annonce le contenu de la sauvegarde et les personnes remplacées, puis restaure`() {
        var confirmed = 0
        var dismissed = 0
        compose.setContent {
            FocaleTheme(AppSettings(dynamicColor = false)) {
                RestoreSummaryCard(SUMMARY, onConfirm = { confirmed++ }, onDismiss = { dismissed++ })
            }
        }
        compose.onNodeWithText("Created by Focale 1.9.0").assertExists()
        for (line in listOf("1,284 favorites", "12 albums", "37 people", "1 hidden folder")) compose.onNodeWithText(line).assertExists()
        compose.onNodeWithText(context.getString(R.string.data_restore_summary_merge)).assertExists()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.data_restore_summary_replace_people, 4, 4))
            .performScrollTo()
            .assertExists()
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        compose.onNodeWithText(context.getString(R.string.action_restore)).performClick()
        assertEquals(1, confirmed)
        assertEquals(1, dismissed)
    }

    @Test
    fun `sans personne sur ce téléphone, le résumé annonce leur création`() {
        compose.setContent {
            FocaleTheme(AppSettings(dynamicColor = false)) {
                RestoreSummaryCard(SUMMARY.copy(existingPeople = 0), onConfirm = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText(context.getString(R.string.data_restore_summary_new_people)).performScrollTo().assertExists()
        compose.onNodeWithText("replace those of this phone", substring = true).assertDoesNotExist()
    }

    private companion object {
        val FOLDER: Uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ASauvegardes")
        const val FAILED_AT = 1_792_584_000_000L
        val SUMMARY = DataBackupSummary(
            createdAt = 1_792_497_600_000L,
            appVersion = "1.9.0",
            favorites = 1_284,
            albums = 12,
            people = 37,
            hiddenFolders = 1,
            media = 20_431,
            faces = 18_762,
            searches = 25,
            existingPeople = 4,
            records = 60_000,
        )
    }
}
