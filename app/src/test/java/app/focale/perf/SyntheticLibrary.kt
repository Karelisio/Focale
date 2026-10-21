package app.focale.perf

import app.focale.core.database.FaceEntity
import app.focale.core.database.FaceScanEntity
import app.focale.core.database.FavoriteEntity
import app.focale.core.database.MediaAnalysisEntity
import app.focale.core.database.MediaEntity
import app.focale.core.database.MediaLabelEntity
import app.focale.core.database.MediaQueryBuilder
import app.focale.core.database.MediaTextEntity
import app.focale.core.database.PersonEntity
import app.focale.core.mediastore.MediaStoreSource
import app.focale.ml.ImageAnalyzer
import app.focale.ml.LabelDictionary
import app.focale.ml.OfflineGeocoder
import app.focale.ml.Place
import app.focale.ml.PlaceIndex
import app.focale.ml.PlaceTranslations
import app.focale.ml.text.RecognizedText
import app.focale.ml.text.TextRecognitionStep
import app.focale.people.FaceAnalysisStep
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Bibliothèque de synthèse des tests de charge, déterministe (graine fixe) et proche d'un
 * téléphone réel : dix ans de prises de vue, de plus en plus nombreuses et groupées en sorties
 * (plusieurs dizaines de photos le même jour), ~300 dossiers dont l'appareil photo, les captures
 * et WhatsApp en tête, ~5 % de vidéos, ~8 % de captures d'écran, ~30 % de médias géolocalisés,
 * ~3 étiquettes par média, du texte reconnu (captures, documents, panneaux) et des visages
 * rattachés à des personnes. Quelques médias sont dans la corbeille système.
 *
 * Les identifiants suivent l'ordre d'arrivée dans MediaStore (`DATE_ADDED`), avec des trous :
 * les photos des anciens téléphones sont arrivées d'un bloc au dernier changement d'appareil.
 * Les empreintes TinyCLIP (2 Ko par photo) ne sont pas générées.
 */
class SyntheticLibrary(val size: Int = DEFAULT_SIZE, seed: Long = SEED) {

    /** Index des médias, tel que la synchronisation l'écrit (date de tri comprise). */
    val media: List<MediaEntity>

    /** Médias sans date de prise de vue dans MediaStore (captures, WhatsApp, téléchargements). */
    val withoutDateTaken: Set<Long>

    /** Résultat de l'analyse principale de chaque média hors corbeille. */
    val analyses: List<MediaAnalysisEntity>
    val labels: List<MediaLabelEntity>

    /** Texte lu dans chaque photo hors corbeille (vide pour la plupart). */
    val texts: List<MediaTextEntity>
    val faceScans: List<FaceScanEntity>
    val faces: List<FaceEntity>
    val persons: List<PersonEntity>
    val favorites: List<FavoriteEntity>

    /** Dossiers distincts (`BUCKET_ID`). */
    val folderCount: Int

    init {
        val random = Random(seed)
        val drafts = draftMedia(random)
        // Identifiants MediaStore : ordre d'arrivée, avec des trous (fichiers non médias, suppressions).
        drafts.sortWith(compareBy<Draft> { it.addedSeconds }.thenBy { it.order })
        var id = FIRST_ID
        val media = ArrayList<MediaEntity>(size)
        val noDate = HashSet<Long>()
        drafts.forEachIndexed { index, draft ->
            id += 1 + if (random.nextDouble() < 0.15) random.nextInt(1, 6) else 0
            val entity = draft.toEntity(id, generation = index + 1L)
            media += entity
            if (!draft.hasDateTaken) noDate += id
        }
        this.media = media
        withoutDateTaken = noDate
        folderCount = media.mapTo(HashSet()) { it.bucketId }.size

        val analyzed = media.filter { !it.isTrashed }
        val analyses = ArrayList<MediaAnalysisEntity>(analyzed.size)
        val labels = ArrayList<MediaLabelEntity>(analyzed.size * 3)
        val texts = ArrayList<MediaTextEntity>(analyzed.size)
        val scans = ArrayList<FaceScanEntity>(analyzed.size)
        val faces = ArrayList<FaceEntity>(analyzed.size / 3)
        val kindOf = HashMap<Long, Kind>(media.size)
        drafts.forEachIndexed { index, draft -> kindOf[media[index].id] = draft.kind }
        for (entity in analyzed) {
            val kind = kindOf.getValue(entity.id)
            analyses += analysis(random, entity, kind)
            pickLabels(random, kind).forEach { (label, confidence) -> labels += MediaLabelEntity(entity.id, label, confidence) }
            if (entity.mediaType == MediaQueryBuilder.MEDIA_TYPE_IMAGE) {
                val text = text(random, kind)
                texts += MediaTextEntity(entity.id, entity.dateModified, TextRecognitionStep.VERSION, text, RecognizedText.searchKey(text))
                if (!entity.isScreenshot && !entity.isGif) {
                    val count = faceCount(random, kind)
                    scans += FaceScanEntity(entity.id, entity.dateModified, FaceAnalysisStep.VERSION, count)
                    repeat(count) { faces += face(random, faces.size + 1L, entity.id) }
                }
            }
        }
        this.analyses = analyses
        this.labels = labels
        this.texts = texts
        faceScans = scans
        this.faces = faces
        persons = (1..PERSONS).map { personId ->
            PersonEntity(id = personId.toLong(), name = PEOPLE_NAMES.getOrNull(personId - 1), createdAt = END_MS)
        }
        favorites = analyzed.filter { random.nextDouble() < 0.02 }.map { FavoriteEntity(it.id, it.dateAdded) }
    }

