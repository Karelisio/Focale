package app.focale.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph

/**
 * Indicateur de sélection Expressive : un cercle vide qui se transforme (morphing)
 * en « cookie » plein avec une coche lorsque l'élément est sélectionné.
 */
@Composable
fun SelectionIndicator(
    selected: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
) {
    val progress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "selectionMorph",
    )
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val shape = remember(progress) { MorphShape(morph, progress) }
    val colors = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { rotationZ = progress * 40f }
            .clip(shape)
            .background(if (selected) colors.primary else Color.Black.copy(alpha = 0.25f))
            .border(width = 2.dp, color = if (selected) colors.primary else Color.White, shape = shape),
        contentAlignment = Alignment.Center,
    ) {
        if (progress > 0.05f) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = colors.onPrimary,
                modifier = Modifier
                    .padding(4.dp)
                    .graphicsLayer {
                        rotationZ = -progress * 40f
                        scaleX = progress
                        scaleY = progress
                    },
            )
        }
    }
}
