package app.focale.core.locale

import android.app.Application
import android.app.LocaleManager
import android.os.Bundle
import android.os.LocaleList
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import app.focale.R
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Langue de Focale : choix appliqué (activité recréée, état des écrans conservé) et gardé, par le
 * système depuis Android 13, par AppCompat avant ; contexte de l'application traduit pour ce qui
 * s'affiche hors des activités. Téléphone en anglais (qualificatifs Robolectric par défaut).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppLocalesTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        // État statique d'AppCompat (avant Android 13), partagé par les tests d'une même version.
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test
    fun `liste confiée au système, la langue avec la région du téléphone qui la parle`() {
        val system = LocaleListCompat.forLanguageTags("en-GB,fr-CA")
        assertEquals("fr-CA", AppLanguage.FRENCH.locales(system).toLanguageTags())
        assertEquals("en-GB", AppLanguage.ENGLISH.locales(system).toLanguageTags())
        assertEquals("fr", AppLanguage.FRENCH.locales(LocaleListCompat.forLanguageTags("de-DE")).toLanguageTags())
        assertEquals("", AppLanguage.SYSTEM.locales(system).toLanguageTags())

        assertEquals(AppLanguage.SYSTEM, AppLanguage.of(LocaleListCompat.getEmptyLocaleList()))
        assertEquals(AppLanguage.FRENCH, AppLanguage.of(LocaleListCompat.forLanguageTags("fr-BE")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.of(LocaleListCompat.forLanguageTags("en-AU")))
        // Autre langue choisie hors de Focale : affichée en anglais, langue des ressources par défaut.
        assertEquals(AppLanguage.ENGLISH, AppLanguage.of(LocaleListCompat.forLanguageTags("de")))
    }

    @Test
    @Config(sdk = [34])
    fun `Android 13 et plus, le choix est le réglage du système, qui le garde`() {
        val locales = AppLocales(context)
        assertEquals(AppLanguage.SYSTEM, locales.language)
        assertEquals("en", locales.locale.language)

        locales.setLanguage(AppLanguage.FRENCH)
        val manager = context.getSystemService(LocaleManager::class.java)
        assertEquals(LocaleList.forLanguageTags("fr"), manager.applicationLocales)
        // Gardé par le système : relu tel quel, par exemple dans un nouveau processus.
        assertEquals(AppLanguage.FRENCH, AppLocales(context).language)
        assertEquals("fr", AppLocales(context).locale.language)
        // Choisi dans les réglages d'Android : Focale le lit de même.
        manager.applicationLocales = LocaleList.forLanguageTags("en")
        assertEquals(AppLanguage.ENGLISH, AppLocales(context).language)

        locales.setLanguage(AppLanguage.SYSTEM)
        assertEquals(LocaleList.getEmptyLocaleList(), manager.applicationLocales)
        assertEquals(AppLanguage.SYSTEM, AppLocales(context).language)
    }

    @Test
    @Config(sdk = [34])
    fun `hors des activités, un contexte dans la langue de Focale`() {
        val locales = AppLocales(context)
        // Langue du système : le contexte de l'application convient tel quel.
        assertSame(context, locales.localized(context))
        assertEquals("Language", context.getString(R.string.settings_language))

        locales.setLanguage(AppLanguage.FRENCH)
        val localized = locales.localized(context)
        assertNotSame(context, localized)
        assertEquals("Langue", localized.getString(R.string.settings_language))
        assertEquals("fr", localized.resources.configuration.locales[0].language)
        // Même langue, même contexte : pas un nouveau à chaque notification.
        assertSame(localized, locales.localized(context))
        // Un contexte déjà dans cette langue (activité recréée) est rendu tel quel.
        assertSame(localized, locales.localized(localized))

        locales.setLanguage(AppLanguage.ENGLISH)
        assertEquals("Language", locales.localized(context).getString(R.string.settings_language))
    }

    @Test
    @Config(sdk = [30])
    fun `avant Android 13, AppCompat recrée l'activité dans la langue choisie, état conservé, et la garde`() {
        val controller = Robolectric.buildActivity(LanguageTestActivity::class.java).setup()
        idle()
        val first = controller.get()
        assertEquals("Language 0", first.rendered)
        repeat(3) { first.increment() }
        idle()
        assertEquals("Language 3", first.rendered)

        val locales = AppLocales(context)
        locales.setLanguage(AppLanguage.FRENCH)
        idle()

        // Recréée, sans redémarrer l'application : textes en français, compteur retrouvé.
        val recreated = controller.get()
        assertNotSame(first, recreated)
        assertEquals("fr", recreated.resources.configuration.locales[0].language)
        assertEquals("Langue 3", recreated.rendered)
        // Gardée par AppCompat (autoStoreLocales) : relue de son fichier par un nouveau processus.
        assertEquals("fr", LocaleManagerCompat.getApplicationLocales(context).toLanguageTags())
        assertEquals(AppLanguage.FRENCH, AppLocales(context).language)
        // Hors des activités : le contexte de l'application, dans la langue choisie.
        assertEquals("Langue", AppLocales(context).localized(context).getString(R.string.settings_language))

        // Retour à la langue du système, gardé de même par la prochaine activité AppCompat créée.
        // (Sous Robolectric, les ressources de l'application ont pris le français : AppCompat, qui
        // y lit la langue du système, ne recrée pas l'activité ouverte.)
        locales.setLanguage(AppLanguage.SYSTEM)
        idle()
        assertEquals(AppLanguage.SYSTEM, locales.language)
        val next = Robolectric.buildActivity(LanguageTestActivity::class.java).setup()
        idle()
        assertEquals("", LocaleManagerCompat.getApplicationLocales(context).toLanguageTags())
        assertEquals(AppLanguage.SYSTEM, AppLocales(context).language)
        next.pause().stop().destroy()
        controller.pause().stop().destroy()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

    /** Activité AppCompat au thème de Focale, comme `MainActivity` : un texte et un état sauvegardé. */
    class LanguageTestActivity : AppCompatActivity() {
        var increment: () -> Unit = {}
        var rendered: String? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_Focale)
            super.onCreate(savedInstanceState)
            setContent {
                var saved by rememberSaveable { mutableIntStateOf(0) }
                increment = { saved++ }
                val text = stringResource(R.string.settings_language) + " " + saved
                SideEffect { rendered = text }
                Text(text)
            }
        }
    }
}