    /** Médias hors corbeille, dans l'ordre de la timeline (date de prise de vue, puis identifiant, décroissants). */
    val timelineOrder: List<MediaEntity> by lazy {
        media.filter { !it.isTrashed }.sortedWith(compareByDescending<MediaEntity> { it.dateTaken }.thenByDescending { it.id })
    }

    // ── Médias ────────────────────────────────────────────────────────────────────────

    private enum class Kind { CAMERA_PHOTO, CAMERA_VIDEO, SCREENSHOT, WHATSAPP_IMAGE, WHATSAPP_VIDEO, DOWNLOAD, ALBUM }

    /** Média avant attribution de son identifiant. */
    private class Draft(
        val order: Int,
        val kind: Kind,
        val takenMs: Long,
        val hasDateTaken: Boolean,
        val addedSeconds: Long,
        val modifiedSeconds: Long,
        val folder: String,
        val mime: String,
        val name: String,
        val width: Int,
        val height: Int,
        val orientation: Int,
        val bytes: Long,
        val durationMs: Long,
        val trashed: Boolean,
    ) {
        fun toEntity(id: Long, generation: Long): MediaEntity {
            val video = mime.startsWith("video/")
            val folderName = folder.trimEnd('/').substringAfterLast('/')
            return MediaEntity(
                id = id,
                mediaType = if (video) MediaQueryBuilder.MEDIA_TYPE_VIDEO else MediaQueryBuilder.MEDIA_TYPE_IMAGE,
                mimeType = mime,
                displayName = name,
                relativePath = folder,
                bucketId = bucketId(folder),
                bucketName = folderName,
                // Même règle que MediaStoreSource : sans date de prise de vue, la date de modification.
                dateTaken = if (hasDateTaken) takenMs else modifiedSeconds * 1000,
                dateAdded = addedSeconds * 1000,
                dateModified = modifiedSeconds * 1000,
                generation = generation,
                size = bytes,
                width = width,
                height = height,
                orientation = orientation,
                duration = durationMs,
                volumeName = "external_primary",
                isScreenshot = MediaStoreSource.isScreenshot(folder, name),
                isGif = mime == "image/gif",
                isTrashed = trashed,
                dateExpires = if (trashed) (END_MS / 1000 + 20 * DAY_SECONDS) * 1000 else 0,
            )
        }
    }

