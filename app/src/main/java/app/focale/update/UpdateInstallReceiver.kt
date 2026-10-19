package app.focale.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Issue d'une session d'installation : écran de confirmation à afficher, abandon par
 * l'utilisateur ou échec. En cas de succès, Android remplace l'application et arrête
 * ce processus.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val repository = EntryPointAccessors.fromApplication(context, UpdateEntryPoint::class.java).updateRepository()
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?.let(repository::onConfirmationRequired)
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> repository.onInstallFailed(aborted = status == PackageInstaller.STATUS_FAILURE_ABORTED)
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface UpdateEntryPoint {
        fun updateRepository(): UpdateRepository
    }
}
