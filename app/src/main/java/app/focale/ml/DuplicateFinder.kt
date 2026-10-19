package app.focale.ml

/**
 * Regroupement des photos quasi identiques à partir de leurs dHash 64 bits.
 *
 * Évite la comparaison de toutes les paires (O(n²), 1,25 milliard pour 50 000 photos) par
 * hachage multi-index : l'empreinte est découpée en 8 octets ; deux empreintes à une
 * distance de Hamming ≤ 7 ont forcément au moins un octet identique (principe des
 * tiroirs). Seules les paires partageant un octet sont comparées, puis regroupées par
 * union-find.
 */
object DuplicateFinder {

    private const val BANDS = 8
    /** Au-delà, un seau correspond à des images peu informatives (aplats) : ignoré. */
    private const val MAX_BUCKET = 400

    /**
     * @param hashes paires (identifiant, empreinte).
     * @return groupes d'identifiants (au moins 2 éléments), les plus grands d'abord.
     */
    fun group(hashes: List<Pair<Long, Long>>, maxDistance: Int = 6): List<List<Long>> {
        require(maxDistance < BANDS) { "La distance maximale doit rester inférieure à $BANDS" }
        // Les empreintes quasi uniformes (images unies) donneraient de faux doublons.
        val usable = hashes.filter { java.lang.Long.bitCount(it.second) in 6..58 }
        val parent = IntArray(usable.size) { it }

        fun find(i: Int): Int {
            var root = i
            while (parent[root] != root) root = parent[root]
            var node = i
            while (parent[node] != root) {
                val next = parent[node]
                parent[node] = root
                node = next
            }
            return root
        }

        for (band in 0 until BANDS) {
            val buckets = HashMap<Int, MutableList<Int>>()
            usable.forEachIndexed { index, (_, hash) ->
                val key = ((hash ushr (band * 8)) and 0xFF).toInt()
                buckets.getOrPut(key) { ArrayList() }.add(index)
            }
            for (bucket in buckets.values) {
                if (bucket.size < 2 || bucket.size > MAX_BUCKET) continue
                for (a in 0 until bucket.size - 1) {
                    val i = bucket[a]
                    for (b in a + 1 until bucket.size) {
                        val j = bucket[b]
                        if (ImageMetrics.hammingDistance(usable[i].second, usable[j].second) <= maxDistance) {
                            val ri = find(i)
                            val rj = find(j)
                            if (ri != rj) parent[ri] = rj
                        }
                    }
                }
            }
        }
        return usable.indices
            .groupBy(::find)
            .values
            .filter { it.size >= 2 }
            .map { members -> members.map { usable[it].first } }
            .sortedByDescending { it.size }
    }
}
