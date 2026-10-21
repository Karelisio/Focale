package app.focale.ml

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import app.focale.core.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.DataInputStream
import java.io.InputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Nom d'un lieu de la base GeoNames ([name], celui qu'enregistre l'analyse), et ses formes
 * française et anglaise quand elles en diffèrent (« Lisbon », « Lisbonne ») ; [alias] : autre nom
 * anglais, cherché mais jamais affiché (« Makkah » : « Mecca »).
 */
@Immutable
data class PlaceName(
    val name: String,
    val french: String? = null,
    val english: String? = null,
    val alias: String? = null,
) {

    /** Nom dans la langue de l'application : français pour une [locale] française, anglais sinon. */
    fun display(locale: Locale): String = (if (locale.language == Locale.FRENCH.language) french else english) ?: name

    /** Toutes les formes du nom, celui de GeoNames d'abord (recherche). */
    val forms: List<String> get() = listOfNotNull(name, french, english, alias).distinct()
}

/**
 * Noms français et anglais des lieux de la base embarquée, par pays et nom GeoNames : seuls
 * figurent ceux qui diffèrent du nom GeoNames (`tools/geonames/build_cities.py`).
 */
class PlaceTranslations(private val names: Map<String, PlaceName>) {

    val size: Int get() = names.size

    /** Noms du lieu [name] de [country] (code ISO 3166-1) ; le seul nom GeoNames s'il n'en a pas d'autre. */
    fun of(name: String, country: String?): PlaceName = names[key(country, name)] ?: PlaceName(name)

    /** Clé de recherche du lieu (colonne `media_analysis.place_key`), voir [searchKey]. */
    fun searchKey(name: String, country: String?): String = searchKey(of(name, country))

    companion object {
        val EMPTY = PlaceTranslations(emptyMap())

        /** Sépare les formes d'un nom dans sa clé de recherche. */
        const val SEPARATOR = " | "

        /**
         * Clé de recherche : formes normalisées du nom (minuscules, sans accents), celle du nom
         * GeoNames d'abord, puis les autres séparées par [SEPARATOR] : « lisbon | lisbonne ». Un
         * mot cherché trouve ainsi le lieu dans les deux langues ; un lieu sans traduction garde
         * pour clé son seul nom normalisé.
         */
        fun searchKey(place: PlaceName): String =
            place.forms.map(LabelDictionary::normalize).filter { it.isNotEmpty() }.distinct().joinToString(SEPARATOR)

        /**
         * Lit la table des noms traduits d'une base FCG2 (`assets/geo/cities.bin`) sans lire ses
         * lieux ; une base FCG1 n'en a pas.
         */
        fun read(input: InputStream): PlaceTranslations {
            val data = DataInputStream(input.buffered(1 shl 16))
            return when (PlaceIndex.readMagic(data)) {
                PlaceIndex.FORMAT_1 -> EMPTY
                else -> PlaceTranslations(readTable(data))
            }
        }

        /** Table des noms traduits, juste après l'en-tête FCG2. */
        private fun readTable(data: DataInputStream): Map<String, PlaceName> {
            val count = data.readInt()
            data.readInt() // taille de la table, pour qui la saute
            val names = HashMap<String, PlaceName>(count * 2)
            val buffer = ByteArray(255)
            fun text(): String? {
                val length = data.readUnsignedByte()
                if (length == 0) return null
                data.readFully(buffer, 0, length)
                return String(buffer, 0, length, Charsets.UTF_8)
            }
            repeat(count) {
                val country = String(charArrayOf(data.readUnsignedByte().toChar(), data.readUnsignedByte().toChar()))
                val name = text().orEmpty()
                val french = text()
                val english = text()
                names[key(country, name)] = PlaceName(name, french, english, alias = text())
            }
            return names
        }

        private fun key(country: String?, name: String): String = country.orEmpty() + '\u0000' + name
    }
}

/**
 * Noms traduits de la base embarquée, chargés une fois à la première demande (quelques milliers
 * de noms, sans l'index des 171 000 lieux) et gardés en mémoire.
 */
@Singleton
class PlaceNames @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val mutex = Mutex()

    @Volatile
    private var loaded: PlaceTranslations? = null

    suspend fun translations(): PlaceTranslations = loaded ?: mutex.withLock {
        loaded ?: withContext(io) { load() }.also { loaded = it }
    }

    /** Noms du lieu [name] de [country]. */
    suspend fun of(name: String, country: String?): PlaceName = translations().of(name, country)

    private fun load(): PlaceTranslations = try {
        context.assets.open(OfflineGeocoder.ASSET).use(PlaceTranslations::read)
    } catch (error: Exception) {
        // Base illisible : les noms GeoNames restent affichés.
        Log.w(TAG, "Noms traduits des lieux illisibles", error)
        PlaceTranslations.EMPTY
    }

    private companion object {
        const val TAG = "PlaceNames"
    }
}
