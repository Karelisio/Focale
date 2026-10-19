package app.focale.ui.vault

import android.content.Context
import android.view.Window
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.focale.R
import app.focale.ui.viewer.findActivity

/**
 * Interdit captures d'écran, enregistrement et aperçu dans les applications récentes
 * tant qu'un écran du coffre est affiché.
 */
@Composable
fun SecureWindow() {
    val window = LocalContext.current.findActivity()?.window ?: return
    DisposableEffect(window) {
        SecureFlag.acquire(window)
        onDispose { SecureFlag.release(window) }
    }
}

/**
 * Compteur partagé par les écrans du coffre : pendant la transition de l'un à l'autre,
 * le nouvel écran pose l'indicateur avant que l'ancien ne le retire.
 */
private object SecureFlag {
    private var holders = 0

    fun acquire(window: Window) {
        if (holders++ == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(window: Window) {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

/** Biométrie forte (classe 3) disponible et enregistrée sur l'appareil. */
fun canUseBiometrics(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

/**
 * Lanceur de l'invite biométrique système, ou null hors d'une [FragmentActivity].
 * [onSuccess] n'est appelé qu'après une authentification réussie ; l'annulation
 * (bouton « Utiliser le code ») ramène simplement au clavier.
 */
@Composable
fun rememberBiometricPrompt(onSuccess: () -> Unit): (() -> Unit)? {
    val activity = LocalContext.current.findActivity() as? FragmentActivity ?: return null
    val title = stringResource(R.string.vault_biometric_title)
    val negative = stringResource(R.string.vault_biometric_negative)
    val currentOnSuccess by rememberUpdatedState(onSuccess)
    return remember(activity, title, negative) {
        {
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        currentOnSuccess()
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setNegativeButtonText(negative)
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .setConfirmationRequired(false)
                .build()
            prompt.authenticate(info)
        }
    }
}
