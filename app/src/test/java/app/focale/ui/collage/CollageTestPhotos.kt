package app.focale.ui.collage

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaType

/** Photos de test des écrans de collage : médias de l'index et aperçus en aplats. */
internal object CollageTestPhotos {

    /** 12 mars 2025, 09:30 UTC. */
    const val DATE = 1_741_771_800_000L

    fun item(id: Long, video: Boolean = false): MediaItem = MediaItem(
        id = id,
        uri = Uri.parse("content://media/external/images/media/$id"),
        type = if (video) MediaType.VIDEO else MediaType.IMAGE,
        mimeType = if (video) "video/mp4" else "image/jpeg",
        displayName = if (video) "VID_%04d.mp4".format(id) else "IMG_%04d.jpg".format(id),
        relativePath = "DCIM/Camera/",
        bucketId = 1,
        bucketName = "Camera",
        dateTaken = DATE,
        dateModified = DATE,
        size = 1_000_000,
        width = 4000,
        height = 3000,
        orientation = 0,
        durationMs = if (video) 12_000 else 0,
        isGif = false,
        isScreenshot = false,
    )

    /**
     * Paysage en aplats, différent pour chaque [index] : ciel, soleil, collines. Paysage 4:3, sauf
     * un sur trois en portrait, pour que les cases en recadrent de toutes les formes.
     */
    fun scene(index: Int): ImageBitmap {
        val portrait = index % 3 == 2
        val width = if (portrait) 300 else 400
        val height = if (portrait) 400 else 300
        val palette = PALETTES[index % PALETTES.size]
        return ImageBitmap(width, height).also { bitmap ->
            val canvas = Canvas(bitmap)
            val paint = Paint()
            paint.color = palette[0]
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.color = palette[1]
            canvas.drawCircle(Offset(width * (0.25f + 0.1f * (index % 5)), height * 0.3f), width * 0.11f, paint)
            paint.color = palette[2]
            canvas.drawPath(
                Path().apply {
                    moveTo(-10f, height * 0.7f)
                    cubicTo(width * 0.3f, height * 0.55f, width * 0.6f, height * 0.85f, width + 10f, height * 0.62f)
                    lineTo(width + 10f, height + 10f)
                    lineTo(-10f, height + 10f)
                    close()
                },
                paint,
            )
            paint.color = palette[3]
            canvas.drawRect(width * 0.62f, height * 0.5f, width * 0.74f, height * 0.78f, paint)
        }
    }

    private val PALETTES = listOf(
        listOf(Color(0xFF8DB3CC), Color(0xFFF2D37C), Color(0xFF6E8F5A), Color(0xFF5D4037)),
        listOf(Color(0xFFF6B48F), Color(0xFFFFF3C4), Color(0xFF8C5A7A), Color(0xFF2E3A59)),
        listOf(Color(0xFF263859), Color(0xFFE8EAF6), Color(0xFF17223B), Color(0xFFFFB74D)),
        listOf(Color(0xFFB2DFDB), Color(0xFFFF8A65), Color(0xFF00796B), Color(0xFF004D40)),
        listOf(Color(0xFFFFE0B2), Color(0xFFE65100), Color(0xFFA1887F), Color(0xFF4E342E)),
        listOf(Color(0xFFC5CAE9), Color(0xFFFFFFFF), Color(0xFF7986CB), Color(0xFF283593)),
    )
}
