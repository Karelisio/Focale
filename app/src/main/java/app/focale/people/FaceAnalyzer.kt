package app.focale.people

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.util.Size
import app.focale.core.di.IoDispatcher
import app.focale.core.model.MediaItem
import app.focale.ml.ImageMetrics
import app.focale.ml.await
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Visage retenu sur une photo : cadre normalisé, empreinte et note de qualité. */
class DetectedFace(val box: FaceBox, val embedding: FloatArray, val quality: Float)

/**
 * Détection et description des visages d'une photo, entièrement sur l'appareil :
 * ML Kit Face Detection (modèle embarqué) trouve les visages et leurs points de repère,
 * [FaceAlignment] les redresse, [SFaceModel] calcule leur empreinte.
 *
 * Les modèles sont chargés au premier usage et libérés par [close] à la fin de chaque
 * passage de l'analyse.
 */
@Singleton
class FaceAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolver: ContentResolver,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private var detector: FaceDetector? = null
    private var model: SFaceModel? = null

    /** Charge les modèles ; une erreur ici concerne toute l'étape, pas une photo. */
    suspend fun prepare() {
        withContext(io) {
            detector()
            model()
        }
    }

    suspend fun analyze(item: MediaItem): List<DetectedFace> {
        val bitmap = withContext(io) { decode(item) } ?: return emptyList()
        try {
            val faces = detector().process(InputImage.fromBitmap(bitmap, 0)).await()
            if (faces.isEmpty()) return emptyList()
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            return faces.mapNotNull { describe(it, pixels, width, height) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun describe(face: Face, pixels: IntArray, width: Int, height: Int): DetectedFace? {
        val bounds = face.boundingBox
        val size = min(bounds.width(), bounds.height())
        val yaw = face.headEulerAngleY
        val pitch = face.headEulerAngleX
        if (!FaceQuality.acceptsPose(size, yaw, pitch)) return null
        val points = FaceAlignment.ordered(
            rightEye = face.point(FaceLandmark.RIGHT_EYE) ?: return null,
            leftEye = face.point(FaceLandmark.LEFT_EYE) ?: return null,
            nose = face.point(FaceLandmark.NOSE_BASE) ?: return null,
            mouthRight = face.point(FaceLandmark.MOUTH_RIGHT) ?: return null,
            mouthLeft = face.point(FaceLandmark.MOUTH_LEFT) ?: return null,
        )
        val aligned = FaceAlignment.warp(pixels, width, height, FaceAlignment.similarity(points))
        val sharpness = FaceAlignment.sharpness(aligned)
        if (!FaceQuality.acceptsSharpness(sharpness)) return null
        val embedding = model().embed(FaceAlignment.tensor(aligned))
        val box = FaceBox(
            left = (bounds.left.toFloat() / width).coerceIn(0f, 1f),
            top = (bounds.top.toFloat() / height).coerceIn(0f, 1f),
            right = (bounds.right.toFloat() / width).coerceIn(0f, 1f),
            bottom = (bounds.bottom.toFloat() / height).coerceIn(0f, 1f),
        )
        return DetectedFace(box, embedding, FaceQuality.score(size, yaw, pitch, sharpness))
    }

    private fun Face.point(type: Int): FacePoint? = getLandmark(type)?.position?.let { FacePoint(it.x, it.y) }

    /**
     * Photo réduite (côté le plus long ≤ [DETECTION_SIZE]), orientation EXIF appliquée par
     * `ImageDecoder`, en sRGB ; à défaut, la miniature MediaStore (orientée elle aussi).
     */
    private fun decode(item: MediaItem): Bitmap? {
        val decoded = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, item.uri)) { decoder, info, _ ->
                val (width, height) = ImageMetrics.fit(info.size.width, info.size.height, DETECTION_SIZE)
                if (width < info.size.width) decoder.setTargetSize(width, height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            runCatching { resolver.loadThumbnail(item.uri, Size(DETECTION_SIZE, DETECTION_SIZE), null) }.getOrNull()
        } ?: return null
        if (decoded.config == Bitmap.Config.ARGB_8888) return decoded
        return decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
    }

    @Synchronized
    private fun detector(): FaceDetector = detector ?: FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(MIN_FACE_RATIO)
            .build(),
    ).also { detector = it }

    @Synchronized
    private fun model(): SFaceModel = model ?: SFaceModel(
        context.assets.open(SFaceModel.ASSET).use { it.readBytes() },
    ).also { model = it }

    /** Libère les modèles (fin d'un passage d'analyse). */
    @Synchronized
    fun close() {
        detector?.close()
        detector = null
        model?.close()
        model = null
    }

    private companion object {
        /** Assez de pixels pour les visages d'une photo de groupe, sans décoder l'original. */
        const val DETECTION_SIZE = 1280

        /** Taille minimale d'un visage pour ML Kit, en fraction de la largeur de l'image. */
        const val MIN_FACE_RATIO = 0.04f
    }
}
