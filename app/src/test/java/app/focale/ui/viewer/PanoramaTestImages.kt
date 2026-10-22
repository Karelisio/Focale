package app.focale.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import kotlinx.coroutines.flow.Flow
import me.saket.telephoto.zoomable.ZoomableImageSource

/** Image affichée telle quelle par telephoto, sans Coil : un [Painter] connu d'avance. */
internal class PainterImageSource(private val painter: Painter) : ZoomableImageSource {
    @Composable
    override fun resolve(canvasSize: Flow<Size>): ZoomableImageSource.ResolveResult =
        ZoomableImageSource.ResolveResult(delegate = ZoomableImageSource.PainterDelegate(painter))
}

/**
 * Panorama factice en aplats (ciel, soleil, montagnes, lac), [width] × [height] pixels : léger
 * à stocker en PNG, et assez varié pour voir où l'on se trouve.
 */
internal fun fakePanorama(width: Int = 1800, height: Int = 300): ImageBitmap = ImageBitmap(width, height).also { bitmap ->
    val canvas = Canvas(bitmap)
    fun fill(color: Long) = Paint().apply { this.color = Color(color) }
    val w = width.toFloat()
    val h = height.toFloat()
    canvas.drawRect(0f, 0f, w, h, fill(0xFF8DB3C9))
    canvas.drawCircle(Offset(w * 0.7f, h * 0.25f), h * 0.09f, fill(0xFFF2D37C))
    val far = Path().apply {
        moveTo(0f, h * 0.62f)
        for (step in 0..12) lineTo(w * step / 12f, h * if (step % 2 == 0) 0.6f else 0.38f)
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    canvas.drawPath(far, fill(0xFF7C8FA3))
    val near = Path().apply {
        moveTo(0f, h * 0.75f)
        for (step in 0..7) lineTo(w * step / 7f, h * if (step % 2 == 0) 0.72f else 0.52f)
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    canvas.drawPath(near, fill(0xFF5E7A55))
    canvas.drawRect(0f, h * 0.82f, w, h, fill(0xFF4A7A93))
}

internal fun fakePanoramaPainter(width: Int = 1800, height: Int = 300): Painter = BitmapPainter(fakePanorama(width, height))
