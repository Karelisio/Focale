package app.focale

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.focale.core.mediastore.ConsentRequester
import app.focale.core.permissions.MediaAccessManager
import app.focale.core.settings.SettingsRepository
import app.focale.core.settings.ThemeMode
import app.focale.ui.navigation.FocaleApp
import app.focale.ui.theme.FocaleTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Activité unique. Hérite de [FragmentActivity] pour l'invite biométrique du coffre.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var consentRequester: ConsentRequester
    @Inject lateinit var accessManager: MediaAccessManager

    /** Lance les dialogues système de consentement (suppression, modification…). */
    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        consentRequester.complete(result.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                consentRequester.requests.collect { sender ->
                    consentLauncher.launch(IntentSenderRequest.Builder(sender).build())
                }
            }
        }

        setContent {
            val settings by settingsRepository.current.collectAsStateWithLifecycle()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Barres système transparentes, icônes adaptées au thème courant.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                )
                onDispose {}
            }
            FocaleTheme(settings) {
                FocaleApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accessManager.refresh()
    }
}
