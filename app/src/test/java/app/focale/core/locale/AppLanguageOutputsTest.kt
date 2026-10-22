package app.focale.core.locale

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import app.focale.backup.AutoBackupError
import app.focale.backup.DataBackupNotifications
import app.focale.core.model.GroupLevel
import app.focale.core.util.Formatters
import app.focale.data.MemoryKind
import app.focale.data.MemoryTitles
import app.focale.ml.CountryResolver
import app.focale.ml.LabelDictionary
import app.focale.ml.PlaceName
import app.focale.ml.SearchQueryParser
import app.focale.vault.VaultImportState
import app.focale.vault.VaultNotifications
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Ce qui s'affiche hors des écrans suit la langue de Focale, pas celle du téléphone (ici en
 * anglais) : dates, nombres, lieux, pays et étiquettes ; analyse des recherches ; notifications et
 * canaux préparés par les tâches de fond avec le contexte de l'application, renommés quand la
 * langue change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppLanguageOutputsTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val locales = AppLocales(context)
    private val zone = ZoneId.systemDefault()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() {
        locales.setLanguage(AppLanguage.FRENCH)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        locales.setLanguage(AppLanguage.SYSTEM)
        scope.cancel()
    }

    @Test
    fun `dates, nombres, lieux, pays et étiquettes dans la langue de Focale`() {
        val text = locales.localized(context)
        val day = LocalDate.of(2023, 7, 14).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("14 juil. 2023", Formatters.dayMonthYear(text, day))
        assertEquals("Juillet 2023", Formatters.periodLabel(text, day, GroupLevel.MONTH))
        // Le contexte de l'application, lui, reste dans la langue du système.
        assertEquals("Jul 14, 2023", Formatters.dayMonthYear(context, day))
        assertEquals("4000 × 3000 · 12,0 Mpx", Formatters.resolution(4000, 3000, locales.locale))

        val lisbon = PlaceName("Lisbon", french = "Lisbonne")
        assertEquals("Lisbonne", lisbon.display(locales.locale))
        val trip = MemoryKind.Trip(yearsAgo = 3, place = lisbon, country = "PT", firstDay = LocalDate.of(2023, 6, 10), lastDay = LocalDate.of(2023, 6, 17))
        assertEquals("Lisbonne · juin 2023", MemoryTitles.title(text.resources, trip))
        assertEquals("Allemagne", CountryResolver.displayName("DE", locales.locale))
        assertEquals("Chien", LabelDictionary.displayName("Dog", locales.locale))

        locales.setLanguage(AppLanguage.ENGLISH)
        val english = locales.localized(context)
        assertEquals("Lisbon", lisbon.display(locales.locale))
        assertEquals("Lisbon · June 2023", MemoryTitles.title(english.resources, trip))
        assertEquals("Dog", LabelDictionary.displayName("Dog", locales.locale))
        assertEquals("4000 × 3000 · 12.0 Mpx", Formatters.resolution(4000, 3000, locales.locale))
    }

    @Test
    fun `recherche analysée dans la langue de Focale`() {
        fun parsed(text: String) = SearchQueryParser.parse(text, zone = zone, locale = locales.locale).dateFrom
        val april3 = LocalDate.of(2021, 4, 3).atStartOfDay(zone).toInstant().toEpochMilli()
        val march4 = LocalDate.of(2021, 3, 4).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(april3, parsed("03/04/2021"))
        // Anglais sans région du téléphone qui le parle : ordre américain, le mois d'abord.
        locales.setLanguage(AppLanguage.ENGLISH)
        assertEquals(march4, parsed("03/04/2021"))
    }

    @Test
    fun `notifications préparées en arrière-plan dans la langue de Focale, canaux compris`() {
        val manager = context.getSystemService(NotificationManager::class.java)
        // Échec d'une sauvegarde automatique (tâche de fond), avec le contexte de l'application.
        val data = DataBackupNotifications(context, locales)
        data.autoBackupFailed(AutoBackupError.NO_SPACE)
        val failed = shadowOf(manager).allNotifications.single()
        assertEquals("Échec de la sauvegarde automatique des données", failed.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Plus assez de place dans le dossier des sauvegardes.", failed.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("Sauvegarde automatique des données", manager.getNotificationChannel(DataBackupNotifications.CHANNEL).name)
        // Service au premier plan de la mise au coffre (tâche de fond).
        val vault = VaultNotifications(context, locales)
        val running = vault.importForeground(VaultImportState.Running(0, 0)).notification
        assertEquals("Mise au coffre-fort", running.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Préparation…", running.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("Sauvegarde automatique du coffre", manager.getNotificationChannel(VaultNotifications.CHANNEL_BACKUP).name)

        // Langue changée : canaux renommés (leurs noms s'affichent dans les réglages d'Android),
        // une seule fois même si le système l'annonce aussi.
        locales.setLanguage(AppLanguage.ENGLISH)
        var calls = 0
        val refresh = LocaleRefresh(
            locales,
            {
                setOf(
                    LocaleChangeListener { data.refreshChannel() },
                    LocaleChangeListener { vault.refreshChannels() },
                    LocaleChangeListener { calls++ },
                )
            },
            scope,
        )
        runBlocking {
            refresh.refresh().join()
            refresh.refresh().join()
        }
        assertEquals(1, calls)
        assertEquals("Automatic data backup", manager.getNotificationChannel(DataBackupNotifications.CHANNEL).name)
        assertEquals("Moving to the vault", manager.getNotificationChannel(VaultNotifications.CHANNEL_IMPORT).name)
        assertEquals("New photos for the vault", manager.getNotificationChannel(VaultNotifications.CHANNEL_NEW_MEDIA).name)
        assertEquals("Automatic vault backup", manager.getNotificationChannel(VaultNotifications.CHANNEL_BACKUP).name)
        runBlocking {
            locales.setLanguage(AppLanguage.FRENCH)
            refresh.refresh().join()
        }
        assertEquals(2, calls)
        assertEquals("Mise au coffre-fort", manager.getNotificationChannel(VaultNotifications.CHANNEL_IMPORT).name)
    }
}
