package app.focale.ui.components

import androidx.compose.material3.toPath
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.Morph

/**
 * Forme intermédiaire entre deux polygones arrondis ([Morph] de `graphics-shapes`).
 * Les polygones de `MaterialShapes` sont normalisés dans le carré unité : le chemin
 * est simplement mis à l'échelle de la taille du composant.
 *
 * @param progress 0 = forme de départ, 1 = forme d'arrivée.
 */
class MorphShape(
    private val morph: Morph,
    private val progress: Float,
    private val startAngle: Int = 0,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        morph.toPath(progress = progress, path = path, startAngle = startAngle)
        path.transform(Matrix().apply { scale(size.width, size.height) })
        return Outline.Generic(path)
    }
}
