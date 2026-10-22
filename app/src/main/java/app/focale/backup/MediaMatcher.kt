package app.focale.backup

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Clés portables d'un média : les `_ID` de MediaStore changent d'un téléphone à l'autre, son
 * chemin et son nom de fichier restent. Chemin et nom sont comparés sans casse ni différence de
 * normalisation Unicode (un fichier copié depuis un Mac est en NFD), sous forme d'empreintes de
 * 64 bits : une bibliothèque de 100 000 médias tient en quelques mégaoctets, et deux clés
 * distinctes n'ont pratiquement aucune chance de se confondre (de l'ordre de 10⁻⁹).
 */
internal object MediaKeys {

    private val FNV_OFFSET = 0xcbf29ce484222325UL.toLong()
    private const val FNV_PRIME = 0x100000001b3L
    private val MIX_1 = 0xff51afd7ed558ccdUL.toLong()
    private val MIX_2 = 0xc4ceb9fe1a85ec53UL.toLong()

    /** Chemin et nom : la clé de l'appariement par dossier. */
    fun path(relativePath: String, displayName: String): Long = hash(folder(relativePath) + '/' + normalizedName(displayName))

    /** Nom seul : la clé du repli, quand le dossier a changé. */
    fun name(displayName: String): Long = hash(normalizedName(displayName))

    /** `RELATIVE_PATH` normalisé : « DCIM/Camera/ » → « dcim/camera ». */
    fun folder(relativePath: String): String =
        Normalizer.normalize(relativePath.trim().trim('/'), Normalizer.Form.NFC).lowercase(Locale.ROOT)

    private fun normalizedName(displayName: String): String =
        Normalizer.normalize(displayName.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)

    /** FNV-1a 64 bits sur les caractères UTF-16, puis mélange final de MurmurHash3 (`fmix64`). */
    private fun hash(text: String): Long {
        var h = FNV_OFFSET
        for (char in text) h = (h xor char.code.toLong()) * FNV_PRIME
        h = (h xor (h ushr 33)) * MIX_1
        h = (h xor (h ushr 33)) * MIX_2
        return h xor (h ushr 33)
    }
}

/** Tableau de `Long` qui s'agrandit, sans boîte par valeur. */
internal class LongList(capacity: Int = 1024) {
    private var values = LongArray(capacity.coerceAtLeast(16))
    var size = 0
        private set

    fun add(value: Long) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }

    operator fun get(index: Int): Long = values[index]

    fun clear() {
        size = 0
    }

    fun toArray(): LongArray = values.copyOf(size)
}

/** Médias d'une bibliothèque, réduits à leurs clés : ce téléphone, ou la sauvegarde restaurée. */
internal class MediaKeyTable private constructor(
    val pathKeys: LongArray,
    val nameKeys: LongArray,
    val sizes: LongArray,
    val dates: LongArray,
) {
    val size: Int get() = pathKeys.size

    /** Positions triées par clé de chemin, puis par clé de nom : recherche dichotomique des candidats. */
    private val byPath: IntArray = order(pathKeys)
    private val byName: IntArray = order(nameKeys)

    /** Médias de même chemin et même nom que [key] ([MediaKeys.path]). */
    fun withPath(key: Long, action: (Int) -> Unit) = range(byPath, pathKeys, key, action)

    /** Médias de même nom que [key] ([MediaKeys.name]). */
    fun withName(key: Long, action: (Int) -> Unit) = range(byName, nameKeys, key, action)

    private fun range(order: IntArray, keys: LongArray, key: Long, action: (Int) -> Unit) {
        var low = 0
        var high = order.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (keys[order[middle]] < key) low = middle + 1 else high = middle
        }
        var index = low
        while (index < order.size && keys[order[index]] == key) action(order[index++])
    }

    /** Positions dans l'ordre des clés, puis des positions : à clé égale, l'ordre de la bibliothèque. */
    private fun order(keys: LongArray): IntArray =
        keys.indices.sortedWith { a, b -> keys[a].compareTo(keys[b]).takeIf { it != 0 } ?: a.compareTo(b) }.toIntArray()

    /** Construction ligne à ligne, dans l'ordre des médias. */
    class Builder {
        private val pathKeys = LongList()
        private val nameKeys = LongList()
        private val sizes = LongList()
        private val dates = LongList()

        val size: Int get() = pathKeys.size

        fun add(relativePath: String, displayName: String, size: Long, dateTaken: Long) {
            pathKeys.add(MediaKeys.path(relativePath, displayName))
            nameKeys.add(MediaKeys.name(displayName))
            sizes.add(size)
            dates.add(dateTaken)
        }

        fun build() = MediaKeyTable(pathKeys.toArray(), nameKeys.toArray(), sizes.toArray(), dates.toArray())
    }
}