    private fun draftMedia(random: Random): ArrayList<Draft> {
        // Sorties : instants de plus en plus fréquents au fil des ans, d'importance très inégale.
        val events = LongArray(size / 6 + 1) { growingInstant(random) }
        val weights = DoubleArray(events.size) { (1.0 / (1.0 - random.nextDouble()).pow(1 / 1.4)).coerceAtMost(60.0) }
        val cumulative = DoubleArray(weights.size).also { sums ->
            var total = 0.0
            weights.forEachIndexed { i, w -> total += w; sums[i] = total }
        }
        val albums = (1..ALBUM_FOLDERS).map { "Pictures/Album ${it.toString().padStart(3, '0')}/" }
        var albumMedia = 0
        val drafts = ArrayList<Draft>(size)
        repeat(size) { order ->
            val kind = pickKind(random)
            val taken = when (kind) {
                Kind.CAMERA_PHOTO, Kind.CAMERA_VIDEO, Kind.WHATSAPP_IMAGE, Kind.WHATSAPP_VIDEO, Kind.ALBUM -> {
                    val target = random.nextDouble() * cumulative.last()
                    val event = cumulative.binarySearch(target).let { if (it < 0) -it - 1 else it }.coerceAtMost(events.lastIndex)
                    events[event] + random.nextLong(4 * HOUR_MS)
                }
                Kind.SCREENSHOT, Kind.DOWNLOAD -> growingInstant(random)
            }.coerceAtMost(END_MS - 60_000)
            // Les photos des anciens téléphones sont arrivées d'un bloc au dernier changement d'appareil.
            val takenSeconds = taken / 1000
            val added = if (taken < MIGRATION_MS) MIGRATION_MS / 1000 + takenSeconds % 7200 else takenSeconds + random.nextLong(1, 60)
            val edited = random.nextDouble() < 0.02
            val modified = if (edited) (takenSeconds + random.nextLong(DAY_SECONDS, 300 * DAY_SECONDS)).coerceAtMost(END_MS / 1000) else takenSeconds
            val stamp = FILE_STAMP.format(Instant.ofEpochMilli(taken))
            val draft = when (kind) {
                Kind.CAMERA_PHOTO -> {
                    val portrait = random.nextDouble() < 0.3
                    Draft(
                        order, kind, taken, true, added, modified, "DCIM/Camera/", "image/jpeg", "IMG_$stamp.jpg",
                        if (portrait) 3000 else 4000, if (portrait) 4000 else 3000, if (random.nextDouble() < 0.1) 90 else 0,
                        random.nextLong(1_500_000, 6_500_000), 0, random.nextDouble() < TRASHED_RATIO,
                    )
                }
                Kind.CAMERA_VIDEO -> Draft(
                    order, kind, taken, true, added, modified, "DCIM/Camera/", "video/mp4", "VID_$stamp.mp4",
                    1920, 1080, 0, random.nextLong(5_000_000, 250_000_000), random.nextLong(2_000, 180_000),
                    random.nextDouble() < TRASHED_RATIO,
                )
                Kind.SCREENSHOT -> Draft(
                    order, kind, taken, false, added, takenSeconds, "Pictures/Screenshots/", "image/png",
                    "Screenshot_${stamp.replace('_', '-')}.png", 1080, 2400, 0, random.nextLong(150_000, 900_000), 0,
                    random.nextDouble() < TRASHED_RATIO,
                )
                Kind.WHATSAPP_IMAGE -> Draft(
                    order, kind, taken, false, added, takenSeconds, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/",
                    "image/jpeg", "IMG-${stamp.take(8)}-WA${random.nextInt(10_000).toString().padStart(4, '0')}.jpg",
                    1600, 1200, 0, random.nextLong(60_000, 350_000), 0, random.nextDouble() < TRASHED_RATIO,
                )
                Kind.WHATSAPP_VIDEO -> Draft(
                    order, kind, taken, false, added, takenSeconds, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/",
                    "video/mp4", "VID-${stamp.take(8)}-WA${random.nextInt(10_000).toString().padStart(4, '0')}.mp4",
                    848, 480, 0, random.nextLong(500_000, 16_000_000), random.nextLong(3_000, 90_000), random.nextDouble() < TRASHED_RATIO,
                )
                Kind.DOWNLOAD -> {
                    val gif = random.nextDouble() < 0.25
                    Draft(
                        order, kind, taken, false, added, takenSeconds, "Download/", if (gif) "image/gif" else "image/png",
                        if (gif) "animation_$order.gif" else "image_$order.png", 800, 600, 0, random.nextLong(20_000, 3_000_000), 0,
                        random.nextDouble() < TRASHED_RATIO,
                    )
                }
                Kind.ALBUM -> {
                    // Un média dans chaque dossier, puis une loi de Zipf : quelques gros dossiers, beaucoup de petits.
                    val folder = if (albumMedia < albums.size) albums[albumMedia] else albums[zipf(random, albums.size, 1.1)]
                    albumMedia++
                    Draft(
                        order, kind, taken, random.nextDouble() < 0.7, added, modified, folder, "image/jpeg",
                        "DSC_${(order % 10_000).toString().padStart(4, '0')}.JPG", 6000, 4000, 0,
                        random.nextLong(3_000_000, 12_000_000), 0, random.nextDouble() < TRASHED_RATIO,
                    )
                }
            }
            drafts += draft
        }
        return drafts
    }

