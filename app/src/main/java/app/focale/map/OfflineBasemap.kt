package app.focale.map

import android.content.Context
import app.focale.core.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Nature d'une couche du fond de carte. */
enum class BasemapKind { LAND, LAKE, BORDER }

/**
 * Couche vectorielle : anneaux (polygones) ou lignes, chacun sous la forme
 * `[lat0, lon0, lat1, lon1, …]` en degrés.
 */
class BasemapLayer(val kind: BasemapKind, val detailed: Boolean, val rings: List<DoubleArray>)

/** Nom d'un pays, affiché à partir du niveau de zoom [minZoom]. */
class CountryLabel(
    val latitude: Double,
    val longitude: Double,
    val minZoom: Int,
    val nameEn: String,
    val nameFr: String,
)

class Basemap(val layers: List<BasemapLayer>, val labels: List<CountryLabel>)

/**
 * Fond de carte embarqué (Natural Earth, domaine public) : terres, lacs, frontières
 * et noms de pays, en deux niveaux de détail. Chargé une seule fois, à la demande.
 * Généré par `tools/basemap/build_basemap.py`.
 */
@Singleton
class OfflineBasemap @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private var cached: Basemap? = null

    suspend fun load(): Basemap = mutex.withLock {
        cached ?: withContext(io) { context.assets.open(ASSET).use(BasemapReader::read) }.also { cached = it }
    }

    private companion object {
        const val ASSET = "geo/basemap.bin"
    }
}

/** Décodeur du format FBM1 (voir le script de génération pour sa description). */
object BasemapReader {

    private const val SCALE = 100_000.0

    fun read(input: InputStream): Basemap {
        val data = DataInputStream(BufferedInputStream(input, 64 * 1024))
        val magic = ByteArray(4).also(data::readFully)
        if (!magic.contentEquals("FBM1".toByteArray(Charsets.US_ASCII))) throw IOException("Fond de carte invalide")
        val layers = List(data.readUnsignedByte()) {
            val kind = BasemapKind.entries[data.readUnsignedByte()]
            val detailed = data.readUnsignedByte() == 1
            BasemapLayer(kind, detailed, List(data.readInt()) { readRing(data) })
        }
        val labels = List(data.readInt()) {
            CountryLabel(
                latitude = data.readInt() / SCALE,
                longitude = data.readInt() / SCALE,
                minZoom = data.readUnsignedByte(),
                nameEn = readString(data),
                nameFr = readString(data),
            )
        }
        return Basemap(layers, labels)
    }

    private fun readRing(data: DataInputStream): DoubleArray {
        val count = data.readInt()
        val coordinates = DoubleArray(count * 2)
        var lat = 0L
        var lon = 0L
        for (i in 0 until count) {
            lat += readVarint(data)
            lon += readVarint(data)
            coordinates[2 * i] = lat / SCALE
            coordinates[2 * i + 1] = lon / SCALE
        }
        return coordinates
    }

    /** Entier signé codé en zigzag sur une suite d'octets de 7 bits. */
    private fun readVarint(data: DataInputStream): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val byte = data.readUnsignedByte()
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
        }
        return (result ushr 1) xor -(result and 1)
    }

    private fun readString(data: DataInputStream): String {
        val bytes = ByteArray(data.readUnsignedShort()).also(data::readFully)
        return String(bytes, Charsets.UTF_8)
    }
}
