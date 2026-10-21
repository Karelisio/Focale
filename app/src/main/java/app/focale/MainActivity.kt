package app.focale

import android.content.Intent
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
import app.focale.ui.navigation.MainRoute
import app.focale.ui.navigation.OpenRequest
import app.focale.ui.theme.FocaleTheme
import app.focale.update.UpdateRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Activité unique, qui ouvre aussi les photos et vidéos des autres applications ([OpenRequest]).
 * Hérite de [FragmentActivity] pour l'invite biométrique du coffre.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var consentRequester: ConsentRequester
    @Inject lateinit var accessManager: MediaAccessManager
    @Inject lateinit var updateRepository: UpdateRepository

    /** Ouvertures reçues après le lancement (widget, autre application), consommées par l'interface. */
    private val openRequests = MutableStateFlow<OpenRequest?>(null)

    /** Lance les dialogues système de consentement (suppression, modification…). */
    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        consentRequester.complete(result.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Lancée pour le média d'une autre application : il est la première destination, sans timeline
        // dessous. L'intention de lancement est conservée : même graphe après une recréation.
        val launch = OpenRequest.of(intent, launched = true, ownPackage = packageName)
        val startRoute: Any = (launch as? OpenRequest.External)?.route ?: MainRoute
        // Après une recréation, la visionneuse déjà ouverte est restaurée par la navigation.
        if (savedInstanceState == null && launch is OpenRequest.Media) openRequests.value = launch

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                consentRequester.requests.collect { sender ->
                    consentLauncher.launch(IntentSenderRequest.Builder(sender).build())
                }
            }
        }
        // Confirmation d'une mise à jour, affichée dès que Focale est visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                updateRepository.confirmations.collect { confirmation ->
                    runCatching { startActivity(confirmation) }
                        .onFailure { updateRepository.onInstallFailed(aborted = false) }
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
                FocaleApp(
                    startRoute = startRoute,
                    openRequests = openRequests,
                    onOpenHandled = { openRequests.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        accessManager.refresh()
    }

    /**
     * Intention reçue par l'activité déjà ouverte (`singleTop`) : widget, lanceur, ou média d'une
     * autre application ouvert par-dessus l'écran courant, que le retour retrouve.
     */
    private fun handleIntent(intent: Intent) {
        OpenRequest.of(intent, launched = false, ownPackage = packageName)?.let { openRequests.value = it }
    }

    companion object {
        const val ACTION_OPEN_MEDIA = "app.focale.action.OPEN_MEDIA"
        const val EXTRA_MEDIA_ID = "media_id"
    }
}