    private fun pickKind(random: Random): Kind {
        val x = random.nextDouble()
        return when {
            x < 0.700 -> Kind.CAMERA_PHOTO
            x < 0.735 -> Kind.CAMERA_VIDEO
            x < 0.815 -> Kind.SCREENSHOT
            x < 0.915 -> Kind.WHATSAPP_IMAGE
            x < 0.930 -> Kind.WHATSAPP_VIDEO
            x < 0.950 -> Kind.DOWNLOAD
            else -> Kind.ALBUM
        }
    }

    /** Instant des dix dernières années, de densité croissante (trois fois plus de photos à la fin qu'au début). */
    private fun growingInstant(random: Random): Long {
        val u = random.nextDouble()
        val fraction = (sqrt(1 + 8 * u) - 1) / 2 // densité 1 + 2x sur [0, 1], normalisée
        return START_MS + (fraction * SPAN_MS).toLong()
    }

    // ── Résultats d'analyse ───────────────────────────────────────────────────────────────

    private fun analysis(random: Random, entity: MediaEntity, kind: Kind): MediaAnalysisEntity {
        val photo = entity.mediaType == MediaQueryBuilder.MEDIA_TYPE_IMAGE
        val located = (kind == Kind.CAMERA_PHOTO || kind == Kind.CAMERA_VIDEO) && random.nextDouble() < 0.42
        val city = if (located) CITIES[pickCity(random)] else null
        val place = city?.let { CITY_PLACES.getValue(it) }
        return MediaAnalysisEntity(
            mediaId = entity.id,
            analyzedModified = entity.dateModified,
            version = ImageAnalyzer.VERSION,
            phash = if (photo) random.nextLong() else null,
            blurScore = if (photo) (if (random.nextDouble() < 0.05) random.nextDouble(5.0, 100.0) else random.nextDouble(100.0, 4000.0)).toFloat() else null,
            latitude = city?.let { it.latitude + random.nextDouble(-0.05, 0.05) },
            longitude = city?.let { it.longitude + random.nextDouble(-0.05, 0.05) },
            // Lieu tel que l'analyse l'enregistre : nom GeoNames, clé de ses noms traduits.
            place = place?.name,
            placeKey = place?.let { placeTranslations.searchKey(it.name, it.countryCode) },
            country = place?.countryCode,
        )
    }

    private fun pickCity(random: Random): Int {
        val x = random.nextDouble()
        return when {
            x < 0.55 -> 0 // chez soi
            x < 0.75 -> 1 + random.nextInt(3) // environs
            else -> 4 + zipf(random, CITIES.size - 4, 1.0)
        }
    }

    /** ~3 étiquettes par média, les plus courantes bien plus souvent que les autres. */
    private fun pickLabels(random: Random, kind: Kind): List<Pair<String, Float>> {
        val count = when (kind) {
            Kind.SCREENSHOT -> 1 + random.nextInt(3)
            else -> (0 until 5).count { random.nextDouble() < 0.45 } + 1
        }
        val picked = LinkedHashSet<String>()
        while (picked.size < count) picked += LABELS[zipf(random, LABELS.size, 1.05)]
        return picked.map { it to random.nextDouble(0.6, 0.99).toFloat() }
    }

    private fun text(random: Random, kind: Kind): String {
        val lines = when {
            kind == Kind.SCREENSHOT && random.nextDouble() < 0.95 -> 2 + random.nextInt(14)
            kind == Kind.CAMERA_PHOTO && random.nextDouble() < 0.025 -> return receipt(random)
            kind == Kind.CAMERA_PHOTO && random.nextDouble() < 0.10 -> 1 + random.nextInt(3)
            kind == Kind.WHATSAPP_IMAGE && random.nextDouble() < 0.08 -> 1 + random.nextInt(6)
            else -> 0
        }
        return (0 until lines).joinToString("\n") {
            (0 until 2 + random.nextInt(5)).joinToString(" ") { WORDS[zipf(random, WORDS.size, 0.9)] }
        }
    }

