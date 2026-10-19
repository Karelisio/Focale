package app.focale.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.core.model.MediaItem
import app.focale.core.util.Formatters
import app.focale.data.MediaDetails
import java.util.Locale
import kotlin.math.roundToInt

/** Panneau Infos : date, fichier, dimensions, appareil, exposition, localisation. */
@Composable
fun MediaInfoSheet(
    item: MediaItem,
    details: MediaDetails?,
    onDismiss: () -> Unit,
    placeName: String? = null,
    onShowOnMap: ((Double, Double) -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        MediaInfoContent(item, details, placeName, onShowOnMap)
    }
}

@Composable
fun MediaInfoContent(
    item: MediaItem,
    details: MediaDetails?,
    placeName: String?,
    onShowOnMap: ((Double, Double) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.info_title),
            style = MaterialTheme.typography.headlineSmallEmphasized,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (details == null) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
            return@Column
        }

        InfoCard(
            icon = Icons.Outlined.CalendarToday,
            title = Formatters.dateTime(context, details.captureTime ?: item.dateTaken),
        )
        InfoCard(
            icon = if (item.isVideo) Icons.Outlined.Movie else Icons.Outlined.Image,
            title = item.displayName,
            subtitle = item.relativePath.trimEnd('/'),
        )
        InfoCard(
            icon = Icons.Outlined.PhotoSizeSelectLarge,
            title = Formatters.resolution(item.displayWidth, item.displayHeight),
            subtitle = buildList {
                add(Formatters.fileSize(context, item.size))
                add(item.mimeType)
                if (item.isVideo) add(Formatters.duration(item.durationMs))
                details.frameRate?.let { add(String.format(locale, "%.0f i/s", it)) }
                details.videoBitrate?.let { add("${it / 1000} kb/s") }
            }.joinToString(" · "),
        )
        if (details.hasCameraInfo) {
            InfoCard(
                icon = Icons.Outlined.CameraAlt,
                title = listOfNotNull(details.cameraMake, details.cameraModel)
                    .distinct()
                    .joinToString(" ")
                    .ifBlank { stringResource(R.string.info_camera) },
                subtitle = exposureSummary(details, locale),
                caption = details.lensModel,
            )
        }
        if (details.hasLocation) {
            val lat = details.latitude!!
            val lon = details.longitude!!
            InfoCard(
                icon = Icons.Outlined.LocationOn,
                title = placeName ?: stringResource(R.string.info_location),
                subtitle = String.format(Locale.ROOT, "%.5f, %.5f", lat, lon),
                onClick = onShowOnMap?.let { { it(lat, lon) } },
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** « ƒ/1.8 · 1/120 s · ISO 100 · 6,9 mm (24 mm) » */
private fun exposureSummary(details: MediaDetails, locale: Locale): String = buildList {
    details.fNumber?.let { add("ƒ/" + String.format(locale, "%.1f", it)) }
    details.exposureTime?.let { t ->
        add(if (t < 1.0) "1/${(1.0 / t).roundToInt()} s" else String.format(locale, "%.1f s", t))
    }
    details.iso?.let { add("ISO $it") }
    details.focalLength?.let { f ->
        val equivalent = details.focalLength35mm?.let { " ($it mm)" }.orEmpty()
        add(String.format(locale, "%.1f mm", f) + equivalent)
    }
}.joinToString(" · ")

@Composable
private fun InfoCard(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    caption: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val content: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.padding(10.dp).size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!caption.isNullOrBlank()) {
                    Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
            content = content,
        )
    } else {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
            content = content,
        )
    }
}
