package app.focale.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MotionPhotosOn
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.core.model.MediaItem
import app.focale.video.MotionPhotos
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Badge discret des photos animées dans la grille. La détection (quelques en-têtes du fichier)
 * n'est lancée qu'après un court délai, pour ne rien lire pendant un défilement rapide, et au
 * plus deux à la fois ; ses résultats sont mémorisés et partagés avec la visionneuse.
 */
@Composable
fun MotionPhotoBadge(item: MediaItem, modifier: Modifier = Modifier) {
    if (!MotionPhotos.isCandidate(item)) return
    val context = LocalContext.current
    val isMotion by produceState(initialValue = MotionPhotos.cached(item)?.video != null, item.id, item.dateModified) {
        val known = MotionPhotos.cached(item)
        value = known?.video != null
        if (known != null) return@produceState
        delay(DETECTION_DELAY_MS)
        value = withContext(MotionPhotos.gridDispatcher) { MotionPhotos.detect(context.contentResolver, item) } != null
    }
    if (!isMotion) return
    Box(
        modifier
            .padding(5.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(3.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.MotionPhotosOn,
            contentDescription = stringResource(R.string.motion_photo_badge),
            tint = Color.White,
            modifier = Modifier.size(14.dp),
        )
    }
}

private const val DETECTION_DELAY_MS = 250L
