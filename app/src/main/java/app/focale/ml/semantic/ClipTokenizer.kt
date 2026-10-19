package app.focale.ml.semantic

import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Tokeniseur BPE de CLIP : portage de `clip/simple_tokenizer.py` (OpenAI, licence MIT) sur
 * le vocabulaire `bpe_simple_vocab_16e6` (fusions par octets, marque de fin de mot `</w>`).
 *
 * Différence avec l'original : ni réparation ftfy ni décodage des entités HTML,
 * l'application ne lui transmet que du texte déjà normalisé. Le tokeniseur de référence
 * est `tools/clip/clip_model.py`, dont les sorties servent aux tests.
 *
 * @param merges paires fusionnées (« a b »), par rang croissant ; un vocabulaire réduit
 *   garde les premières (les plus fréquentes), les jetons de début et de fin suivant alors
 *   directement les fusions conservées.
 */
class ClipTokenizer(merges: List<String>) {

    private val ranks = HashMap<String, Int>(merges.size * 2)
    private val encoder = HashMap<String, Int>((BYTE_COUNT * 2 + merges.size + 2) * 2)
    private val cache = object : LinkedHashMap<String, List<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > CACHE_SIZE
    }

    /** Jeton de début de texte. */
    val startToken: Int

    /** Jeton de fin de texte : le plus grand identifiant, que l'encodeur repère par argmax. */
    val endToken: Int

    val vocabularySize: Int

    init {
        // Vocabulaire : octets, octets en fin de mot, fusions, puis les deux jetons spéciaux.
        BYTE_ORDER.forEachIndexed { i, b -> encoder[BYTE_CHARS[b].toString()] = i }
        BYTE_ORDER.forEachIndexed { i, b -> encoder["${BYTE_CHARS[b]}$END_OF_WORD"] = BYTE_COUNT + i }
        merges.forEachIndexed { rank, merge ->
            ranks[merge] = rank
            encoder[merge.replace(" ", "")] = BYTE_COUNT * 2 + rank
        }
        startToken = BYTE_COUNT * 2 + merges.size
        endToken = startToken + 1
        vocabularySize = endToken + 1
    }

    /** Identifiants des jetons de [text], sans jetons de début ni de fin. */
    fun encode(text: String): IntArray {
        val clean = WHITESPACE.replace(text.trim(), " ").trim().lowercase()
        val ids = ArrayList<Int>()
        for (match in PIECES.findAll(clean)) {
            when (match.value) {
                // Jetons spéciaux écrits dans le texte : gardés entiers, comme dans l'original.
                START_TEXT -> ids += startToken
                END_TEXT -> ids += endToken
                else -> {
                    val bytes = match.value.toByteArray(Charsets.UTF_8)
                    val piece = buildString(bytes.size) { bytes.forEach { append(BYTE_CHARS[it.toInt() and 0xFF]) } }
                    for (token in bpe(piece)) ids += encoder.getValue(token)
                }
            }
        }
        return ids.toIntArray()
    }

    /**
     * Entrée de l'encodeur de texte : début, jetons de [text] (tronqués), fin, puis des
     * zéros jusqu'à [CONTEXT_LENGTH].
     */
    fun tokenize(text: String): LongArray {
        val tokens = encode(text)
        val result = LongArray(CONTEXT_LENGTH)
        result[0] = startToken.toLong()
        val count = minOf(tokens.size, CONTEXT_LENGTH - 2)
        for (i in 0 until count) result[i + 1] = tokens[i].toLong()
        result[count + 1] = endToken.toLong()
        return result
    }

    /** Fusions successives de la paire de plus petit rang, comme l'original. */
    private fun bpe(piece: String): List<String> = synchronized(cache) {
        cache[piece]?.let { return it }
        var word: List<String> = piece.mapIndexed { i, c -> if (i == piece.lastIndex) "$c$END_OF_WORD" else c.toString() }
        while (word.size > 1) {
            var bestRank = Int.MAX_VALUE
            var best = -1
            for (i in 0 until word.size - 1) {
                val rank = ranks["${word[i]} ${word[i + 1]}"] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    best = i
                }
            }
            if (best < 0) break
            val first = word[best]
            val second = word[best + 1]
            val merged = ArrayList<String>(word.size - 1)
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    merged += first + second
                    i += 2
                } else {
                    merged += word[i]
                    i++
                }
            }
            word = merged
        }
        cache[piece] = word
        word
    }

    companion object {
        /** Longueur de séquence de l'encodeur de texte. */
        const val CONTEXT_LENGTH = 77

        /** Nombre de fusions du vocabulaire complet de CLIP (49 408 jetons). */
        const val FULL_MERGES = 49152 - 256 - 2

        private const val BYTE_COUNT = 256
        private const val END_OF_WORD = "</w>"
        private const val START_TEXT = "<|startoftext|>"
        private const val END_TEXT = "<|endoftext|>"
        private const val CACHE_SIZE = 512

        /**
         * Espaces au sens de Python (`\s` du module regex) : séparateurs Unicode et
         * caractères de contrôle d'espacement, sans dépendre des options de `Pattern`
         * (qui diffèrent entre la JVM et Android).
         */
        private const val SPACE = "\\p{Z}\\t\\n\\x0B\\f\\r\\x1C-\\x1F\\x85"
        private val WHITESPACE = Regex("[$SPACE]+")

        /** Découpage de l'original : contractions anglaises, lettres, chiffre par chiffre, ponctuation. */
        private val PIECES = Regex(
            "<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^$SPACE\\p{L}\\p{N}]+",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Correspondance réversible octet → caractère de `bytes_to_unicode()` : octets
         * imprimables inchangés, les autres décalés au-delà de 255. L'ordre est celui du
         * vocabulaire.
         */
        private val BYTE_ORDER: IntArray
        private val BYTE_CHARS: CharArray

        init {
            val printable = ('!'.code..'~'.code) + ('¡'.code..'¬'.code) + ('®'.code..'ÿ'.code)
            val bytes = printable.toMutableList()
            val chars = printable.toMutableList()
            var shift = 0
            for (b in 0 until BYTE_COUNT) {
                if (b !in printable) {
                    bytes += b
                    chars += BYTE_COUNT + shift++
                }
            }
            BYTE_ORDER = bytes.toIntArray()
            // Indexé par octet pour l'encodage ; l'ordre du vocabulaire suit BYTE_ORDER.
            BYTE_CHARS = CharArray(BYTE_COUNT)
            bytes.forEachIndexed { i, b -> BYTE_CHARS[b] = chars[i].toChar() }
        }

        /** Lit les [maxMerges] premières fusions d'un vocabulaire `bpe_simple_vocab_16e6.txt.gz`. */
        fun load(input: InputStream, maxMerges: Int = FULL_MERGES): ClipTokenizer {
            val merges = ArrayList<String>(maxMerges)
            GZIPInputStream(input).bufferedReader(Charsets.UTF_8).useLines { lines ->
                // Première ligne : en-tête de version.
                for (line in lines.drop(1)) {
                    if (merges.size >= maxMerges) break
                    if (line.isNotEmpty()) merges += line
                }
            }
            return ClipTokenizer(merges)
        }
    }
}
