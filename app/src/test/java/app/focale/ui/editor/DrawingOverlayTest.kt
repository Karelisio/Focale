package app.focale.ui.editor

import android.app.Application
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.focale.edit.DrawTool
import app.focale.edit.DrawingStroke
import app.focale.edit.ImageFrame
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Gestes réels (événements tactiles injectés) sur la couche de dessin, posée comme dans
 * l'éditeur sous un parent qui zoomerait et déplacerait l'aperçu s'il recevait le geste.
 * Densité 1 : 1 dp = 1 px.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h400dp-mdpi")
class DrawingOverlayTest {

    /** Image pivotée entière de 200 × 300 px posée en (100, 50). */
    private val frame = ImageFrame(100f, 50f, 200f, 300f)

    private var strokes by mutableStateOf(emptyList<DrawingStroke>())
    private var transformed = false
    private var eraseGestures = 0
    private var time = 0L
    private lateinit var view: View

    private fun show(tool: DrawTool?) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTransformGestures { _, _, _, _ -> transformed = true } },
            ) {
                if (tool != null) {
                    DrawingOverlay(
                        frame = frame,
                        visible = Rect(100f, 50f, 300f, 350f),
                        strokes = strokes,
                        tool = tool,
                        color = Color.Red,
                        size = 10.dp,
                        onStroke = { strokes = strokes + it },
                        onEraseStart = { eraseGestures++ },
                        onErase = { erased -> strokes = strokes.filterNot { it === erased } },
                    )
                }
            }
        }
        idle()
        view = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))

    private fun send(action: Int, vararg pointers: Pair<Float, Float>) {
        time += 16
        val properties = Array(pointers.size) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(pointers.size) { index -> MotionEvent.PointerCoords().apply { x = pointers[index].first; y = pointers[index].second; pressure = 1f; size = 1f } }
        val event = MotionEvent.obtain(0L, time, action, pointers.size, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
        idle()
    }

    private fun swipe(vararg points: Pair<Float, Float>) {
        send(MotionEvent.ACTION_DOWN, points.first())
        for (point in points.drop(1)) send(MotionEvent.ACTION_MOVE, point)
        send(MotionEvent.ACTION_UP, points.last())
    }

    @Test
    fun `sans couche de dessin, le même geste déplacerait l'aperçu`() {
        show(tool = null)
        swipe(120f to 100f, 160f to 150f, 200f to 200f, 280f to 300f)
        assertTrue(transformed)
    }

    @Test
    fun `un trait à main levée suit le doigt en coordonnées de l'image sans déplacer l'aperçu`() {
        show(DrawTool.PEN)
        swipe(120f to 100f, 160f to 150f, 200f to 200f, 280f to 300f)
        assertFalse(transformed)
        val stroke = strokes.single()
        assertEquals(DrawTool.PEN, stroke.tool)
        assertEquals(4, stroke.pointCount)
        assertEquals(0.1f, stroke.x(0), 1e-4f)
        assertEquals(50f / 300f, stroke.y(0), 1e-4f)
        assertEquals(0.9f, stroke.x(3), 1e-4f)
        assertEquals(250f / 300f, stroke.y(3), 1e-4f)
        // 10 px rapportés au petit côté de l'image affichée.
        assertEquals(10f / 200f, stroke.width, 1e-5f)
    }

    @Test
    fun `une forme va du point de départ au point d'arrivée`() {
        show(DrawTool.RECTANGLE)
        swipe(150f to 100f, 200f to 180f, 250f to 250f)
        val stroke = strokes.single()
        assertEquals(2, stroke.pointCount)
        assertEquals(0.25f, stroke.x(0), 1e-4f)
        assertEquals(0.75f, stroke.x(1), 1e-4f)
        assertEquals(200f / 300f, stroke.y(1), 1e-4f)
    }

    @Test
    fun `un simple appui avec une forme ne laisse rien`() {
        show(DrawTool.ARROW)
        swipe(150f to 100f, 151f to 101f)
        assertTrue(strokes.isEmpty())
    }

    @Test
    fun `un second doigt ne zoome pas pendant le tracé`() {
        show(DrawTool.PEN)
        send(MotionEvent.ACTION_DOWN, 150f to 150f)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 150f to 150f, 250f to 250f)
        send(MotionEvent.ACTION_MOVE, 140f to 140f, 280f to 280f)
        send(MotionEvent.ACTION_MOVE, 120f to 120f, 300f to 300f)
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 120f to 120f, 300f to 300f)
        send(MotionEvent.ACTION_UP, 120f to 120f)
        assertFalse(transformed)
        assertEquals(1, strokes.size)
    }

    @Test
    fun `la gomme efface les traits touchés en une seule étape`() {
        show(DrawTool.PEN)
        swipe(120f to 100f, 280f to 100f)
        swipe(120f to 200f, 280f to 200f)
        swipe(120f to 300f, 280f to 300f)
        assertEquals(3, strokes.size)
        val kept = strokes.last()
        show(DrawTool.ERASER)
        // Balayage vertical rapide sur les deux premiers traits seulement.
        swipe(200f to 80f, 200f to 250f)
        assertFalse(transformed)
        assertEquals(listOf(kept), strokes)
        assertEquals(1, eraseGestures)
    }
}
