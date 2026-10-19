package app.focale.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Formes généreuses, dans l'esprit Material 3 Expressive. */
val FocaleShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
    largeIncreased = RoundedCornerShape(32.dp),
    extraLargeIncreased = RoundedCornerShape(44.dp),
    extraExtraLarge = RoundedCornerShape(52.dp),
)

/** Rayon des miniatures : plus la grille est dense, plus les coins sont discrets. */
fun thumbnailCornerRadius(columns: Int): Dp = when {
    columns <= 2 -> 24.dp
    columns == 3 -> 18.dp
    columns == 4 -> 14.dp
    columns == 5 -> 10.dp
    else -> 6.dp
}
