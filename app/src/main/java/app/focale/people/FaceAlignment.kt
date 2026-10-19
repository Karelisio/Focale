package app.focale.people

import app.focale.ml.ImageMetrics
import kotlin.math.floor
import kotlin.math.hypot

/** Point d'une image, en pixels. */
data class FacePoint(val x: Float, val y: Float)

/** Transformation affine 2 × 3 : x' = a·x + b·y + c, y' = d·x + e·y + f. */
data class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {

    fun apply(point: FacePoint): FacePoint =
        FacePoint((a * point.x + b * point.y + c).toFloat(), (d * point.x + e * point.y + f).toFloat())

    /** Facteur d'échelle d'une similitude (pixels de sortie par pixel source). */
    val scale: Double get() = hypot(a, d)

    fun inverse(): Affine {
        val det = a * e - b * d
        require(det != 0.0) { "Transformation non inversible" }
        val ia = e / det
        val ib = -b / det
        val id = -d / det
        val ie = a / det
        return Affine(ia, ib, -(ia * c + ib * f), id, ie, -(id * c + ie * f))
    }
}

/**
 * Alignement d'un visage pour SFace, aux conventions de `FaceRecognizerSF.alignCrop`
 * d'OpenCV : similitude (rotation, échelle uniforme, translation) amenant les cinq points
 * de repère sur le gabarit ArcFace 112 × 112, puis rééchantillonnage bilinéaire.
 * Logique pure, testable sur la JVM.
 */
object FaceAlignment {

    const val SIZE = 112

    /**
     * Gabarit ArcFace : œil à gauche de l'image, œil à droite, nez, coin gauche puis coin
     * droit de la bouche (visage de face, non retourné).
     */
    val TEMPLATE: List<FacePoint> = listOf(
        FacePoint(38.2946f, 51.6963f),
        FacePoint(73.5318f, 51.5014f),
        FacePoint(56.0252f, 71.7366f),
        FacePoint(41.5493f, 92.3655f),
        FacePoint(70.7299f, 92.2041f),
    )

    /**
     * Similitude des moindres carrés de [source] vers [target] (Umeyama sans réflexion).
     * En 2D, la solution est explicite : avec les points centrés (x, y) → (u, v),
     * a = Σ(xu + yv) / Σ(x² + y²) et b = Σ(xv − yu) / Σ(x² + y²) donnent la partie linéaire
     * [[a, −b], [b, a]].
     */
    fun similarity(source: List<FacePoint>, target: List<FacePoint> = TEMPLATE): Affine {
        require(source.size == target.size && source.size >= 2) { "Points de repère incomplets" }
        val n = source.size
        val sx = source.sumOf { it.x.toDouble() } / n
        val sy = source.sumOf { it.y.toDouble() } / n
        val tx = target.sumOf { it.x.toDouble() } / n
        val ty = target.sumOf { it.y.toDouble() } / n
        var dotSum = 0.0
        var crossSum = 0.0
        var squares = 0.0
        for (i in 0 until n) {
            val x = source[i].x - sx
            val y = source[i].y - sy
            val u = target[i].x - tx
            val v = target[i].y - ty
            dotSum += x * u + y * v
            crossSum += x * v - y * u
            squares += x * x + y * y
        }
        require(squares > 0.0) { "Points de repère confondus" }
        val a = dotSum / squares
        val b = crossSum / squares
        return Affine(a, -b, tx - (a * sx - b * sy), b, a, ty - (b * sx + a * sy))
    }

