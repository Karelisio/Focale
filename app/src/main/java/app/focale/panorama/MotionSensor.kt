package app.focale.panorama

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * Orientation du téléphone pour « Bouger le téléphone » : vecteur de rotation « jeu » (gyroscope
 * et accéléromètre, sans boussole : pas de saut près d'un objet métallique), à défaut vecteur de
 * rotation ordinaire. Sans l'un ni l'autre, l'option n'est pas proposée.
 */
object MotionSensor {

    fun find(manager: SensorManager?): Sensor? =
        manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /**
     * Matrices 3 × 3 (lignes à la suite) du repère du téléphone vers le monde, tant que le flux
     * est collecté ; seule la plus récente attend si le collecteur prend du retard.
     */
    fun rotations(manager: SensorManager, sensor: Sensor): Flow<FloatArray> = callbackFlow {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val matrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                trySend(matrix)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { manager.unregisterListener(listener) }
    }.buffer(Channel.CONFLATED)
}

/**
 * Cap du regard (degrés, croissant vers la droite) d'une mesure à l'autre, pour parcourir un
 * panorama à plat en tournant sur soi : seule la variation compte, ramenée dans [-180, 180[
 * pour qu'un passage par le nord ne fasse pas faire un tour à l'image.
 */
class HeadingTracker {
    private var last: Double? = null

    /** Rotation depuis la mesure précédente ; 0 pour la première. */
    fun update(rotation: FloatArray, displayQuarterTurns: Int): Double {
        val heading = SphereCamera.yawOf(SphereCamera.cameraToWorld(rotation, displayQuarterTurns))
        val previous = last
        last = heading
        return if (previous == null) 0.0 else SphereMath.wrap(heading - previous)
    }

    fun reset() {
        last = null
    }
}
