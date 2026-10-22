package app.focale.whatsnew

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import app.focale.core.locale.AppLanguage
import app.focale.core.locale.AppLocales
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * « Nouveautés » dans la langue de Focale : le journal embarqué en français (CHANGELOG.md) pour le
 * français, sa traduction (CHANGELOG.en.md) pour l'anglais et toute autre langue, choisie dans
 * Focale ou suivie du téléphone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WhatsNewRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val locales = AppLocales(context)
    private val repository by lazy {
        WhatsNewRepository(context, PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "state.preferences_pb") }, locales, Dispatchers.IO)
    }

    private val french = Changelog.parse(File("../CHANGELOG.md").readText())
    private val english = Changelog.parse(File("../CHANGELOG.en.md").readText())

    @After
    fun tearDown() {
        locales.setLanguage(AppLanguage.SYSTEM)
        scope.cancel()
    }

    @Test
    fun `le journal suit la langue choisie dans Focale`() = runBlocking<Unit> {
        locales.setLanguage(AppLanguage.FRENCH)
        assertEquals(french, repository.all())
        locales.setLanguage(AppLanguage.ENGLISH)
        assertEquals(english, repository.all())
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `langue du téléphone suivie, en français`() = runBlocking<Unit> {
        assertEquals(french, repository.all())
    }

    @Test
    @Config(qualifiers = "de-rDE")
    fun `langue du téléphone suivie, ni français ni anglais, en anglais`() = runBlocking<Unit> {
        assertEquals(english, repository.all())
    }
}
