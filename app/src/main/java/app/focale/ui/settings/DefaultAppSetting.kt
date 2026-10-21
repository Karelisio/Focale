package app.focale.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.focale.R
import app.focale.core.model.MediaItem

/** Application qui ouvre les photos des autres applications : résolution de `ACTION_VIEW` d'un JPEG. */
sealed interface PhotoViewerDefault {
    /** Focale, choisi « Toujours » (ou seule application capable de les ouvrir). */
    data object Focale : PhotoViewerDefault

    /** Aucune : Android affiche son sélecteur à chaque ouverture. */
    data object None : PhotoViewerDefault

    /** Une autre application, dont il faut d'abord effacer les valeurs par défaut. */
    data class Other(val packageName: String, val label: String) : PhotoViewerDefault

    companion object {
        /**
         * État d'après la résolution « par défaut seulement » : [resolved], paquet de l'activité
         * retenue, parmi les [candidates] qui savent ouvrir une photo. Une activité hors de celles-ci
         * est le sélecteur du système : aucune valeur par défaut.
         */
        fun of(resolved: String?, candidates: Collection<String>, ownPackage: String, label: (String) -> String): PhotoViewerDefault =
            when {
                resolved == ownPackage -> Focale
                resolved == null || resolved !in candidates -> None
                else -> Other(resolved, label(resolved))
            }
    }
}

/** Ce que propose l'entrée « Application par défaut pour les photos ». */
sealed interface DefaultAppAction {
    /** Rien : Focale ouvre déjà les photos. */
    data object Done : DefaultAppAction

    /** Ouvrir une photo récente sans sélecteur imposé : Android propose de choisir Focale « Toujours ». */
    data object OpenPhoto : DefaultAppAction

    /** Aucune photo dans la bibliothèque : seule la marche à suivre est donnée. */
    data object Explain : DefaultAppAction

    /** Expliquer qu'il faut effacer les valeurs par défaut de [label], puis ouvrir sa page de réglages. */
    data class ClearDefaults(val packageName: String, val label: String) : DefaultAppAction

    companion object {
        fun of(state: PhotoViewerDefault, hasPhoto: Boolean): DefaultAppAction = when (state) {
            PhotoViewerDefault.Focale -> Done
            PhotoViewerDefault.None -> if (hasPhoto) OpenPhoto else Explain
            is PhotoViewerDefault.Other -> ClearDefaults(state.packageName, state.label)
        }
    }
}

/** Application qui ouvre aujourd'hui un JPEG ; sa visibilité (Android 11+) est déclarée dans le manifeste. */
@Suppress("DEPRECATION") // Variantes à ResolveInfoFlags : Android 13+ seulement.
fun resolvePhotoViewerDefault(context: Context): PhotoViewerDefault {
    val packageManager = context.packageManager
    // Seuls le schéma et le type comptent pour la résolution : la photo n'a pas à exister.
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(PROBE_URI.toUri(), JPEG)
    val resolved = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
    val candidates = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }
    return PhotoViewerDefault.of(resolved?.packageName, candidates, context.packageName) { packageName ->
        resolved?.applicationInfo?.loadLabel(packageManager)?.toString() ?: packageName
    }
}

/**
 * Entrée « Application par défaut pour les photos » : état actuel, recalculé à chaque retour dans
 * Focale, et action adaptée ; [samplePhoto] fournit la photo à ouvrir.
 */
@Composable
fun DefaultAppSetting(shapes: ListItemShapes, samplePhoto: suspend () -> MediaItem?) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(resolvePhotoViewerDefault(context)) }
    var photo by remember { mutableStateOf<MediaItem?>(null) }
    var photoSearched by remember { mutableStateOf(false) }
    var explaining by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state = resolvePhotoViewerDefault(context) }
    LaunchedEffect(state) {
        if (state == PhotoViewerDefault.None) {
            photo = samplePhoto()
            photoSearched = true
        }
    }

    // Une photo est supposée exister tant que la recherche n'a pas répondu : le texte ne change pas.
    val action = DefaultAppAction.of(state, hasPhoto = photo != null || !photoSearched)
    DefaultAppEntry(
        action = action,
        shapes = shapes,
        onClick = when (action) {
            DefaultAppAction.OpenPhoto -> { { photo?.let { openWithoutChooser(context, it) } } }
            is DefaultAppAction.ClearDefaults -> { { explaining = true } }
            DefaultAppAction.Done, DefaultAppAction.Explain -> null
        },
    )
    if (explaining && action is DefaultAppAction.ClearDefaults) {
        ClearDefaultsDialog(
            label = action.label,
            onOpenSettings = {
                explaining = false
                openAppDetails(context, action.packageName)
            },
            onDismiss = { explaining = false },
        )
    }
}

/** Rendu de l'entrée pour [action] ; sans [onClick], elle n'est pas cliquable. */
@Composable
internal fun DefaultAppEntry(action: DefaultAppAction, shapes: ListItemShapes, onClick: (() -> Unit)?) {
    val title = stringResource(R.string.default_app_title)
    val subtitle = when (action) {
        DefaultAppAction.Done -> stringResource(R.string.default_app_focale)
        DefaultAppAction.OpenPhoto -> stringResource(R.string.default_app_none)
        DefaultAppAction.Explain -> stringResource(R.string.default_app_none_no_photo)
        is DefaultAppAction.ClearDefaults -> stringResource(R.string.default_app_other, action.label)
    }
    if (onClick != null) {
        SettingsAction(title = title, subtitle = subtitle, icon = Icons.Outlined.PhotoLibrary, shapes = shapes, onClick = onClick)
    } else {
        SettingsInfo(
            title = title,
            subtitle = subtitle,
            icon = Icons.Outlined.PhotoLibrary,
            shapes = shapes,
            trailing = if (action == DefaultAppAction.Done) {
                { Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
            } else {
                null
            },
        )
    }
}

@Composable
private fun ClearDefaultsDialog(label: String, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
        title = { Text(stringResource(R.string.default_app_clear_title)) },
        text = { Text(stringResource(R.string.default_app_clear_message, label)) },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.default_app_open_settings)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Photo ouverte sans sélecteur imposé : Android liste ses applications, avec « Toujours ». */
private fun openWithoutChooser(context: Context, photo: MediaItem) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(photo.uri, photo.mimeType.ifBlank { JPEG })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }
}

/** Page « Infos sur l'application » de [packageName], où effacer ses valeurs par défaut. */
private fun openAppDetails(context: Context, packageName: String) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private const val JPEG = "image/jpeg"
private const val PROBE_URI = "content://media/external/images/media/1"
