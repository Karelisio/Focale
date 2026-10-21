package app.focale.ui.viewer

import android.net.Uri
import androidx.compose.foundation.gestures.awaitEachGesture
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
import androidx.compose.ui.unit.dp
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaType
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.withSign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Geste réel à deux doigts sur le détecteur de rotation, au-dessus d'un contenu qui écoute aussi. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RotationGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private val photo = MediaItem(
        id = 7, uri = Uri.EMPTY, type = MediaType.IMAGE, mimeType = "image/jpeg", displayName = "photo.jpg",
        relativePath = "DCIM/Camera/", bucketId = 1, bucketName = "Camera", dateTaken = 0, dateModified = 0,
        size = 0, width = 4000, height = 3000, orientation = 0, durationMs = 0, isGif = false, isScreenshot = false,
    )

    private val haptics = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
    }

    /** Fait tourner deux doigts de [degrees] autour du centre, par pas de 5°, et relève-les. */
    private fun rotate(state: PhotoRotationState, degrees: Float, onContentEvent: (consumed: Boolean) -> Unit = {}) {
        compose.setContent {
            Box(
                Modifier
                    .size(300.dp)
                    .testTag("photo")
                    .rotationGesture(state, { photo }, haptics),
            ) {
                // Comme telephoto : le contenu reçoit les évènements après le détecteur.
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                do {
                                    val event = awaitPointerEvent()
                                    onContentEvent(event.changes.any { it.isConsumed })
                                } while (event.changes.any { it.pressed })
                            }
                        },
                )
            }
        }
        compose.onNodeWithTag("photo").performTouchInput {
            val radius = width / 4f
            fun at(angle: Float, opposite: Boolean): Offset {
                val radians = Math.toRadians((angle + if (opposite) 180f else 0f).toDouble())
                return center + Offset((radius * cos(radians)).toFloat(), (radius * sin(radians)).toFloat())
            }
            down(0, at(0f, false))
            down(1, at(0f, true))
            for (step in 1..(abs(degrees) / 5f).toInt()) {
                val angle = (step * 5f).withSign(degrees)
                updatePointerTo(0, at(angle, false))
                updatePointerTo(1, at(angle, true))
                move()
            }
            up(0)
            up(1)
        }
        compose.waitForIdle()
    }

    @Test
    fun `un quart de tour des doigts vise un quart de tour, sans rien consommer`() {
        val state = PhotoRotationState()
        var consumed = false
        rotate(state, 90f) { if (it) consumed = true }

        assertEquals(photo.id, state.item?.id)
        assertEquals(1, state.turns)
        assertEquals(1, state.pending)
        assertFalse(state.dragging)
        assertFalse("le détecteur ne doit rien consommer", consumed)
    }

    @Test
    fun `une petite rotation de pincement est ignorée`() {
        val state = PhotoRotationState()
        rotate(state, 10f)

        assertNull(state.item)
        assertEquals(0, state.turns)
        assertEquals(0f, state.angle, 0f)
    }

    @Test
    fun `une rotation sous la moitié du quart de tour revient à 0°`() {
        val state = PhotoRotationState()
        rotate(state, 40f)

        assertTrue(state.item != null)
        assertEquals(0, state.turns)
        assertEquals(0, state.pending)
    }

    @Test
    fun `sens antihoraire`() {
        val state = PhotoRotationState()
        rotate(state, -90f)

        assertEquals(-1, state.turns)
        assertEquals(3, state.pending)
    }
}
