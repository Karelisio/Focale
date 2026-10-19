package app.focale.core.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Niveau d'accès aux médias accordé par l'utilisateur. */
enum class MediaAccess {
    /** Toutes les photos et vidéos. */
    FULL,

    /** Android 14+ : seulement une sélection choisie par l'utilisateur. */
    PARTIAL,

    DENIED,
}

/**
 * Source de vérité de l'état des permissions médias.
 *
 * L'état est réévalué à chaque retour au premier plan ([refresh]) car l'utilisateur
 * peut le modifier à tout moment depuis les réglages système.
 */
@Singleton
class MediaAccessManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _access = MutableStateFlow(compute())
    val access: StateFlow<MediaAccess> = _access.asStateFlow()

    fun refresh() {
        _access.value = compute()
    }

    /** Vrai si les coordonnées GPS des EXIF peuvent être lues sans masquage. */
    fun canReadLocation(): Boolean = granted(Manifest.permission.ACCESS_MEDIA_LOCATION)

    private fun compute(): MediaAccess = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO)) ->
            MediaAccess.FULL

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.PARTIAL

        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> MediaAccess.FULL

        else -> MediaAccess.DENIED
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** Permissions à demander ensemble pour lire la galerie. */
        val requestedPermissions: Array<String>
            get() = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                    Manifest.permission.ACCESS_MEDIA_LOCATION,
                )

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.ACCESS_MEDIA_LOCATION,
                )

                else -> arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.ACCESS_MEDIA_LOCATION,
                )
            }
    }
}
