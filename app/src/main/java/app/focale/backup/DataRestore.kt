package app.focale.backup

import app.focale.backup.BackupArchiveException.Reason
import app.focale.core.database.AlbumEntity
import app.focale.core.database.AlbumMediaEntity
import app.focale.core.database.FaceEntity
import app.focale.core.database.FaceScanEntity
import app.focale.core.database.FavoriteEntity
import app.focale.core.database.FocaleDatabase
import app.focale.core.database.MediaDateEntity
import app.focale.core.database.PersonEntity
import app.focale.core.database.SearchHistoryEntity
import app.focale.core.mediastore.MediaRepositoryLimits
import app.focale.people.FaceAnalysisStep
import app.focale.people.FaceEmbeddings
import app.focale.people.PeopleRepository
import java.text.Normalizer
import java.util.Locale

/** Ce que la transaction a restauré, et ce qui reste à écrire après elle. */
internal class DataRestoreApplied(
    val report: DataRestoreReport,
    val settings: SettingsRecord?,
    val hiddenFolders: Set<Long>,
)

/**
 * Médias de ce téléphone, hors corbeille, réduits à ce que demandent l'appariement et la
 * restauration : clés, identifiants, dates de modification, dossiers par chemin.
 */
private class Library(
    val keys: MediaKeyTable,
    val ids: LongArray,
    val modified: LongArray,
    val buckets: Set<Long>,
    val folders: Map<String, Set<Long>>,
)

/**
 * Application d'une sauvegarde des données lue par [DataArchiveReader], dans la transaction de
 * [DataBackupRepository.restore] : appariement des médias puis enregistrement de chaque
 * enregistrement, en lots ; rien n'est validé si une exception l'interrompt.
 *
 * Seules les [categories] choisies sont appliquées : les enregistrements des autres sont passés
 * sans être décodés, et rien de ce qui les concerne sur le téléphone n'est touché (pas même les
 * personnes vides, si les personnes ne sont pas restaurées). Médias et bibliothèque ne sont lus
 * que si une catégorie en a besoin.
 *
 * @param checkpoint point d'annulation, appelé entre deux enregistrements.
 * @param recorded appelé après chaque enregistrement lu (avancement).
 */
