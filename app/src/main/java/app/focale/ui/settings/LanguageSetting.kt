package app.focale.ui.settings

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.focale.R
import app.focale.core.locale.AppLanguage
import app.focale.ui.components.Choice
import app.focale.ui.components.ConnectedChoiceGroup

/**
 * Section « Langue » : celle du système, le français ou l'anglais, pour toutes les versions
 * d'Android. Chaque langue est nommée dans la sienne. Depuis Android 13 ([sameAsSystemSetting]),
 * c'est le réglage « Langue de l'application » d'Android : le changer ici le change là-bas.
 */
@Composable
fun LanguageSection(
    language: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    sameAsSystemSetting: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
) {
    val choices = listOf(
        Choice(AppLanguage.SYSTEM, stringResource(R.string.settings_language_system)),
        Choice(AppLanguage.FRENCH, stringResource(R.string.settings_language_french)),
        Choice(AppLanguage.ENGLISH, stringResource(R.string.settings_language_english)),
    )
    SettingsGroup(stringResource(R.string.settings_language)) {
        item { shapes ->
            SettingsCustom(
                title = stringResource(R.string.settings_language_app),
                subtitle = stringResource(
                    if (sameAsSystemSetting) R.string.settings_language_summary_system else R.string.settings_language_summary,
                ),
                shapes = shapes,
            ) {
                ConnectedChoiceGroup(choices = choices, selected = language, onSelect = onSelect)
            }
        }
    }
}