/**
 * Appariement des médias d'une sauvegarde à ceux de ce téléphone, chacun à un seul au plus.
 *
 * 1. Même dossier et même nom (sans casse) : retenu si la taille ou la date de prise de vue
 *    concorde ; à choisir entre plusieurs (même chemin sur la carte SD et dans la mémoire
 *    interne), d'abord les deux, puis la taille, puis la date, puis la date la plus proche.
 * 2. Pour les autres, dossier changé : même nom, même taille et même date de prise de vue.
 *
 * Deux dates concordent si elles sont égales, ou décalées d'un fuseau horaire (multiple d'un quart
 * d'heure, moins de 24 h) : un EXIF sans fuseau est daté par MediaStore selon la date du fichier,
 * que la copie vers un autre téléphone ne garde pas toujours. Tous les dossiers passent avant tout
 * repli : un média trouvé à sa place n'est pas pris par une copie déplacée d'un autre. À égalité,
 * l'ordre de la bibliothèque décide ; le même appariement donne toujours le même résultat.
 */
internal object MediaMatcher {

    private const val QUARTER_HOUR_MS = 15 * 60 * 1000L
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** Concordance de deux dates de prise de vue. */
    enum class DateMatch { EXACT, SHIFTED, NONE }

    fun dates(a: Long, b: Long): DateMatch {
        val difference = a - b
        return when {
            difference == 0L -> DateMatch.EXACT
            abs(difference) < DAY_MS && difference % QUARTER_HOUR_MS == 0L -> DateMatch.SHIFTED
            else -> DateMatch.NONE
        }
    }

    /**
     * Médias de [library] (positions) retrouvés pour chaque média de [backup], -1 pour ceux qui
     * manquent sur ce téléphone.
     */
    fun match(backup: MediaKeyTable, library: MediaKeyTable): IntArray {
        val result = IntArray(backup.size) { -1 }
        val taken = BooleanArray(library.size)
        // 1. Même dossier, même nom.
        for (entry in 0 until backup.size) {
            var best = -1
            var bestRank = Int.MAX_VALUE
            var bestDistance = Long.MAX_VALUE
            library.withPath(backup.pathKeys[entry]) { candidate ->
                if (taken[candidate]) return@withPath
                val sameSize = library.sizes[candidate] == backup.sizes[entry]
                val rank = when (dates(library.dates[candidate], backup.dates[entry])) {
                    DateMatch.EXACT -> if (sameSize) 0 else 3
                    DateMatch.SHIFTED -> if (sameSize) 1 else 4
                    DateMatch.NONE -> if (sameSize) 2 else return@withPath
                }
                val distance = abs(library.dates[candidate] - backup.dates[entry])
                if (rank < bestRank || (rank == bestRank && distance < bestDistance)) {
                    best = candidate
                    bestRank = rank
                    bestDistance = distance
                }
            }
            if (best >= 0) {
                result[entry] = best
                taken[best] = true
            }
        }
        // 2. Dossier changé : même nom, même taille, même date.
        for (entry in 0 until backup.size) {
            if (result[entry] >= 0) continue
            var best = -1
            var bestRank = Int.MAX_VALUE
            library.withName(backup.nameKeys[entry]) { candidate ->
                if (taken[candidate] || library.sizes[candidate] != backup.sizes[entry]) return@withName
                val rank = when (dates(library.dates[candidate], backup.dates[entry])) {
                    DateMatch.EXACT -> 0
                    DateMatch.SHIFTED -> 1
                    DateMatch.NONE -> return@withName
                }
                if (rank < bestRank) {
                    best = candidate
                    bestRank = rank
                }
            }
            if (best >= 0) {
                result[entry] = best
                taken[best] = true
            }
        }
        return result
    }
}
