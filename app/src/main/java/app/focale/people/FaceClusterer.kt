package app.focale.people

import kotlin.math.sqrt

/** Visage à regrouper : identifiant, photo d'origine et empreinte de norme 1. */
class ClusterFace(val id: Long, val mediaId: Long, val embedding: FloatArray)

/**
 * Regroupement incrémental des visages en personnes. Logique pure, sans Android : l'étape
 * d'analyse charge l'état depuis la base, soumet chaque lot de nouveaux visages et
 * enregistre les décisions.
 *
 * - Un nouveau visage rejoint la personne dont le centroïde (moyenne normalisée des
 *   empreintes de ses visages) est le plus proche, si la similarité cosinus atteint
 *   [Config.attach]. Une personne ne reçoit qu'un visage par photo.
 * - Sinon il attend. Une personne naît autour d'un visage en attente qui a au moins
 *   [Config.minFaces] − 1 voisins (similarité ≥ [Config.seed]), une fois le groupe
 *   épuré : chaque membre doit rester proche du centroïde des autres (sans lui-même,
 *   pour ne pas favoriser l'intrus), une seule photo par visage.
 * - Les visages rattachés à la main n'entrent jamais ici comme nouveaux visages : ils
 *   nourrissent seulement les centroïdes de leur personne.
 *
 * Seuils pensés pour SFace : OpenCV retient 0,363 pour comparer deux visages ; contre un
 * centroïde, plus stable qu'un visage isolé, et sur des milliers de visages en attente,
 * on reste un peu plus strict pour éviter de mélanger deux personnes (un groupe scindé se
 * fusionne d'un geste, un groupe mélangé est pénible à trier).
 */
class FaceClusterer(private val config: Config = Config()) {

    data class Config(
        /** Similarité minimale entre un visage et le centroïde d'une personne. */
        val attach: Float = 0.42f,
        /** Similarité minimale entre deux visages en attente pour former un groupe. */
        val seed: Float = 0.45f,
        /** Nombre de visages (sur autant de photos) pour créer une personne. */
        val minFaces: Int = 3,
    )

    /**
     * Décisions d'un passage : visage → personne, et personnes créées (identifiants
     * provisoires négatifs, à remplacer par [rename] une fois enregistrées).
     */
    data class Outcome(val assignments: Map<Long, Long>, val created: Set<Long>) {
        val isEmpty: Boolean get() = assignments.isEmpty()
    }

    private class Person(var id: Long) {
        val sum = FloatArray(FaceEmbeddings.DIMENSION)
        val mediaIds = HashSet<Long>()
        private var centroid: FloatArray? = null

        fun add(face: ClusterFace) {
            for (i in sum.indices) sum[i] += face.embedding[i]
            mediaIds += face.mediaId
            centroid = null
        }

        fun similarity(embedding: FloatArray): Float {
            val current = centroid ?: FaceEmbeddings.normalize(sum).also { centroid = it }
            return FaceEmbeddings.dot(current, embedding)
        }
    }

    private val persons = LinkedHashMap<Long, Person>()
    private val pending = LinkedHashMap<Long, ClusterFace>()
    private var nextTemporaryId = -1L

    val personCount: Int get() = persons.size
    val pendingCount: Int get() = pending.size

    /** Chargement : visage déjà rattaché (automatiquement ou à la main) à [personId]. */
    fun addAssigned(personId: Long, face: ClusterFace) {
        persons.getOrPut(personId) { Person(personId) }.add(face)
    }

    /** Chargement : visage sans personne, candidat au regroupement. */
    fun addPending(face: ClusterFace) {
        pending[face.id] = face
    }

    /** Donne son identifiant définitif à une personne créée par [cluster] ou [sweep]. */
    fun rename(temporaryId: Long, id: Long) {
        val person = persons.remove(temporaryId) ?: return
        person.id = id
        persons[id] = person
    }

    /** Oublie une personne créée mais finalement non enregistrée. */
    fun forget(personId: Long) {
        persons.remove(personId)
    }

