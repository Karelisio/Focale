package app.focale.core.locale

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Langue de l'interface choisie dans les réglages ; [SYSTEM] suit celle du téléphone. */
enum class AppLanguage(
    /** Code ISO 639 de la langue, null pour celle du système. */
    val code: String?,
) {
    SYSTEM(null),
    FRENCH("fr"),
    ENGLISH("en"),
    ;

    /**
     * Liste à confier au système : la langue, avec la région et les préférences (« fr-CA »,
     * « en-GB-u-fw-mon ») de la première langue du téléphone qui est la même ; vide pour [SYSTEM].
     */
    fun locales(system: LocaleListCompat): LocaleListCompat {
        val code = code ?: return LocaleListCompat.getEmptyLocaleList()
        val regional = (0 until system.size()).mapNotNull(system::get).firstOrNull { it.language == code }
        return LocaleListCompat.create(regional ?: Locale.forLanguageTag(code))
    }

    companion object {
        /**
         * Langue choisie d'après la liste de l'application ; une autre langue que le français
         * (choisie hors de Focale) s'affiche en anglais, la langue des ressources par défaut.
         */
        fun of(locales: LocaleListCompat): AppLanguage {
            val language = locales[0]?.language ?: return SYSTEM
            return entries.firstOrNull { it.code == language } ?: ENGLISH
        }
    }
}

/**
 * Langue de Focale, pour toutes les versions d'Android : depuis Android 13, c'est le réglage
 * « Langue de l'application » du système (`LocaleManager`, langues listées par le
 * `android:localeConfig` que génère AGP), qui le garde et recrée les activités ; avant, AppCompat
 * l'applique à `MainActivity` et le garde dans son fichier (`autoStoreLocales`, manifeste), que
 * le système reprend au passage à Android 13. Ni l'un ni l'autre n'est dans les réglages de
 * Focale : la sauvegarde des données ne les reprend pas.
 *
 * Hors des activités (notifications et leurs canaux créés par les tâches de fond, widget), le
 * contexte de l'application reste dans la langue du système avant Android 13 : [localized] en
 * donne un dans la langue choisie, et [locale] est celle des dates, des nombres et de l'analyse
 * des recherches, au lieu de `Locale.getDefault()`.
 */
@Singleton
class AppLocales @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Avant Android 13 : liste lue une fois du fichier d'AppCompat, puis tenue à jour par [setLanguage]. */
    @Volatile
    private var stored: LocaleListCompat? = null

    /** Dernier contexte de [localized] pour le contexte de l'application, et sa configuration. */
    @Volatile
    private var cached: Pair<Configuration, Context>? = null

    /** Langue choisie. */
    val language: AppLanguage get() = AppLanguage.of(applicationLocales())

    /** Langue de l'interface : celle choisie, sinon la première du système. */
    val locale: Locale
        get() = applicationLocales()[0] ?: LocaleManagerCompat.getSystemLocales(context)[0] ?: Locale.getDefault()

    /**
     * Change la langue de Focale. Les activités ouvertes sont recréées dans la nouvelle langue
     * (par le système depuis Android 13, par AppCompat avant), l'état de leurs écrans conservé.
     */
    fun setLanguage(language: AppLanguage) {
        val locales = language.locales(LocaleManagerCompat.getSystemLocales(context))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales = LocaleList.forLanguageTags(locales.toLanguageTags())
        } else {
            stored = locales
            // AppCompat recrée les activités ouvertes, puis écrit son fichier à leur création.
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }

    /**
     * [base] dans la langue de Focale : chaînes, pluriels, dates et nombres. Rendu tel quel s'il
     * l'est déjà (langue du système, activité, contexte de l'application depuis Android 13).
     */
    fun localized(base: Context = context): Context {
        val locales = applicationLocales()
        val current = base.resources.configuration
        if (locales.isEmpty || current.locales.toLanguageTags() == locales.toLanguageTags()) return base
        val configuration = Configuration(current).apply { setLocales(LocaleList.forLanguageTags(locales.toLanguageTags())) }
        val shared = base === context
        if (shared) cached?.let { (config, localized) -> if (config == configuration) return localized }
        return base.createConfigurationContext(configuration).also { if (shared) cached = configuration to it }
    }

    private fun applicationLocales(): LocaleListCompat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleManagerCompat.getApplicationLocales(context)
        } else {
            stored ?: LocaleManagerCompat.getApplicationLocales(context).also { stored = it }
        }
}