internal class DataRestore(
    private val database: FocaleDatabase,
    /** `DATE_TAKEN` brute de MediaStore ([app.focale.core.mediastore.MediaStoreSource.datesTaken]). */
    private val mediaStoreDates: (Collection<Long>) -> Map<Long, Long>,
    private val checkpoint: () -> Unit,
    private val recorded: () -> Unit,
    private val categories: Set<DataCategory> = DataCategory.ALL,
) {
    private val dao = database.backupDao()
    private val faceDao = database.faceDao()
    private val albumDao = database.albumDao()
    private val favoriteDao = database.favoriteDao()
    private val mediaDao = database.mediaDao()
    private val historyDao = database.searchHistoryDao()

    private lateinit var library: Library
    private var header: HeaderRecord? = null
    private var settings: SettingsRecord? = null
    private val hiddenFolders = ArrayList<HiddenFolderRecord>()
    private val backupMedia = MediaKeyTable.Builder()

    /** Média du téléphone (position dans [library]) retrouvé pour chaque média de la sauvegarde, -1 sinon. */
    private var matches: IntArray? = null

    /** Médias de la sauvegarde désignés par ce qui est restauré : le bilan ne compte qu'eux. */
    private var referenced = BooleanArray(0)
    private val needsMedia = categories.any { it.needsMedia }
    private var embeddingsUsable = true

    private val favorites = ArrayList<FavoriteEntity>()
    private val members = ArrayList<AlbumMediaEntity>()
    private val albumsByName = HashMap<String, Pair<Long, Boolean>>()
    private val albumTargets = HashMap<Long, Long>()
    private val persons = HashMap<Long, Long>()
    private val coverFaces = HashMap<Long, Long>()
    private val covers = HashMap<Long, Long>()
    private var replaced = BooleanArray(0)
    private val pendingDeletes = ArrayList<Long>()

    /** Personnes du téléphone dont des visages ont été remplacés. */
    private val touched = HashSet<Long>()
    private val scans = ArrayList<FaceScanEntity>()
    private val faces = ArrayList<FaceEntity>()
    private val faceIds = LongList()
    private val rescan = HashSet<Long>()
    private val dates = HashMap<Long, Long>()
    private val searches = ArrayList<SearchRecord>()

    private var favoriteCount = 0
    private var albumCount = 0
    private var faceCount = 0

    suspend fun run(reader: DataArchiveReader): DataRestoreApplied {
        library = if (needsMedia || DataCategory.HIDDEN_FOLDERS in categories) loadLibrary() else EMPTY_LIBRARY
        replaced = BooleanArray(library.ids.size)
        if (DataCategory.ALBUMS in categories) {
            for (album in dao.albumNames()) albumsByName.putIfAbsent(albumKey(album.name), album.id to (album.coverMediaId == null))
        }
        while (true) {
            checkpoint()
            val raw = reader.next() ?: break
            // En-tête d'abord, même pour une restauration partielle.
            if ((header == null) != (raw.type == RecordType.HEADER)) throw BackupArchiveException(Reason.CORRUPTED)
            if (wanted(raw.type)) raw.decode()?.let { apply(it) }
            recorded()
        }
        if (header == null) throw BackupArchiveException(Reason.CORRUPTED)
        val matched = if (needsMedia) matches() else IntArray(0)
        flushFavorites()
        flushMembers()
        val people = if (DataCategory.PEOPLE in categories) finishPeople() else 0
        applyDates()
        applySearches()
        return DataRestoreApplied(
            report = DataRestoreReport(
                media = referenced.count { it },
                matched = matched.indices.count { referenced[it] && matched[it] >= 0 },
                favorites = favoriteCount,
                albums = albumCount,
                people = people,
                faces = faceCount,
                hiddenFolders = 0,
                searches = searches.size,
                settings = settings?.let { true },
                dates = dates.size,
                categories = categories,
            ),
            settings = settings,
            hiddenFolders = hiddenFolderIds(),
        )
    }

    /** Enregistrement à décoder : structure de l'archive, médias si besoin, catégories choisies. */
    private fun wanted(type: RecordType?): Boolean = when (val category = type?.category) {
        null -> type == RecordType.HEADER || (type == RecordType.MEDIA && needsMedia)
        else -> category in categories
    }

    /** Fin des personnes : vignettes, photos à relire, personnes vidées ; nombre de personnes listées recréées. */
    private suspend fun finishPeople(): Int {
        flushScans()
        flushDeletes()
        flushFaces()
        for ((person, face) in covers) faceDao.setCover(person, face)
        rescan.toList().chunked(MediaRepositoryLimits.MAX_SQL_ARGS).forEach { dao.invalidateScans(it) }
        // Personne du téléphone qui ne garde des visages que dans la corbeille : remplacée elle aussi,
        // plutôt que de revenir en double si une photo en sort.
        touched.removeAll(persons.values.toSet())
        touched.toList().chunked(MediaRepositoryLimits.MAX_SQL_ARGS).forEach { dao.detachLeftovers(it) }
        // Personnes recréées sans visage retrouvé, ou du téléphone vidées par le remplacement.
        faceDao.deleteEmptyPersons()
        return persons.values.chunked(MediaRepositoryLimits.MAX_SQL_ARGS)
            .sumOf { dao.listedPeople(it, PeopleRepository.MIN_PHOTOS) }
    }

    private suspend fun apply(record: DataRecord) {
        if ((header == null) != (record is HeaderRecord)) throw BackupArchiveException(Reason.CORRUPTED)
        when (record) {
            is HeaderRecord -> {
                header = record
                // Empreintes d'une version ultérieure de l'analyse : peut-être d'un autre modèle.
                embeddingsUsable = record.faceVersion <= FaceAnalysisStep.VERSION
            }
            is SettingsRecord -> settings = record
            is HiddenFolderRecord -> hiddenFolders += record
            is MediaRecord -> {
                // Les médias précèdent tout ce qui les désigne.
                if (matches != null) throw BackupArchiveException(Reason.CORRUPTED)
                backupMedia.add(record.path, record.name, record.size, record.taken)
            }
            is MediaDateRecord -> mediaId(record.media)?.let { dates[it] = record.taken }
            is FavoriteRecord -> mediaId(record.media)?.let { id ->
                favorites += FavoriteEntity(id, record.addedAt)
                favoriteCount++
                if (favorites.size >= BATCH) flushFavorites()
            }
            is AlbumRecord -> applyAlbum(record)
            is AlbumMemberRecord -> {
                val album = albumTargets[record.album] ?: return
                val id = mediaId(record.media) ?: return
                members += AlbumMediaEntity(album, id, record.addedAt)
                if (members.size >= BATCH) flushMembers()
            }
            is PersonRecord -> {
                val id = faceDao.insertPerson(
                    PersonEntity(
                        name = record.name?.trim()?.takeIf { it.isNotEmpty() },
                        hidden = record.hidden,
                        pinned = record.pinned,
                        createdAt = record.createdAt,
                    ),
                )
                persons[record.id] = id
                record.cover?.let { coverFaces[it] = id }
            }
            is RefusalRecord -> {
                val low = persons[record.low] ?: return
                val high = persons[record.high] ?: return
                if (low != high) faceDao.refuse(minOf(low, high), maxOf(low, high))
            }
            is FaceScanRecord -> {
                val index = libraryIndex(record.media) ?: return
                replace(index)
                scans += FaceScanEntity(
                    mediaId = library.ids[index],
                    // Photo modifiée depuis son analyse : à relire, visages gardés pour la relecture.
                    analyzedModified = if (record.current) library.modified[index] else 0,
                    version = if (embeddingsUsable && record.version <= FaceAnalysisStep.VERSION) record.version else 0,
                    faceCount = record.faces,
                )
                if (scans.size >= BATCH) flushScans()
            }
            is FaceRecord -> applyFace(record)
            is SearchRecord -> searches += record
        }
    }

    private suspend fun applyAlbum(record: AlbumRecord) {
        val name = record.name.trim().ifEmpty { ALBUM_FALLBACK_NAME }
        val cover = record.cover?.let(::mediaId)
        val existing = albumsByName[albumKey(name)]
        val target = if (existing == null) {
            albumDao.insert(AlbumEntity(name = name, createdAt = record.createdAt, coverMediaId = cover)).also {
                albumsByName[albumKey(name)] = it to (cover == null)
            }
        } else {
            val (id, withoutCover) = existing
            // Album du même nom : il garde sa couverture, ou prend celle de la sauvegarde s'il n'en a pas.
            if (withoutCover && cover != null) {
                albumDao.setCover(id, cover)
                albumsByName[albumKey(name)] = id to false
            }
            id
        }
        albumTargets[record.id] = target
        albumCount++
    }

    private suspend fun applyFace(record: FaceRecord) {
        val index = libraryIndex(record.media) ?: return
        replace(index)
        // Les visages du téléphone sur cette photo partent avant que ceux de la sauvegarde n'arrivent.
        flushDeletes()
        val mediaId = library.ids[index]
        val person = record.person?.let(persons::get)
        // Personne inconnue de la sauvegarde : visage libre, que le regroupement reprendra.
        val attached = record.person == null || person != null
        val usable = embeddingsUsable && record.embedding.size == EMBEDDING_BYTES
        if (!usable) rescan += mediaId
        faces += FaceEntity(
            mediaId = mediaId,
            personId = person,
            boxLeft = record.left,
            boxTop = record.top,
            boxRight = record.right,
            boxBottom = record.bottom,
            // Empreinte inutilisable : nulle (aucune ressemblance) jusqu'à la relecture de la photo.
            embedding = if (usable) record.embedding else ByteArray(EMBEDDING_BYTES),
            quality = record.quality,
            manual = record.manual && attached,
        )
        faceIds.add(record.id)
        faceCount++
        if (faces.size >= FACE_BATCH) flushFaces()
    }

    /** Les visages du téléphone sur la photo [index] seront remplacés par ceux de la sauvegarde. */
    private fun replace(index: Int) {
        if (replaced[index]) return
        replaced[index] = true
        pendingDeletes += library.ids[index]
    }

    private fun matches(): IntArray = matches ?: MediaMatcher.match(backupMedia.build(), library.keys).also {
        matches = it
        referenced = BooleanArray(it.size)
    }

    private fun libraryIndex(media: Int): Int? {
        val matched = matches()
        if (media !in matched.indices) throw BackupArchiveException(Reason.CORRUPTED)
        referenced[media] = true
        return matched[media].takeIf { it >= 0 }
    }

    private fun mediaId(media: Int): Long? = libraryIndex(media)?.let { library.ids[it] }

    private suspend fun flushFavorites() {
        if (favorites.isEmpty()) return
        favoriteDao.insert(favorites.toList())
        favorites.clear()
    }

    private suspend fun flushMembers() {
        if (members.isEmpty()) return
        albumDao.addMedia(members.toList())
        members.clear()
    }

    private suspend fun flushScans() {
        if (scans.isEmpty()) return
        faceDao.upsertScans(scans.toList())
        scans.clear()
    }

    private suspend fun flushDeletes() {
        if (pendingDeletes.isEmpty()) return
        for (chunk in pendingDeletes.chunked(MediaRepositoryLimits.MAX_SQL_ARGS)) {
            touched += dao.personsIn(chunk)
            dao.deleteFacesOf(chunk)
        }
        pendingDeletes.clear()
    }

    private suspend fun flushFaces() {
        if (faces.isEmpty()) return
        val ids = faceDao.insertFaces(faces.toList())
        ids.forEachIndexed { index, id -> coverFaces[faceIds[index]]?.let { person -> covers[person] = id } }
        faces.clear()
        faceIds.clear()
    }

    /** Dates d'origine : retenues, et date de tri des médias dont MediaStore n'a pas de date de prise de vue. */
    private suspend fun applyDates() {
        for (chunk in dates.keys.toList().chunked(MediaRepositoryLimits.MAX_SQL_ARGS)) {
            val known = mediaStoreDates(chunk)
            for (id in chunk) {
                val taken = dates.getValue(id)
                mediaDao.upsertOriginalDate(MediaDateEntity(id, taken))
                if ((known[id] ?: 0) <= 0) mediaDao.setDateTaken(id, taken)
            }
        }
    }

    /**
     * Historique réuni à celui du téléphone : une même recherche n'y figure qu'une fois, à sa date
     * la plus récente, ses personnes désignées par celles recréées ([SearchHistoryMerge]).
     */
    private suspend fun applySearches() {
        if (searches.isEmpty()) return
        val existing = dao.searchHistory().groupBy { SearchHistoryMerge.canonical(it.query) }
        for (search in searches) {
            val key = SearchHistoryMerge.remap(search.query, persons::get)
            val same = existing[SearchHistoryMerge.canonical(key)].orEmpty()
            same.filter { it.query != key }.forEach { historyDao.delete(it.query) }
            historyDao.upsert(SearchHistoryEntity(key, maxOf(search.searchedAt, same.maxOfOrNull { it.searchedAt } ?: 0)))
        }
    }

    /**
     * Dossiers masqués de la sauvegarde sur ce téléphone : le même `BUCKET_ID` s'il y existe, sinon
     * les dossiers de même chemin, sinon le `BUCKET_ID` d'origine (empreinte du chemin, que le
     * dossier aura s'il est recréé au même endroit).
     */
    private fun hiddenFolderIds(): Set<Long> = hiddenFolders.flatMapTo(HashSet()) { record ->
        when {
            record.bucketId in library.buckets -> listOf(record.bucketId)
            record.path != null -> library.folders[MediaKeys.folder(record.path)]?.toList() ?: listOf(record.bucketId)
            else -> listOf(record.bucketId)
        }
    }

    private suspend fun loadLibrary(): Library {
        val keys = MediaKeyTable.Builder()
        val ids = LongList()
        val modified = LongList()
        val buckets = HashSet<Long>()
        val folders = HashMap<String, MutableSet<Long>>()
        var after = Long.MIN_VALUE
        while (true) {
            checkpoint()
            val page = dao.library(after, LIBRARY_PAGE)
            if (page.isEmpty()) break
            for (row in page) {
                keys.add(row.relativePath, row.displayName, row.size, row.dateTaken)
                ids.add(row.id)
                modified.add(row.dateModified)
                if (buckets.add(row.bucketId)) folders.getOrPut(MediaKeys.folder(row.relativePath)) { HashSet() } += row.bucketId
            }
            after = page.last().id
        }
        return Library(keys.build(), ids.toArray(), modified.toArray(), buckets, folders)
    }

    private companion object {
        /** Bibliothèque non lue : aucune catégorie choisie n'en a besoin. */
        val EMPTY_LIBRARY = Library(MediaKeyTable.Builder().build(), LongArray(0), LongArray(0), emptySet(), emptyMap())

        const val LIBRARY_PAGE = 2_000
        const val BATCH = 500
        const val FACE_BATCH = 500
        const val EMBEDDING_BYTES = FaceEmbeddings.DIMENSION * Float.SIZE_BYTES

        /** Nom d'un album sans nom, comme dans le coffre. */
        const val ALBUM_FALLBACK_NAME = "Album"

        /** Nom d'album comparé sans casse, espaces ni différence de normalisation. */
        fun albumKey(name: String): String = Normalizer.normalize(name.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }
}

/** Catégorie d'un type d'enregistrement ; null pour la structure de l'archive (en-tête, médias). */
internal val RecordType.category: DataCategory?
    get() = when (this) {
        RecordType.HEADER, RecordType.MEDIA -> null
        RecordType.SETTINGS -> DataCategory.SETTINGS
        RecordType.HIDDEN_FOLDER -> DataCategory.HIDDEN_FOLDERS
        RecordType.MEDIA_DATE -> DataCategory.ORIGINAL_DATES
        RecordType.FAVORITE -> DataCategory.FAVORITES
        RecordType.ALBUM, RecordType.ALBUM_MEMBER -> DataCategory.ALBUMS
        RecordType.PERSON, RecordType.REFUSAL, RecordType.FACE_SCAN, RecordType.FACE -> DataCategory.PEOPLE
        RecordType.SEARCH -> DataCategory.SEARCH_HISTORY
    }
