package app.focale.core.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Réglages repris par la sauvegarde des données : liste blanche complète et explicite, valeurs
 * sauvegardées, remplacement à la restauration, valeurs d'une autre version.
 */
class SettingsBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `chaque clé des réglages est soit sauvegardée, soit écartée explicitement`() {
        // Toutes les clés déclarées : une clé ajoutée aux réglages sans être classée fait échouer ce test.
        val declared = SettingsRepository.Keys::class.java.declaredFields
            .filter { Preferences.Key::class.java.isAssignableFrom(it.type) }
            .map { field ->
                field.isAccessible = true
                field.get(SettingsRepository.Keys) as Preferences.Key<*>
            }
        assertTrue(declared.size > 20)
        val backedUp = SettingsRepository.BACKED_UP.map { it.key }
        assertEquals("clé en double", backedUp.size, backedUp.toSet().size)
        assertEquals(emptySet<Preferences.Key<*>>(), backedUp.toSet() intersect SettingsRepository.NOT_BACKED_UP)
        assertEquals(declared.toSet(), backedUp.toSet() + SettingsRepository.NOT_BACKED_UP)
        // L'état propre à l'appareil n'est pas dans ces réglages.
        val names = declared.map { it.name }
        for (local in listOf("permission", "download", "whats_new", "default_app", "last_sync")) {
            assertFalse(local, names.any { local in it })
        }
    }

    @Test
    fun `seuls les réglages choisis sont sauvegardés, et une sauvegarde les remplace tous`() = runTest {
        val old = repository("ancien")
        old.setThemeMode(ThemeMode.DARK)
        old.setGridColumns(3)
        old.setFolderHidden(42, true)
        val (values, keys) = old.backupValues()
        assertEquals(mapOf("theme_mode" to JsonPrimitive("DARK"), "grid_columns" to JsonPrimitive(3)), values)
        assertEquals(SettingsRepository.BACKED_UP.map { it.key.name }, keys)

        val new = repository("nouveau")
        new.setAmoled(true)
        new.setGridColumns(6)
        new.setFolderHidden(7, true)
        // Thème choisi, colonnes changées, AMOLED revenu à sa valeur par défaut.
        assertEquals(3, new.restoreBackup(values, keys))
        val restored = new.settings.first()
        assertEquals(ThemeMode.DARK, restored.themeMode)
        assertEquals(3, restored.gridColumns)
        assertFalse(restored.amoled)
        // Les dossiers masqués ne sont pas des réglages : ils restent ceux du téléphone.
        assertEquals(setOf(7L), restored.hiddenFolders)
        // Déjà restaurés : rien ne change.
        assertEquals(0, new.restoreBackup(values, keys))
    }

    @Test
    fun `valeur illisible, clé inconnue ou réglage que la sauvegarde ignorait, le réglage reste tel quel`() = runTest {
        val phone = repository("téléphone")
        phone.setThemeMode(ThemeMode.LIGHT)
        phone.setSlideshowInterval(8)
        phone.setMuteVideosByDefault(true)
        val changed = phone.restoreBackup(
            values = mapOf(
                // Version ultérieure : un thème inconnu ici, un nombre en texte, un réglage de plus.
                "theme_mode" to JsonPrimitive("SEPIA"),
                "slideshow_interval" to JsonPrimitive("12"),
                "grid_columns" to JsonPrimitive(40),
                "hidden_folders" to JsonPrimitive("1,2"),
                "nouveau_reglage" to JsonPrimitive(true),
            ),
            // Sauvegarde d'une version qui ne connaissait pas encore « mute_videos ».
            known = listOf("theme_mode", "slideshow_interval", "grid_columns", "hidden_folders", "nouveau_reglage"),
        )
        val settings = phone.settings.first()
        assertEquals(ThemeMode.LIGHT, settings.themeMode)
        assertEquals(8, settings.slideshowIntervalSeconds)
        // Hors limites : ramené dans les limites.
        assertEquals(AppSettings.MAX_COLUMNS, settings.gridColumns)
        assertTrue(settings.muteVideosByDefault)
        assertEquals(emptySet<Long>(), settings.hiddenFolders)
        assertEquals(1, changed)
    }

    @Test
    fun `les dossiers masqués d'une sauvegarde s'ajoutent à ceux du téléphone`() = runTest {
        val phone = repository("téléphone")
        phone.setFolderHidden(1, true)
        assertEquals(2, phone.hideFolders(listOf(1, 2, 3)))
        assertEquals(0, phone.hideFolders(listOf(2, 3)))
        assertEquals(setOf(1L, 2L, 3L), phone.settings.first().hiddenFolders)
    }

    private fun repository(name: String) = SettingsRepository(
        PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "$name.preferences_pb") },
        scope,
    )
}
