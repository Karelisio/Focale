package app.focale.people

import java.util.Random
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeSuggesterTest {

    /**
     * Empreintes synthétiques de [FaceClustererTest] : composante commune, identité et bruit
     * propre à chaque photo (deux photos d'une même personne ≈ 0,6, deux inconnus ≈ 0,15).
     * Un « style » imite ce qui scinde une personne en deux groupes (lunettes, âge, lumière).
     */
    private class Faces(seed: Long) {
        private val random = Random(seed)
        private val common = gaussian()

        fun gaussian(): FloatArray = FaceEmbeddings.normalize(FloatArray(FaceEmbeddings.DIMENSION) { random.nextGaussian().toFloat() })

        /** Centroïde de [count] visages de [identity] pris avec le même [style]. */
        fun centroid(identity: FloatArray, count: Int, style: FloatArray? = null): FloatArray {
            val sum = FloatArray(FaceEmbeddings.DIMENSION)
            repeat(count) {
                val noise = gaussian()
                val face = FaceEmbeddings.normalize(
                    FloatArray(FaceEmbeddings.DIMENSION) { 0.45f * common[it] + identity[it] + 0.6f * (style?.get(it) ?: 0f) + 0.85f * noise[it] },
                )
                for (i in sum.indices) sum[i] += face[i]
            }
            return FaceEmbeddings.normalize(sum)
        }
    }

    /** Centroïde exact : [along] sur le premier axe, le reste sur l'axe [axis] (similarité connue). */
    private fun around(along: Float, axis: Int): FloatArray = FloatArray(FaceEmbeddings.DIMENSION).also {
        it[0] = along
        if (axis != 0) it[axis] = sqrt(1 - along * along)
    }

    private fun candidate(id: Long, centroid: FloatArray, name: String? = null, photos: Int = 10) =
        MergeSuggester.Candidate(id, name, photos, centroid)

    @Test
    fun `une personne scindée par le regroupement est proposée, deux personnes distinctes non`() {
        val faces = Faces(1)
        val alice = faces.gaussian()
        val bob = faces.gaussian()
        val aliceWithGlasses = candidate(1, faces.centroid(alice, count = 12, style = faces.gaussian()))
        val aliceOutdoors = candidate(2, faces.centroid(alice, count = 6, style = faces.gaussian()))
        val bobAlone = candidate(3, faces.centroid(bob, count = 9))

        val suggestion = MergeSuggester.suggest(listOf(aliceWithGlasses, aliceOutdoors, bobAlone), refused = emptySet()).single()

        assertEquals(setOf(1L, 2L), setOf(suggestion.targetId, suggestion.sourceId))
        assertTrue("similarité : ${suggestion.similarity}", suggestion.similarity >= MergeSuggester.THRESHOLD)
        // Deux personnes distinctes restent loin du seuil.
        val strangers = FaceEmbeddings.dot(aliceWithGlasses.centroid, bobAlone.centroid)
        assertTrue("inconnus : $strangers", strangers < MergeSuggester.THRESHOLD - 0.1f)
    }

    @Test
    fun `les paires les plus probables d'abord, aucune sous le seuil`() {
        val suggestions = MergeSuggester.suggest(
            listOf(
                candidate(1, around(1f, 0), photos = 30),
                // Avec 1 : 0,45 ; avec 3 : 0,45 × 0,7 = 0,315.
                candidate(2, around(0.45f, 2), photos = 20),
                // Avec 1 : 0,7.
                candidate(3, around(0.7f, 3), photos = 10),
                // Avec 1 : 0,3, sous le seuil.
                candidate(4, around(0.3f, 4), photos = 5),
            ),
            refused = emptySet(),
        )
        assertEquals(listOf(1L to 3L, 1L to 2L), suggestions.map { it.targetId to it.sourceId })
        assertEquals(0.7f, suggestions.first().similarity, 1e-5f)
    }

    @Test
    fun `une paire refusée n'est plus proposée`() {
        val people = listOf(candidate(5, around(1f, 0)), candidate(2, around(0.8f, 1)))
        assertEquals(1, MergeSuggester.suggest(people, refused = emptySet()).size)
        // Paire ordonnée : plus petit identifiant en premier.
        assertTrue(MergeSuggester.suggest(people, refused = setOf(2L to 5L)).isEmpty())
    }

    @Test
    fun `la cible est la personne nommée, sinon celle qui a le plus de photos`() {
        val base = around(1f, 0)
        val close = around(0.8f, 1)

        val named = MergeSuggester.suggest(listOf(candidate(1, base, photos = 40), candidate(2, close, name = "Léa", photos = 3)), emptySet())
        assertEquals(2L to 1L, named.single().let { it.targetId to it.sourceId })

        val unnamed = MergeSuggester.suggest(listOf(candidate(1, base, photos = 4), candidate(2, close, photos = 12)), emptySet())
        assertEquals(2L to 1L, unnamed.single().let { it.targetId to it.sourceId })
    }

    @Test
    fun `deux personnes nommées ne sont proposées que sous le même nom`() {
        val base = around(1f, 0)
        val close = around(0.8f, 1)
        val siblings = listOf(candidate(1, base, name = "Léa"), candidate(2, close, name = "Tom"))
        assertTrue(MergeSuggester.suggest(siblings, emptySet()).isEmpty())
        // Même nom à la casse et aux accents près : sans doute un doublon.
        val twice = listOf(candidate(1, base, name = "Léa", photos = 3), candidate(2, close, name = "lea", photos = 8))
        assertEquals(2L to 1L, MergeSuggester.suggest(twice, emptySet()).single().let { it.targetId to it.sourceId })
    }
}
