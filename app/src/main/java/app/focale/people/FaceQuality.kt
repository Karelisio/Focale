package app.focale.people

import kotlin.math.abs

/**
 * Tri des visages détectés : trop petits, de profil ou flous, ils donneraient des
 * empreintes peu fiables et sont ignorés ; les autres reçoivent une note de qualité (0 à 1)
 * qui désigne les vignettes. Seuils calibrés sur SFace : sous ~30 px de visage dans le
 * gabarit, ou au-delà d'un flou gaussien de σ ≈ 2 px, l'empreinte s'éloigne nettement de
 * celle du même visage net.
 */
object FaceQuality {

    /** Plus petit côté du cadre, en pixels de l'image analysée. */
    const val MIN_FACE_PX = 48

    /** Rotation gauche-droite (lacet) maximale : au-delà, visage de profil. */
    const val MAX_YAW = 35f

    /** Inclinaison haut-bas (tangage) maximale. */
    const val MAX_PITCH = 30f

    /** Variance du laplacien minimale du visage aligné (voir [FaceAlignment.sharpness]). */
    const val MIN_SHARPNESS = 15.0

    /** Pose et taille acceptables, avant l'alignement. */
    fun acceptsPose(sizePx: Int, yaw: Float, pitch: Float): Boolean =
        sizePx >= MIN_FACE_PX && abs(yaw) <= MAX_YAW && abs(pitch) <= MAX_PITCH

    fun acceptsSharpness(sharpness: Double): Boolean = sharpness >= MIN_SHARPNESS

    /** Note de 0 à 1 : grand, de face et net. */
    fun score(sizePx: Int, yaw: Float, pitch: Float, sharpness: Double): Float {
        val size = (sizePx / GOOD_FACE_PX).coerceIn(0f, 1f)
        val pose = (1f - abs(yaw) / (MAX_YAW * 1.5f)).coerceIn(0f, 1f) * (1f - abs(pitch) / (MAX_PITCH * 1.5f)).coerceIn(0f, 1f)
        val sharp = (sharpness / GOOD_SHARPNESS).toFloat().coerceIn(0f, 1f)
        return 0.4f * size + 0.35f * pose + 0.25f * sharp
    }

    private const val GOOD_FACE_PX = 192f
    private const val GOOD_SHARPNESS = 250.0
}