    private fun receipt(random: Random): String = buildString {
        appendLine(if (random.nextBoolean()) "FACTURE N° ${random.nextInt(100_000)}" else "TICKET DE CAISSE")
        repeat(3 + random.nextInt(12)) {
            appendLine("${WORDS[random.nextInt(WORDS.size)].uppercase()} ${random.nextInt(1, 40)},${random.nextInt(10, 99)} €")
        }
        appendLine("TVA 20 % ${random.nextInt(1, 30)},${random.nextInt(10, 99)} €")
        append("TOTAL TTC ${random.nextInt(10, 300)},${random.nextInt(10, 99)} €")
    }

    private fun faceCount(random: Random, kind: Kind): Int {
        val chance = when (kind) {
            Kind.CAMERA_PHOTO -> 0.30
            Kind.WHATSAPP_IMAGE -> 0.15
            Kind.ALBUM -> 0.10
            else -> 0.0
        }
        if (random.nextDouble() >= chance) return 0
        var count = 1
        while (count < 6 && random.nextDouble() < 0.4) count++
        return count
    }

    private fun face(random: Random, id: Long, mediaId: Long): FaceEntity {
        val left = random.nextDouble(0.0, 0.8).toFloat()
        val top = random.nextDouble(0.0, 0.8).toFloat()
        val side = random.nextDouble(0.05, 0.2).toFloat()
        val person = if (random.nextDouble() < 0.85) zipf(random, PERSONS, 1.0) + 1L else null
        return FaceEntity(
            id = id,
            mediaId = mediaId,
            personId = person,
            boxLeft = left,
            boxTop = top,
            boxRight = left + side,
            boxBottom = top + side,
            embedding = embedding(random),
            quality = random.nextDouble(0.3, 1.0).toFloat(),
        )
    }

    /** Empreinte SFace de 128 flottants petit-boutistes (512 octets), de norme 1. */
    private fun embedding(random: Random): ByteArray {
        val values = FloatArray(EMBEDDING_SIZE) { random.nextFloat() - 0.5f }
        val norm = sqrt(values.sumOf { it.toDouble() * it }).toFloat()
        val buffer = ByteBuffer.allocate(EMBEDDING_SIZE * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putFloat(it / norm) }
        return buffer.array()
    }

    /** Ville où l'on photographie ; son lieu enregistré est celui que trouve le géocodeur. */
    private class City(val name: String, val country: String, val latitude: Double, val longitude: Double)

    companion object {
        const val DEFAULT_SIZE = 50_000
        const val SEED = 20_261_021L

        /** Fin de la bibliothèque (30 juin 2026, 20 h UTC) : les dates ne dépendent pas du jour du test. */
        const val END_MS = 1_782_849_600_000L
        private const val DAY_SECONDS = 86_400L
        private const val HOUR_MS = 3_600_000L
        const val SPAN_MS = 10 * 365 * DAY_SECONDS * 1000
        const val START_MS = END_MS - SPAN_MS

        /** Dernier changement de téléphone : les photos plus anciennes y ont été copiées. */
        private const val MIGRATION_MS = END_MS - 540 * DAY_SECONDS * 1000
        private const val FIRST_ID = 1_000L
        private const val TRASHED_RATIO = 0.003
        private const val ALBUM_FOLDERS = 293
        private const val PERSONS = 40
        private const val EMBEDDING_SIZE = 128

        /** Bibliothèque par défaut, construite une fois par processus de test. */
        val Default: SyntheticLibrary by lazy { SyntheticLibrary() }

        /** Base des lieux embarquée (`assets/geo/cities.bin`), lue comme par l'application. */
        private val placesFile: File by lazy {
            listOf("src/main/assets", "app/src/main/assets").map { File(it, OfflineGeocoder.ASSET) }.first { it.exists() }
        }

        /** Noms français et anglais des lieux de la base (clés de recherche `place_key`). */
        val placeTranslations: PlaceTranslations by lazy { placesFile.inputStream().use(PlaceTranslations::read) }

        /** Lieu enregistré pour chaque ville : la localité la plus proche, comme le géocodeur. */
        private val CITY_PLACES: Map<City, Place> by lazy {
            val index = placesFile.inputStream().use(PlaceIndex::read)
            CITIES.associateWith { city -> index.nearest(city.latitude, city.longitude) ?: Place(city.name, city.country, 0.0) }
        }

        /** `BUCKET_ID` d'un dossier, calculé comme MediaStore (empreinte du chemin en minuscules). */
        fun bucketId(relativePath: String): Long = ("/storage/emulated/0/" + relativePath.trimEnd('/')).lowercase().hashCode().toLong()

        private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC)