    /**
     * Met les points dans l'ordre du gabarit à partir des repères nommés du point de vue de
     * la personne (son œil droit apparaît à gauche de l'image). Si le visage est en miroir
     * ou si la convention diffère, les paires sont permutées : une similitude ne peut pas
     * représenter une réflexion, l'alignement serait faux.
     */
    fun ordered(
        rightEye: FacePoint,
        leftEye: FacePoint,
        nose: FacePoint,
        mouthRight: FacePoint,
        mouthLeft: FacePoint,
    ): List<FacePoint> {
        var eye0 = rightEye
        var eye1 = leftEye
        var mouth0 = mouthRight
        var mouth1 = mouthLeft
        // Dans le gabarit (y vers le bas), œil 0 → œil 1 puis yeux → bouche tournent dans
        // le sens positif : produit vectoriel > 0.
        val ex = eye1.x - eye0.x
        val ey = eye1.y - eye0.y
        val downX = (mouth0.x + mouth1.x - eye0.x - eye1.x) / 2
        val downY = (mouth0.y + mouth1.y - eye0.y - eye1.y) / 2
        if (ex * downY - ey * downX < 0) {
            eye0 = leftEye
            eye1 = rightEye
        }
        // Les coins de la bouche vont dans le même sens que les yeux.
        val mx = mouth1.x - mouth0.x
        val my = mouth1.y - mouth0.y
        if (mx * (eye1.x - eye0.x) + my * (eye1.y - eye0.y) < 0) {
            mouth0 = mouth1.also { mouth1 = mouth0 }
        }
        return listOf(eye0, eye1, nose, mouth0, mouth1)
    }

    /**
     * Visage aligné de [size] × [size] pixels ARGB : chaque pixel de sortie est lu dans
     * [pixels] (image [width] × [height]) par la transformation inverse de [transform], avec
     * interpolation bilinéaire, noir hors de l'image (comme `warpAffine` en bord constant).
     */
    fun warp(pixels: IntArray, width: Int, height: Int, transform: Affine, size: Int = SIZE): IntArray {
        require(pixels.size >= width * height) { "Image incomplète" }
        val inverse = transform.inverse()
        val out = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val sx = inverse.a * x + inverse.b * y + inverse.c
                val sy = inverse.d * x + inverse.e * y + inverse.f
                out[y * size + x] = sample(pixels, width, height, sx, sy)
            }
        }
        return out
    }

    private fun sample(pixels: IntArray, width: Int, height: Int, x: Double, y: Double): Int {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val p00 = pixelOrBlack(pixels, width, height, x0, y0)
        val p10 = pixelOrBlack(pixels, width, height, x0 + 1, y0)
        val p01 = pixelOrBlack(pixels, width, height, x0, y0 + 1)
        val p11 = pixelOrBlack(pixels, width, height, x0 + 1, y0 + 1)
        var argb = 0xFF shl 24
        for (shift in intArrayOf(16, 8, 0)) {
            val top = channel(p00, shift) * (1 - fx) + channel(p10, shift) * fx
            val bottom = channel(p01, shift) * (1 - fx) + channel(p11, shift) * fx
            val value = (top * (1 - fy) + bottom * fy + 0.5).toInt().coerceIn(0, 255)
            argb = argb or (value shl shift)
        }
        return argb
    }

    private fun pixelOrBlack(pixels: IntArray, width: Int, height: Int, x: Int, y: Int): Int =
        if (x in 0 until width && y in 0 until height) pixels[y * width + x] else 0

    private fun channel(argb: Int, shift: Int): Double = ((argb shr shift) and 0xFF).toDouble()

    /**
     * Tenseur d'entrée de SFace (NCHW, lot de 1) : plans rouge, vert puis bleu, valeurs
     * brutes 0..255 — le modèle retranche 127,5 et multiplie par 1/128 lui-même. C'est
     * l'ordre RVB produit par `blobFromImage(…, swapRB = true)` dans `FaceRecognizerSF`.
     */
    fun tensor(aligned: IntArray): FloatArray {
        val plane = aligned.size
        val out = FloatArray(plane * 3)
        for (i in 0 until plane) {
            val argb = aligned[i]
            out[i] = ((argb shr 16) and 0xFF).toFloat()
            out[plane + i] = ((argb shr 8) and 0xFF).toFloat()
            out[2 * plane + i] = (argb and 0xFF).toFloat()
        }
        return out
    }

    /**
     * Netteté du visage aligné : variance du laplacien sur la zone centrale (yeux, nez,
     * bouche), sans les bords noirs ni l'arrière-plan.
     */
    fun sharpness(aligned: IntArray, size: Int = SIZE): Double {
        val margin = size / 7
        val inner = size - 2 * margin
        val gray = IntArray(inner * inner)
        for (y in 0 until inner) {
            for (x in 0 until inner) {
                gray[y * inner + x] = ImageMetrics.luminance(aligned[(y + margin) * size + x + margin])
            }
        }
        return ImageMetrics.laplacianVariance(gray, inner, inner)
    }
}