    /**
     * Regroupe de nouveaux visages : rattachement aux personnes connues, puis création de
     * personnes autour de ceux qui restent en attente.
     */
    fun cluster(faces: List<ClusterFace>): Outcome {
        val assignments = LinkedHashMap<Long, Long>()
        val created = LinkedHashSet<Long>()
        faces.forEach { pending.remove(it.id) }
        val waiting = ArrayList<ClusterFace>()
        for (group in faces.groupBy { it.mediaId }.values) {
            for ((face, person) in bestMatches(group, persons.values)) {
                person.add(face)
                assignments[face.id] = person.id
            }
            group.filter { it.id !in assignments }.forEach {
                pending[it.id] = it
                waiting += it
            }
        }
        for (face in waiting) {
            if (face.id !in pending) continue
            val seeds = sequenceOf(face) + neighbors(face).asSequence()
            val group = seeds.firstNotNullOfOrNull { seed -> if (seed.id in pending) groupAround(seed) else null } ?: continue
            val person = Person(nextTemporaryId--)
            persons[person.id] = person
            created += person.id
            for (member in group) {
                person.add(member)
                pending.remove(member.id)
                assignments[member.id] = person.id
            }
            absorb(person).forEach { assignments[it.id] = person.id }
        }
        return Outcome(assignments, created)
    }

    /**
     * Repasse les visages en attente devant les personnes connues : les centroïdes ont pu
     * bouger depuis leur arrivée (nouveaux visages, fusions, rattachements à la main).
     */
    fun sweep(): Outcome {
        val assignments = LinkedHashMap<Long, Long>()
        for (group in pending.values.groupBy { it.mediaId }.values) {
            for ((face, person) in bestMatches(group, persons.values)) {
                person.add(face)
                pending.remove(face.id)
                assignments[face.id] = person.id
            }
        }
        return Outcome(assignments, emptySet())
    }

    /**
     * Rattachements des visages d'une même photo, les plus sûrs d'abord : un visage va à
     * une seule personne et une personne ne reçoit qu'un visage de la photo.
     */
    private fun bestMatches(group: List<ClusterFace>, candidates: Collection<Person>): List<Pair<ClusterFace, Person>> {
        val options = ArrayList<Triple<ClusterFace, Person, Float>>()
        for (face in group) {
            for (person in candidates) {
                if (face.mediaId in person.mediaIds) continue
                val similarity = person.similarity(face.embedding)
                if (similarity >= config.attach) options += Triple(face, person, similarity)
            }
        }
        options.sortByDescending { it.third }
        val usedFaces = HashSet<Long>()
        val usedPersons = HashSet<Person>()
        val matches = ArrayList<Pair<ClusterFace, Person>>()
        for ((face, person) in options) {
            if (face.id in usedFaces || person in usedPersons) continue
            usedFaces += face.id
            usedPersons += person
            matches += face to person
        }
        return matches
    }

    private fun neighbors(face: ClusterFace): List<ClusterFace> =
        pending.values.filter { it.id != face.id && FaceEmbeddings.dot(it.embedding, face.embedding) >= config.seed }

    /** Groupe cohérent autour de [seed], ou null s'il n'atteint pas [Config.minFaces]. */
    private fun groupAround(seed: ClusterFace): List<ClusterFace>? {
        var members = listOf(seed) + neighbors(seed)
        if (members.size < config.minFaces) return null
        repeat(REFINE_PASSES) {
            val sum = FloatArray(FaceEmbeddings.DIMENSION)
            for (member in members) for (i in sum.indices) sum[i] += member.embedding[i]
            members = members
                .map { it to leaveOneOut(sum, it.embedding) }
                .filter { (_, similarity) -> similarity >= config.attach }
                .groupBy { (face, _) -> face.mediaId }
                .values
                .map { sameMedia -> sameMedia.maxBy { it.second }.first }
            if (members.size < config.minFaces) return null
        }
        return members
    }

    /** Similarité de [embedding] au centroïde du groupe privé de lui-même. */
    private fun leaveOneOut(sum: FloatArray, embedding: FloatArray): Float {
        val withSelf = FaceEmbeddings.dot(sum, embedding)
        val othersSquared = FaceEmbeddings.dot(sum, sum) - 2 * withSelf + FaceEmbeddings.dot(embedding, embedding)
        if (othersSquared <= 1e-6f) return 0f
        return (withSelf - FaceEmbeddings.dot(embedding, embedding)) / sqrt(othersSquared)
    }

    /** Visages en attente proches d'une personne qui vient de naître. */
    private fun absorb(person: Person): List<ClusterFace> {
        val joined = pending.values
            .filter { it.mediaId !in person.mediaIds }
            .map { it to person.similarity(it.embedding) }
            .filter { (_, similarity) -> similarity >= config.attach }
            .groupBy { (face, _) -> face.mediaId }
            .values
            .map { sameMedia -> sameMedia.maxBy { it.second }.first }
        for (face in joined) {
            person.add(face)
            pending.remove(face.id)
        }
        return joined
    }

    private companion object {
        const val REFINE_PASSES = 2
    }
}
