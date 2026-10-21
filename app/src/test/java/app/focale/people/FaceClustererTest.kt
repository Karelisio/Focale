package app.focale.people

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceClustererTest {

    /**
     * Empreintes synthétiques proches de SFace : une composante commune à tous les visages
     * (deux inconnus restent un peu semblables, ≈ 0,15), une par identité et du bruit
     * (deux photos d'une même personne ≈ 0,6).
     */
    private class Faces(seed: Long) {
        val random = Random(seed)
        private val common = gaussian()
        private var nextId = 1L

        fun gaussian(): FloatArray = FaceEmbeddings.normalize(FloatArray(FaceEmbeddings.DIMENSION) { random.nextGaussian().toFloat() })

        fun identity(): FloatArray = gaussian()

        /** Visage de [identity] ; [noise] : pose, lumière, expression propres à la photo. */
        fun face(
            identity: FloatArray,
            mediaId: Long = nextId,
            noise: FloatArray = gaussian(),
            noiseWeight: Float = 0.85f,
        ): ClusterFace {
            val vector = FloatArray(FaceEmbeddings.DIMENSION) { 0.45f * common[it] + identity[it] + noiseWeight * noise[it] }
            return ClusterFace(nextId++, mediaId, FaceEmbeddings.normalize(vector))
        }

        /** Visage d'un inconnu (identité qui ne revient pas). */
        fun stranger(): ClusterFace = face(identity())
    }

    @Test
    fun `les empreintes synthétiques ressemblent à celles de SFace`() {
        val faces = Faces(1)
        val alice = faces.identity()
        val same = List(20) { faces.face(alice) }.zipWithNext { a, b -> FaceEmbeddings.dot(a.embedding, b.embedding) }
        val different = List(20) { faces.stranger() }.zipWithNext { a, b -> FaceEmbeddings.dot(a.embedding, b.embedding) }
        assertTrue("mêmes : ${same.average()}", same.average() in 0.5..0.75)
        assertTrue("différents : ${different.average()}", different.average() in 0.05..0.3)
    }

    @Test
    fun `deux personnes se forment au fil des lots sans se mélanger`() {
        val faces = Faces(2)
        val alice = faces.identity()
        val bob = faces.identity()
        val truth = HashMap<Long, String>()
        val clusterer = FaceClusterer()
        val assigned = HashMap<Long, Long>()
        repeat(10) {
            // Chaque lot : une photo de chacun et un inconnu.
            val batch = listOf(faces.face(alice), faces.face(bob), faces.stranger())
            truth[batch[0].id] = "alice"
            truth[batch[1].id] = "bob"
            assigned += clusterer.cluster(batch).assignments
        }
        assertEquals(2, clusterer.personCount)
        val byPerson = assigned.entries.groupBy({ it.value }, { truth[it.key] })
        assertEquals(2, byPerson.size)
        byPerson.values.forEach { members ->
            assertEquals("personne mélangée : $members", 1, members.toSet().size)
            assertFalse("inconnu rattaché", null in members)
        }
        // Presque toutes les photos d'Alice et de Bob retrouvent leur personne.
        assertTrue("rattachés : ${assigned.size}", assigned.size >= 18)
    }

    @Test
    fun `des inconnus ne créent aucune personne`() {
        val faces = Faces(3)
        val clusterer = FaceClusterer()
        repeat(40) {
            val outcome = clusterer.cluster(List(10) { faces.stranger() })
            assertTrue(outcome.created.isEmpty())
        }
        assertEquals(0, clusterer.personCount)
        assertEquals(400, clusterer.pendingCount)
    }

    @Test
    fun `une personne ne naît qu'à partir de trois photos distinctes`() {
        val faces = Faces(4)
        val carol = faces.identity()
        val clusterer = FaceClusterer()
        // Deux photos seulement, dont une avec deux visages très proches (reflet, montage).
        val first = clusterer.cluster(listOf(faces.face(carol, mediaId = 100), faces.face(carol, mediaId = 100)))
        val second = clusterer.cluster(listOf(faces.face(carol, mediaId = 101)))
        assertTrue(first.isEmpty && second.isEmpty)
        // La troisième photo suffit.
        val third = clusterer.cluster(listOf(faces.face(carol, mediaId = 102)))
        assertEquals(1, third.created.size)
        assertEquals(3, third.assignments.size)
    }

    @Test
    fun `les rattachements manuels nourrissent la personne sans jamais être déplacés`() {
        val faces = Faces(5)
        val dan = faces.identity()
        val eve = faces.identity()
        val clusterer = FaceClusterer()
        // Personne 7 : deux visages de Dan rattachés à la main, plus un d'Eve choisi par
        // l'utilisateur (même s'il ressemble davantage à quelqu'un d'autre).
        val manual = listOf(faces.face(dan), faces.face(dan))
        manual.forEach { clusterer.addAssigned(7L, it) }
        val evesFace = faces.face(eve)
        clusterer.addAssigned(7L, evesFace)
        // Les nouveaux visages de Dan rejoignent la personne 7.
        val outcome = clusterer.cluster(List(4) { faces.face(dan) })
        assertEquals(setOf(7L), outcome.assignments.values.toSet())
        assertEquals(4, outcome.assignments.size)
        // Aucun passage ne propose de déplacer un visage déjà rattaché.
        val everything = outcome.assignments.keys + clusterer.sweep().assignments.keys
        assertTrue(manual.none { it.id in everything })
        assertFalse(evesFace.id in everything)
    }

    @Test
    fun `une personne ne reçoit qu'un visage par photo`() {
        val faces = Faces(6)
        val frank = faces.identity()
        val clusterer = FaceClusterer()
        List(3) { faces.face(frank) }.forEach { clusterer.addAssigned(1L, it) }
        // Deux visages très semblables sur la même photo : un seul est rattaché.
        val outcome = clusterer.cluster(listOf(faces.face(frank, mediaId = 500), faces.face(frank, mediaId = 500)))
        assertEquals(1, outcome.assignments.size)
        assertEquals(1, clusterer.pendingCount)
    }

    @Test
    fun `un intrus voisin du premier visage est écarté du nouveau groupe`() {
        val faces = Faces(7)
        val grace = faces.identity()
        val shared = faces.gaussian()
        val seed = faces.face(grace, noise = shared)
        val others = listOf(faces.face(grace), faces.face(grace))
        // Intrus : autre identité, mais mêmes conditions de prise de vue que le premier
        // visage, donc proche de lui seul.
        val intruder = faces.face(faces.identity(), noise = shared, noiseWeight = 1.2f)
        val clusterer = FaceClusterer()
        clusterer.addPending(intruder)
        others.forEach { clusterer.addPending(it) }
        assertTrue(FaceEmbeddings.dot(seed.embedding, intruder.embedding) >= FaceClusterer.Config().seed)
        val outcome = clusterer.cluster(listOf(seed))
        assertEquals(1, outcome.created.size)
        assertEquals(setOf(seed.id) + others.map { it.id }, outcome.assignments.keys)
    }

    @Test
    fun `les identifiants provisoires sont remplacés par les définitifs`() {
        val faces = Faces(8)
        val heidi = faces.identity()
        val clusterer = FaceClusterer()
        val outcome = clusterer.cluster(List(3) { faces.face(heidi) })
        val temporary = outcome.created.single()
        assertTrue(temporary < 0)
        clusterer.rename(temporary, 42L)
        val next = clusterer.cluster(listOf(faces.face(heidi)))
        assertEquals(setOf(42L), next.assignments.values.toSet())
    }

    @Test
    fun `le balayage rattache les visages en attente à une personne apparue depuis`() {
        val faces = Faces(9)
        val ivan = faces.identity()
        val clusterer = FaceClusterer()
        val waiting = faces.face(ivan)
        clusterer.addPending(waiting)
        List(5) { faces.face(ivan) }.forEach { clusterer.addAssigned(3L, it) }
        assertEquals(mapOf(waiting.id to 3L), clusterer.sweep().assignments)
        assertEquals(0, clusterer.pendingCount)
    }

    @Test
    fun `reste rapide avec des milliers de visages en attente`() {
        val faces = Faces(10)
        val clusterer = FaceClusterer()
        repeat(3_000) { clusterer.addPending(faces.stranger()) }
        val people = List(30) { faces.identity() }
        val start = System.nanoTime()
        repeat(50) {
            clusterer.cluster(List(16) { index -> if (index % 2 == 0) faces.face(people[faces.random.nextInt(people.size)]) else faces.stranger() })
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("trop lent : $elapsedMs ms", elapsedMs < 10_000)
        assertTrue(clusterer.personCount in 20..40)
    }
}
