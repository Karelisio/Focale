package app.focale.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installation d'un APK par une session [PackageInstaller]. Android n'accepte que la
 * même signature que l'application installée et une version plus récente ; l'issue de
 * la session est transmise à [UpdateInstallReceiver].
 */
@Singleton
class UpdateInstaller @Inject constructor(@ApplicationContext private val context: Context) {

    /** Copie [apk] dans une nouvelle session puis la valide (appel bloquant). */
    fun install(apk: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            // Android 12+ : une application qui se met à jour elle-même peut se passer de confirmation.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = packageInstaller.createSession(params)
        try {
            packageInstaller.openSession(sessionId).use { session ->
                session.openWrite(APK_NAME, 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output, BUFFER) }
                    session.fsync(output)
                }
                session.commit(statusReceiver(sessionId).intentSender)
            }
        } catch (error: Exception) {
            runCatching { packageInstaller.abandonSession(sessionId) }
            throw error
        }
    }

    private fun statusReceiver(sessionId: Int): PendingIntent {
        val intent = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
        // Modifiable : l'installateur y ajoute l'état de la session.
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }

    private companion object {
        const val APK_NAME = "focale.apk"
        const val BUFFER = 256 * 1024
    }
}
