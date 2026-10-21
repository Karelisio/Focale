package app.focale.ui.editor

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import app.focale.edit.StraightenMath
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Geste réel à deux doigts sur le détecteur de redressement, au-dessus d'un cadre de recadrage qui écoute aussi. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StraightenGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
    }

    private var angle = 0f
    private var starts = 0
    private var ends = 0
    private var cropDrags = 0

    private fun show(initial: Float = 0f) {
        angle = initial
        compose.setContent {
            Box(
                Modifier
                    .size(300.dp)
                    .testTag("apercu")
                    .straightenGesture({ angle }, haptics, { starts++ }, { angle = it }, { ends++ }),
            ) {
                // Comme le cadre de recadrage : un glisser à un doigt.
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                cropDrags++
                            }
                        },
                )
            }
        }
    }

    /** Deux doigts tournent de [degrees] autour du centre, par pas de 2,5°, en s'écartant de [spread], puis se lèvent. */
    private fun twoFingers(degrees: Float, spread: Float = 1f) {
        compose.onNodeWithTag("apercu").performTouchInput {
            val steps = (abs(degrees) / 2.5f).toInt().coerceAtLeast(1)
            val radius = width / 5f
            fun at(step: Int, opposite: Boolean): Offset {
                val turn = degrees * step / steps + if (opposite) 180f else 0f
                val distance = radius * (1f + (spread - 1f) * step / steps)
                val radians = Math.toRadians(turn.toDouble())
                return center + Offset((distance * cos(radians)).toFloat(), (distance * sin(radians)).toFloat())
            }
            down(0, at(0, false))
            down(1, at(0, true))
            for (step in 1..steps) {
                updatePointerTo(0, at(step, false))
                updatePointerTo(1, at(step, true))
                move()
            }
            up(0)
            up(1)
        }
        compose.waitForIdle()
    }

    @Test
    fun `la rotation des doigts redresse au-delà du seuil, sans à-coup, et le cadre ne bouge pas`() {
        show(initial = 2f)
        twoFingers(20f)

        assertEquals(1, starts)
        assertEquals(1, ends)
        assertEquals(2f + 20f - StraightenMath.GESTURE_THRESHOLD, angle, 0.5f)
        assertEquals(0, cropDrags)
    }

    @Test
    fun `une petite rotation ou un pincement ne redressent rien`() {
        show()
        twoFingers(4f)
        twoFingers(30f, spread = 1.6f)

        assertEquals(0, starts)
        assertEquals(0f, angle, 0f)
        assertEquals(0, cropDrags)
    }

    @Test
    fun `sens antihoraire, borné à 45°`() {
        show()
        twoFingers(-80f)

        assertEquals(-StraightenMath.MAX_ANGLE, angle, 0f)
    }

    @Test
    fun `un seul doigt reste au cadre de recadrage`() {
        show()
        compose.onNodeWithTag("apercu").performTouchInput { swipe(center, center + Offset(width / 3f, 0f)) }
        compose.waitForIdle()

        assertTrue(cropDrags > 0)
        assertEquals(0, starts)
    }
}