        /** Indice tiré selon une loi de Zipf d'exposant [s] sur [n] valeurs (0 la plus fréquente). */
        private fun zipf(random: Random, n: Int, s: Double): Int {
            val table = zipfTables.getOrPut(n to s) {
                val weights = DoubleArray(n) { 1.0 / (it + 1.0).pow(s) }
                var total = 0.0
                DoubleArray(n) { i -> total += weights[i]; total }
            }
            val target = random.nextDouble() * table.last()
            val index = table.binarySearch(target)
            return (if (index < 0) -index - 1 else index).coerceAtMost(n - 1)
        }

        private val zipfTables = HashMap<Pair<Int, Double>, DoubleArray>()

        /** Étiquettes du modèle, les plus courantes en tête (ordre fixe), puis les autres. */
        private val LABELS: List<String> = run {
            val common = listOf(
                "Sky", "Smile", "Food", "Plant", "Building", "Dog", "Flower", "Car", "Selfie", "Room", "Sunset",
                "Beach", "Cat", "Party", "Cake", "Mountain", "Lake", "Bridge", "Toy", "Team", "Paper", "Poster", "Receipt",
            ).filter { LabelDictionary.entry(it) != null }
            common + LabelDictionary.entries.map { it.en }.distinct().filter { it !in common }
        }

        private val CITIES = listOf(
            City("Nantes", "FR", 47.218, -1.553), City("Rezé", "FR", 47.183, -1.550),
            City("Saint-Herblain", "FR", 47.212, -1.649), City("Vertou", "FR", 47.169, -1.470),
            City("Paris", "FR", 48.857, 2.352), City("La Baule-Escoublac", "FR", 47.286, -2.391),
            City("Saint-Malo", "FR", 48.649, -2.026), City("Biarritz", "FR", 43.483, -1.559),
            City("Lyon", "FR", 45.764, 4.836), City("Marseille", "FR", 43.296, 5.370),
            City("Bordeaux", "FR", 44.838, -0.579), City("Annecy", "FR", 45.899, 6.129),
            City("Barcelona", "ES", 41.389, 2.159), City("Lisboa", "PT", 38.722, -9.139),
            City("Roma", "IT", 41.893, 12.483), City("London", "GB", 51.507, -0.128),
            City("Amsterdam", "NL", 52.370, 4.895), City("Berlin", "DE", 52.520, 13.405),
            City("Bruxelles", "BE", 50.847, 4.357), City("Genève", "CH", 46.204, 6.143),
            City("New York", "US", 40.713, -74.006), City("Montréal", "CA", 45.502, -73.567),
            City("Tokyo", "JP", 35.690, 139.692), City("Marrakech", "MA", 31.630, -7.990),
            City("Dakar", "SN", 14.693, -17.447), City("Athína", "GR", 37.984, 23.728),
            City("Dublin", "IE", 53.349, -6.260), City("Praha", "CZ", 50.088, 14.420),
            City("Wien", "AT", 48.208, 16.373), City("Sevilla", "ES", 37.389, -5.984),
        )

        private val WORDS = listOf(
            "le", "la", "de", "et", "Bienvenue", "Sortie", "Ouvert", "Menu", "Boulangerie", "Parking", "Wi-Fi",
            "Code", "Réunion", "Message", "Envoyer", "Rendez-vous", "lundi", "mardi", "Total", "Horaires", "Billet",
            "Train", "Quai", "Voie", "Gare", "Café", "Pharmacie", "Mairie", "Plage", "Musée", "Entrée", "Tarif",
            "Adresse", "Téléphone", "06 12 34 56 78", "Commande", "Livraison", "Facture", "Montant", "Payer",
            "Confirmer", "Annuler", "Paramètres", "Photos", "Notification", "Batterie", "Météo", "Ensoleillé",
            "Nuageux", "Recette", "Farine", "Sucre", "Beurre", "Oeufs", "Lait", "Instagram", "WhatsApp", "Agenda",
        )

        private val PEOPLE_NAMES = listOf(
            "Alice", "Bastien", "Camille", "Damien", "Élodie", "François", "Gaëlle", "Hugo", "Inès", "Julien",
            "Karim", "Léa", "Mathis", "Nora", "Oscar",
        )
    }
}
