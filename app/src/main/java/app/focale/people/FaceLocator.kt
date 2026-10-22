package app.focale.people

import android.graphics.Bitmap
import app.focale.ml.await
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cadres des visages d'une image déjà décodée, pour l'outil Flouter de l'éditeur : détection
 * ML Kit seule (modèle embarqué, sur l'appareil), sans points de repère, empreinte ni filtre de
 * qualité — un visage flou ou de profil doit être flouté aussi. Le détecteur est fermé après
 * chaque appel, l'analyse ([FaceAnalyzer]) gardant le sien.
 */
@Singleton
class FaceLocator @Inject constructor() {

    /** Cadres normalisés (0..1) des visages de [bitmap], dans son orientation. */
    suspend fun locate(bitmap: Bitmap): List<FaceBox> {
        val image = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setMinFaceSize(MIN_FACE_RATIO)
                .build(),
        )
        return try {
            val width = image.width.toFloat()
            val height = image.height.toFloat()
            detector.process(InputImage.fromBitmap(image, 0)).await().map { face ->
                val bounds = face.boundingBox
                FaceBox(
                    left = (bounds.left / width).coerceIn(0f, 1f),
                    top = (bounds.top / height).coerceIn(0f, 1f),
                    right = (bounds.right / width).coerceIn(0f, 1f),
                    bottom = (bounds.bottom / height).coerceIn(0f, 1f),
                )
            }.filter { it.width > 0f && it.height > 0f }
        } finally {
            detector.close()
            if (image !== bitmap) image.recycle()
        }
    }

    private companion object {
        /** Petits visages compris (photo de groupe), en fraction de la largeur de l'image. */
        const val MIN_FACE_RATIO = 0.02f
    }
}
