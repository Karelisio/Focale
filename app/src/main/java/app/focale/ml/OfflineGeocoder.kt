package app.focale.ml

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.DataInputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/** Lieu résolu : ville la plus proche et pays (ISO 3166-1 alpha-2). */
data class Place(val name: String, val countryCode: String, val distanceKm: Double)

/**
 * Géocodage inverse 100 % hors ligne, à partir d'une base GeoNames embarquée
 * (171 000 localités, licence CC BY 4.0). Les noms français et anglais de la base (FCG2) sont lus
 * à part, par [PlaceTranslations].
 *
 * Représentation compacte en mémoire (≈ 4 Mo) : tableaux primitifs + index spatial
 * en grille de 1° × 1° (stockage CSR), ce qui limite une recherche à quelques dizaines
 * de candidats. La base n'est chargée qu'à la première utilisation.
 */
class PlaceIndex private constructor(
    private val latE5: IntArray,
    private val lonE5: IntArray,
    private val countries: CharArray,
    private val nameOffsets: IntArray,
    private val names: ByteArray,
    private val cellStart: IntArray,
    private val cellItems: IntArray,
) {
    val size: Int get() = latE5.size

    /** Localité la plus proche de (lat, lon), à moins de [maxKm] kilomètres. */
    fun nearest(lat: Double, lon: Double, maxKm: Double = DEFAULT_MAX_KM): Place? {
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val cellLat = floor(lat).toInt() + 90
        val cellLon = floor(lon).toInt() + 180
        val cosLat = cos(Math.toRadians(lat))
        var best = -1
        var bestDistance = Double.MAX_VALUE
        for (dLat in -1..1) {
            val row = cellLat + dLat
            if (row !in 0 until LAT_CELLS) continue
            for (dLon in -1..1) {
                val column = Math.floorMod(cellLon + dLon, LON_CELLS)
                val cell = row * LON_CELLS + column
                for (k in cellStart[cell] until cellStart[cell + 1]) {
                    val i = cellItems[k]
                    val distance = distanceKm(lat, lon, latE5[i] / 1e5, lonE5[i] / 1e5, cosLat)
                    if (distance < bestDistance) {
                        bestDistance = distance
                        best = i
                    }
                }
            }
        }
        if (best < 0 || bestDistance > maxKm) return null
        return Place(name(best), String(charArrayOf(countries[2 * best], countries[2 * best + 1])), bestDistance)
    }

    private fun name(i: Int): String {
        val start = nameOffsets[i]
        val end = nameOffsets[i + 1]
        return String(names, start, end - start, Charsets.UTF_8)
    }

    companion object {
        const val DEFAULT_MAX_KM = 60.0
        private const val LAT_CELLS = 180
        private const val LON_CELLS = 360
        private const val EARTH_KM_PER_DEGREE = 111.195

        /** Distance équirectangulaire : largement suffisante à l'échelle d'une ville. */
        private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double, cosLat: Double): Double {
            var dLon = lon2 - lon1
            if (dLon > 180) dLon -= 360
            if (dLon < -180) dLon += 360
            val x = dLon * cosLat
            val y = lat2 - lat1
            return sqrt(x * x + y * y) * EARTH_KM_PER_DEGREE
        }

        /** Base sans noms traduits (versions précédentes de la base). */
        internal const val FORMAT_1 = "FCG1"

        /** Base précédée de la table des noms français et anglais ([PlaceTranslations]). */
        internal const val FORMAT_2 = "FCG2"

        /** Format de la base, d'après ses quatre premiers octets. */
        internal fun readMagic(data: DataInputStream): String {
            val magic = String(ByteArray(4).also(data::readFully), Charsets.US_ASCII)
            require(magic == FORMAT_1 || magic == FORMAT_2) { "Base de lieux invalide" }
            return magic
        }

        /** Lit le format binaire produit par `tools/geonames/build_cities.py` (FCG2, ou FCG1 sans traductions). */
        fun read(input: InputStream): PlaceIndex {
            val data = DataInputStream(input.buffered(1 shl 16))
            if (readMagic(data) == FORMAT_2) {
                data.readInt() // noms traduits, lus par PlaceTranslations
                data.skipFully(data.readInt())
            }
            val count = data.readInt()
            val lat = IntArray(count)
            val lon = IntArray(count)
            val countries = CharArray(count * 2)
            val offsets = IntArray(count + 1)
            var names = ByteArray(count * 12)
            var cursor = 0
            for (i in 0 until count) {
                lat[i] = data.readInt()
                lon[i] = data.readInt()
                countries[2 * i] = data.readUnsignedByte().toChar()
                countries[2 * i + 1] = data.readUnsignedByte().toChar()
                val length = data.readUnsignedByte()
                if (cursor + length > names.size) names = names.copyOf(maxOf(names.size * 2, cursor + length))
                data.readFully(names, cursor, length)
                offsets[i] = cursor
                cursor += length
            }
            offsets[count] = cursor

            // Index en grille : comptage puis répartition (tri par seau).
            val cells = LAT_CELLS * LON_CELLS
            val cellOf = IntArray(count) { i ->
                val row = (floor(lat[i] / 1e5).toInt() + 90).coerceIn(0, LAT_CELLS - 1)
                val column = Math.floorMod(floor(lon[i] / 1e5).toInt() + 180, LON_CELLS)
                row * LON_CELLS + column
            }
            val start = IntArray(cells + 1)
            cellOf.forEach { start[it + 1]++ }
            for (c in 0 until cells) start[c + 1] += start[c]
            val fill = start.copyOf()
            val items = IntArray(count)
            for (i in 0 until count) items[fill[cellOf[i]]++] = i
            return PlaceIndex(lat, lon, countries, offsets, names.copyOf(cursor), start, items)
        }

        private fun DataInputStream.skipFully(length: Int) {
            var remaining = length
            while (remaining > 0) {
                val skipped = skipBytes(remaining)
                if (skipped <= 0) {
                    readByte() // fin de fichier : EOFException
                    remaining--
                } else {
                    remaining -= skipped
                }
            }
        }
    }
}

/** Accès paresseux à l'index des lieux embarqué dans les assets. */
@Singleton
class OfflineGeocoder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile
    private var index: PlaceIndex? = null

    fun nearest(lat: Double, lon: Double): Place? = load().nearest(lat, lon)

    @Synchronized
    private fun load(): PlaceIndex = index ?: context.assets.open(ASSET).use(PlaceIndex::read).also { index = it }

    /** Libère la mémoire une fois l'analyse terminée. */
    fun release() {
        index = null
    }

    companion object {
        /** Base embarquée : `app/src/main/assets/`. */
        const val ASSET = "geo/cities.bin"
    }
}
