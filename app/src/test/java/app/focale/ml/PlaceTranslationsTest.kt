package app.focale.ml

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PlaceTranslationsTest {

    /** Base au format de `tools/geonames/build_cities.py` ; sans [names], au format FCG1. */
    private fun base(places: List<Triple<Double, Double, Pair<String, String>>>, names: List<List<String?>>? = null): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun text(value: String?) {
                val encoded = value.orEmpty().toByteArray(Charsets.UTF_8)
                out.writeByte(encoded.size)
                out.write(encoded)
            }
            if (names == null) {
                out.writeBytes("FCG1")
            } else {
                out.writeBytes("FCG2")
                val table = ByteArrayOutputStream()
                DataOutputStream(table).use { entries ->
                    for (entry in names) {
                        entries.writeBytes(entry[0]!!)
                        for (value in entry.drop(1)) {
                            val encoded = value.orEmpty().toByteArray(Charsets.UTF_8)
                            entries.writeByte(encoded.size)
                            entries.write(encoded)
                        }
                    }
                }
                out.writeInt(names.size)
                out.writeInt(table.size())
                out.write(table.toByteArray())
            }
            out.writeInt(places.size)
            for ((lat, lon, place) in places) {
                out.writeInt(Math.round(lat * 1e5).toInt())
                out.writeInt(Math.round(lon * 1e5).toInt())
                out.writeBytes(place.first)
                text(place.second)
            }
        }
        return bytes.toByteArray()
    }

    private val places = listOf(
        Triple(38.72509, -9.1498, "PT" to "Lisbon"),
        Triple(50.93333, 6.95, "DE" to "Köln"),
        Triple(48.85341, 2.3488, "FR" to "Paris"),
    )
    private val names = listOf(
        listOf("DE", "Köln", "Cologne", "Cologne", null),
        listOf("PT", "Lisbon", "Lisbonne", null, null),
        listOf("SA", "Makkah", "La Mecque", null, "Mecca"),
    )

    @Test
    fun `une base FCG2 donne ses noms traduits et ses lieux`() {
        val bytes = base(places, names)
        val translations = PlaceTranslations.read(bytes.inputStream())
        assertEquals(3, translations.size)
        assertEquals(PlaceName("Lisbon", french = "Lisbonne"), translations.of("Lisbon", "PT"))
        assertEquals(PlaceName("Köln", "Cologne", "Cologne"), translations.of("Köln", "DE"))
        assertEquals(PlaceName("Makkah", "La Mecque", alias = "Mecca"), translations.of("Makkah", "SA"))
        // Même nom dans un autre pays, ou lieu sans traduction : le nom GeoNames seul.
        assertEquals(PlaceName("Lisbon"), translations.of("Lisbon", "US"))
        assertEquals(PlaceName("Paris"), translations.of("Paris", "FR"))
        // L'index des lieux saute la table des noms.
        val index = PlaceIndex.read(bytes.inputStream())
        assertEquals(3, index.size)
        assertEquals("Köln", index.nearest(50.94, 6.96)?.name)
    }

    @Test
    fun `une base FCG1 se lit encore, sans noms traduits`() {
        val bytes = base(places)
        assertSame(PlaceTranslations.EMPTY, PlaceTranslations.read(bytes.inputStream()))
        val index = PlaceIndex.read(bytes.inputStream())
        assertEquals(3, index.size)
        assertEquals("Lisbon", index.nearest(38.7, -9.1)?.name)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `une base d'un autre format est refusée`() {
        PlaceTranslations.read("FCG9".toByteArray().inputStream())
    }

    @Test
    fun `le nom suit la langue de l'application`() {
        val lisbon = PlaceName("Lisbon", french = "Lisbonne")
        assertEquals("Lisbonne", lisbon.display(Locale.FRENCH))
        assertEquals("Lisbonne", lisbon.display(Locale.CANADA_FRENCH))
        assertEquals("Lisbon", lisbon.display(Locale.ENGLISH))
        // Langue sans traduction (l'application est alors en anglais) : nom anglais, sinon GeoNames.
        assertEquals("Lisbon", lisbon.display(Locale.GERMAN))
        val cologne = PlaceName("Köln", "Cologne", "Cologne")
        assertEquals("Cologne", cologne.display(Locale.UK))
        assertEquals("Paris", PlaceName("Paris").display(Locale.FRENCH))
        // Un alias se cherche mais ne s'affiche pas.
        val mecca = PlaceName("Makkah", "La Mecque", alias = "Mecca")
        assertEquals("Makkah", mecca.display(Locale.ENGLISH))
        assertEquals("La Mecque", mecca.display(Locale.FRENCH))
    }

    @Test
    fun `la clé de recherche réunit les noms, celui de GeoNames d'abord`() {
        assertEquals("lisbon | lisbonne", PlaceTranslations.searchKey(PlaceName("Lisbon", french = "Lisbonne")))
        assertEquals("koln | cologne", PlaceTranslations.searchKey(PlaceName("Köln", "Cologne", "Cologne")))
        assertEquals("makkah | la mecque | mecca", PlaceTranslations.searchKey(PlaceName("Makkah", "La Mecque", alias = "Mecca")))
        // Une forme qui ne diffère que par les accents n'est pas répétée.
        assertEquals("montreal", PlaceTranslations.searchKey(PlaceName("Montreal", french = "Montréal")))
        // Sans traduction, la clé est le nom normalisé, comme avant les traductions.
        assertEquals("saint malo", PlaceTranslations.searchKey(PlaceName("Saint-Malo")))
    }

    @Test
    fun `la base embarquée traduit les grandes villes en français`() {
        assertTrue("noms traduits : ${embedded.size}", embedded.size in 3_000..6_000)
        assertEquals("Lisbonne", embedded.of("Lisbon", "PT").display(Locale.FRENCH))
        assertEquals("Lisbon", embedded.of("Lisbon", "PT").display(Locale.ENGLISH))
        assertEquals("Londres", embedded.of("London", "GB").french)
        assertEquals("Cologne", embedded.of("Köln", "DE").display(Locale.ENGLISH))
        assertEquals("Aix-la-Chapelle", embedded.of("Aachen", "DE").french)
        assertEquals("Pékin", embedded.of("Beijing", "CN").french)
        assertEquals(PlaceName("Paris"), embedded.of("Paris", "FR"))
        // Le nom GeoNames reste la forme anglaise affichée : pas de « St Petersburg ».
        assertNull(embedded.of("Saint Petersburg", "RU").english)
        // Les autres noms anglais se cherchent sans s'afficher.
        assertEquals("Mecca", embedded.of("Makkah", "SA").alias)
        assertEquals("Hanover", embedded.of("Hannover", "DE").alias)
        assertEquals("Hannover", embedded.of("Hannover", "DE").display(Locale.ENGLISH))
    }

    companion object {
        private lateinit var embedded: PlaceTranslations

        @BeforeClass
        @JvmStatic
        fun load() {
            val file = listOf("src/main/assets", "app/src/main/assets").map { File(it, OfflineGeocoder.ASSET) }.first { it.exists() }
            embedded = file.inputStream().use(PlaceTranslations::read)
        }
    }
}
