package app.focale.map

import androidx.compose.runtime.Immutable
import app.focale.core.database.GeoPhoto
import app.focale.core.model.GeoArea
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** Groupe de photos proches les unes des autres au niveau de zoom courant. */
@Immutable
data class PhotoCluster(
    val latitude: Double,
    val longitude: Double,
    val count: Int,
    /** Photo la plus récente du groupe, utilisée comme vignette. */
    val cover: GeoPhoto,
    /** Emprise des photos du groupe. */
    val area: GeoArea,
)

/**
 * Regroupement par grille en projection de Mercator : à un niveau de zoom donné, une
 * cellule mesure [cellPx] pixels à l'écran, et toutes les photos d'une cellule forment
 * un groupe. Linéaire en nombre de photos (50 000 photos : quelques millisecondes).
 */
object PhotoClustering {

    private const val MAX_LATITUDE = 85.05112878

    /**
     * @param photos triées de la plus récente à la plus ancienne (la première de chaque
     *   cellule devient sa vignette).
     * @param worldSizePx largeur du monde en pixels au niveau de zoom considéré.
     */
    fun cluster(photos: List<GeoPhoto>, worldSizePx: Double, cellPx: Double): List<PhotoCluster> {
        val cellsPerSide = max(1.0, worldSizePx / cellPx)
        val cells = LinkedHashMap<Long, Accumulator>()
        for (photo in photos) {
            val column = floor(mercatorX(photo.longitude) * cellsPerSide).toLong()
            val row = floor(mercatorY(photo.latitude) * cellsPerSide).toLong()
            cells.getOrPut((column shl 32) or (row and 0xFFFFFFFFL)) { Accumulator(photo) }.add(photo)
        }
        return cells.values.map(Accumulator::toCluster)
    }

    /** Abscisse de Mercator normalisée (0 à 1). */
    fun mercatorX(longitude: Double): Double = ((longitude + 180.0) / 360.0).coerceIn(0.0, 1.0 - 1e-12)

    /** Ordonnée de Mercator normalisée (0 au nord, 1 au sud). */
    fun mercatorY(latitude: Double): Double {
        val radians = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE) * PI / 180.0
        val y = 0.5 - ln(tan(PI / 4 + radians / 2)) / (2 * PI)
        return y.coerceIn(0.0, 1.0 - 1e-12)
    }

    private class Accumulator(val cover: GeoPhoto) {
        var count = 0
        var latitudeSum = 0.0
        var longitudeSum = 0.0
        var south = cover.latitude
        var north = cover.latitude
        var west = cover.longitude
        var east = cover.longitude

        fun add(photo: GeoPhoto) {
            count++
            latitudeSum += photo.latitude
            longitudeSum += photo.longitude
            south = min(south, photo.latitude)
            north = max(north, photo.latitude)
            west = min(west, photo.longitude)
            east = max(east, photo.longitude)
        }

        fun toCluster(): PhotoCluster = PhotoCluster(
            latitude = latitudeSum / count,
            longitude = longitudeSum / count,
            count = count,
            cover = cover,
            area = GeoArea(south = south, west = west, north = north, east = east),
        )
    }
}
