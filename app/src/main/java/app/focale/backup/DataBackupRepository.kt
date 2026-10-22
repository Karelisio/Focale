package app.focale.backup

import android.content.ContentResolver
import android.database.sqlite.SQLiteFullException
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import app.focale.BuildConfig
import app.focale.backup.BackupArchiveException.Reason
import app.focale.core.database.BackupDao
import app.focale.core.database.FocaleDatabase
import app.focale.core.di.IoDispatcher
import app.focale.core.mediastore.MediaRepositoryLimits
import app.focale.core.mediastore.MediaStoreSource
import app.focale.core.mediastore.MediaSyncManager
import app.focale.core.settings.SettingsRepository
import app.focale.people.FaceAnalysisStep
import app.focale.people.PeopleEditions
import app.focale.people.PeopleRepository
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Sauvegarde des données de l'application dans une archive protégée par un mot de passe
 * ([DataArchive]), et restauration, sur ce téléphone ou un autre. Tout se fait en flux : ni
 * l'archive ni la bibliothèque ne sont chargées en mémoire, seulement les clés des médias.
 *
 * Sauvegarde : lecture de l'index par tranches, sans transaction (l'analyse continue) ; un
 * enregistrement qui désigne ce que la lecture n'a pas vu (média, album ou personne ajouté
 * entre-temps) est laissé de côté ou détaché, si bien que la sauvegarde reste cohérente.
 *
 * Restauration, en deux temps. [inspect] vérifie l'archive de bout en bout (mot de passe,
 * intégrité, fin) et en tire le résumé. [restore] synchronise d'abord l'index avec MediaStore,
 * apparie les médias de la sauvegarde à ceux du téléphone ([MediaMatcher]), puis applique tout en
 * une seule transaction, validée seulement une fois l'archive relue jusqu'à sa fin : favoris
 * (réunion), albums (réunis par nom, membres ajoutés), dates d'origine, historique ; personnes
 * remplacées (visages des médias retrouvés à la place de ceux du téléphone, personnes recréées,
 * refus reportés, personnes vidées supprimées). Réglages (liste blanche, remplacés) et dossiers
 * masqués (réunion) suivent, dans le DataStore. Restaurer deux fois donne le même résultat ;
 * restaurer plus tard ajoute les médias arrivés entre-temps.
 */
@Singleton
class DataBackupRepository internal constructor(
    private val resolver: ContentResolver,
    private val database: FocaleDatabase,
    private val settings: SettingsRepository,
    private val editions: PeopleEditions,
    /** `DATE_TAKEN` brute de MediaStore ([MediaStoreSource.datesTaken]). */
    private val mediaStoreDates: (Collection<Long>) -> Map<Long, Long>,
    /** Synchronisation de l'index, attendue jusqu'au bout ([MediaSyncManager.syncNow]). */
    private val syncMedia: suspend () -> Unit,
    private val io: CoroutineDispatcher,
    /** Itérations de la dérivation du mot de passe ; réduites dans les tests. */
    private val iterations: Int,
    private val appVersion: String,
    private val clock: () -> Long,
) {
    @Inject
    constructor(
        resolver: ContentResolver,
        database: FocaleDatabase,
        settings: SettingsRepository,
        editions: PeopleEditions,
        source: MediaStoreSource,
        sync: MediaSyncManager,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(
        resolver, database, settings, editions, source::datesTaken, { sync.syncNow() }, io,
        PasswordArchive.DEFAULT_ITERATIONS, BuildConfig.VERSION_NAME, System::currentTimeMillis,
    )

    private val dao: BackupDao get() = database.backupDao()

    /** Une opération à la fois : une sauvegarde automatique attend la fin d'une restauration. */
    private val mutex = Mutex()

    // ── Sauvegarde ───────────────────────────────────────────────────────────────────

    /**
     * Sauvegarde les données dans le document [target], créé par le sélecteur système ; le
     * document est supprimé si la sauvegarde n'aboutit pas. [password] est effacé.
     *
     * @param cancelled consulté entre deux enregistrements : vrai pour annuler.
     */
    suspend fun export(
        target: Uri,
        password: CharArray,
        cancelled: () -> Boolean = { false },
        onProgress: (BackupProgress) -> Unit = {},
    ): DataExportResult = try {
        mutex.withLock {
            exportLocked(target, cancelled, onProgress) {
                // Le mot de passe ne sert qu'à la dérivation : effacé aussitôt après.
                try {
                    PasswordArchive.deriveKeys(ArchiveFormat.DATA, password, iterations)
                } finally {
                    password.fill('\u0000')
                }
            }.first
        }
    } finally {
        password.fill('\u0000')
    }

    /**
     * Sauvegarde automatique ([DataAutoBackup]) : comme [export], avec des clés déjà tirées du mot de
     * passe, effacées ensuite. Renvoie aussi l'empreinte du contenu écrit ([fingerprint]).
     */
    internal suspend fun export(target: Uri, keys: ArchiveKeys, cancelled: () -> Boolean): Pair<DataExportResult, String?> =
        try {
            mutex.withLock { exportLocked(target, cancelled, onProgress = {}) { keys } }
        } finally {
            keys.close()
        }

    /** Empreinte du contenu qu'écrirait une sauvegarde, sans rien écrire (sauvegarde automatique inutile). */
    internal suspend fun fingerprint(): String = mutex.withLock {
        withContext(io) {
            val sink = FingerprintSink()
            writeContent(sink, Progress(0, { false }, coroutineContext.job, {}))
            sink.fingerprint()
        }
    }

    /** Retire un document créé pour une sauvegarde qui n'aura pas lieu. */
    suspend fun discard(target: Uri) {
        withContext(io) { BackupDocuments.delete(resolver, target) }
    }

    /**
     * Écrit l'archive : seule implémentation de la sauvegarde, manuelle ou automatique.
     *
     * @param keys clés de l'archive, obtenues une fois l'avancement annoncé (la dérivation d'un mot
     *   de passe est lente), puis effacées.
     */
    private suspend fun exportLocked(
        target: Uri,
        cancelled: () -> Boolean,
        onProgress: (BackupProgress) -> Unit,
        keys: () -> ArchiveKeys,
    ): Pair<DataExportResult, String?> = withContext(io) {
        try {
            onProgress(BackupProgress(BackupProgress.Stage.PREPARING))
            // Index à jour : les médias sont sauvegardés sous leur nom et leur dossier actuels.
            syncMedia()
            val progress = Progress(dao.recordEstimate(), cancelled, coroutineContext.job, onProgress)
            val fingerprint = FingerprintSink()
            val counts = keys().use { archiveKeys ->
                BackupDocuments.openOutput(resolver, target).use { output ->
                    DataArchive.write(output, archiveKeys, fingerprint).use { writer ->
                        progress.start()
                        val counts = writeContent(writer, progress)
                        writer.finish()
                        counts
                    }
                }
            }
            DataExportResult.Success(counts.favorites, counts.albums, counts.people, counts.hiddenFolders) to fingerprint.fingerprint()
        } catch (error: CancelledByUser) {
            DataExportResult.Cancelled(partialFileLeft = !BackupDocuments.delete(resolver, target)) to null
        } catch (error: CancellationException) {
            BackupDocuments.delete(resolver, target)
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Sauvegarde des données impossible", error)
            DataExportResult.Failed(
                partialFileLeft = !BackupDocuments.delete(resolver, target),
                noSpace = BackupDocuments.isNoSpace(error),
            ) to null
        }
    }

    /** Ce qu'une sauvegarde contient, compté à l'écriture. */
    private class Counts {
        var favorites = 0
        var albums = 0
        var people = 0
        var hiddenFolders = 0
    }

    /**
     * Écrit tout le contenu dans [sink], dans l'ordre du format ([DataArchive]) : en-tête, réglages,
     * dossiers masqués, médias, puis ce qui les désigne par leur numéro.
     */
    private suspend fun writeContent(sink: RecordSink, progress: Progress): Counts {
        val counts = Counts()
        fun write(record: DataRecord) {
            sink.write(record)
            progress.record()
        }
        write(HeaderRecord(createdAt = clock(), appVersion = appVersion, faceVersion = FaceAnalysisStep.VERSION))
        val (values, known) = settings.backupValues()
        write(SettingsRecord(values, known))
        val hidden = settings.settings.first().hiddenFolders.sorted()
        val folders = hidden.chunked(MediaRepositoryLimits.MAX_SQL_ARGS).flatMap { dao.folders(it) }.associateBy { it.bucketId }
        for (bucket in hidden) {
            val folder = folders[bucket]
            write(HiddenFolderRecord(bucket, folder?.relativePath, folder?.bucketName))
            counts.hiddenFolders++
        }

        // Médias, par identifiant croissant : leur numéro se retrouve par dichotomie.
        val written = LongList()
        var after = Long.MIN_VALUE
        while (true) {
            val page = dao.mediaRefs(after, PAGE)
            if (page.isEmpty()) break
            for (row in page) {
                write(MediaRecord(path = row.relativePath, name = row.displayName, size = row.size, taken = row.dateTaken))
                written.add(row.id)
            }
            after = page.last().id
        }
        val ids = written.toArray()
        fun media(id: Long): Int? = ids.binarySearch(id).takeIf { it >= 0 }

        for (date in dao.mediaDates()) media(date.mediaId)?.let { write(MediaDateRecord(it, date.dateTaken)) }
        for (favorite in dao.favorites()) {
            val number = media(favorite.mediaId) ?: continue
            write(FavoriteRecord(number, favorite.addedAt))
            counts.favorites++
        }
        val albums = HashSet<Long>()
        for (album in dao.albums()) {
            write(AlbumRecord(album.id, album.name, album.createdAt, cover = album.coverMediaId?.let(::media)))
            albums += album.id
            counts.albums++
        }
        var lastAlbum = Long.MIN_VALUE
        var lastMedia = Long.MIN_VALUE
        while (true) {
            val page = dao.albumMembers(lastAlbum, lastMedia, PAGE)
            if (page.isEmpty()) break
            for (member in page) {
                val number = media(member.mediaId) ?: continue
                if (member.albumId in albums) write(AlbumMemberRecord(member.albumId, number, member.addedAt))
            }
            lastAlbum = page.last().albumId
            lastMedia = page.last().mediaId
        }

        val photos = dao.personPhotos().associate { it.personId to it.photos }
        val persons = HashSet<Long>()
        for (person in dao.persons()) {
            val count = photos[person.id] ?: 0
            write(PersonRecord(person.id, person.name, person.hidden, person.pinned, person.createdAt, person.coverFaceId, count))
            persons += person.id
            if (person.name != null || count >= PeopleRepository.MIN_PHOTOS) counts.people++
        }
        for (refusal in dao.refusals()) {
            if (refusal.personLow in persons && refusal.personHigh in persons) write(RefusalRecord(refusal.personLow, refusal.personHigh))
        }

        after = Long.MIN_VALUE
        while (true) {
            val page = dao.faceScans(after, PAGE)
            if (page.isEmpty()) break
            for (scan in page) {
                media(scan.mediaId)?.let { write(FaceScanRecord(it, scan.version, scan.faceCount, scan.current)) }
            }
            after = page.last().mediaId
        }
        after = Long.MIN_VALUE
        while (true) {
            val page = dao.faces(after, FACE_PAGE)
            if (page.isEmpty()) break
            for (face in page) {
                val number = media(face.mediaId) ?: continue
                // Personne créée pendant la lecture : visage laissé libre, que le regroupement reprendra.
                val known = face.personId == null || face.personId in persons
                write(
                    FaceRecord(
                        id = face.id,
                        media = number,
                        person = face.personId.takeIf { known },
                        left = face.boxLeft,
                        top = face.boxTop,
                        right = face.boxRight,
                        bottom = face.boxBottom,
                        embedding = face.embedding,
                        quality = face.quality,
                        manual = face.manual && known,
                    ),
                )
            }
            after = page.last().id
        }
        for (search in dao.searchHistory()) write(SearchRecord(search.query, search.searchedAt))
        return counts
    }

    // ── Vérification ─────────────────────────────────────────────────────────────────

    /**
     * Vérifie l'archive [source] de bout en bout avec [password] (effacé) et en tire le résumé ;
     * rien n'est modifié. La sauvegarde vérifiée se restaure ensuite sans redemander le mot de passe.
     *
     * @param cancelled consulté entre deux enregistrements : vrai pour abandonner.
     */
    suspend fun inspect(source: Uri, password: CharArray, cancelled: () -> Boolean = { false }): DataInspectResult = try {
        mutex.withLock {
            withContext(io) {
                val job = coroutineContext.job
                read(source) { archive ->
                    val keys = try {
                        archive.keysFor(password)
                    } finally {
                        password.fill('\u0000')
                    }
                    try {
                        val summary = DataArchive.read(archive, keys).use { reader -> summarize(reader, Progress(0, cancelled, job, {})) }
                        DataInspectResult.Ready(InspectedBackup(source, summary, keys))
                    } catch (error: Throwable) {
                        keys.close()
                        throw error
                    }
                }
            }
        }
    } catch (error: Failure) {
        if (error.reason == null) DataInspectResult.WrongPassword else DataInspectResult.Failed(error.reason)
    } catch (error: CancelledByUser) {
        DataInspectResult.Cancelled
    } finally {
        password.fill('\u0000')
    }

    /** Lit tout le contenu, fin comprise, en ne décodant que ce que résume [DataBackupSummary]. */
    private suspend fun summarize(reader: DataArchiveReader, progress: Progress): DataBackupSummary {
        var header: HeaderRecord? = null
        var favorites = 0
        var albums = 0
        var people = 0
        var hiddenFolders = 0
        var media = 0
        var faces = 0
        var searches = 0
        while (true) {
            progress.checkpoint()
            val raw = reader.next() ?: break
            if ((header == null) != (raw.type == RecordType.HEADER)) throw BackupArchiveException(Reason.CORRUPTED)
            when (raw.type) {
                RecordType.HEADER -> header = raw.decode() as HeaderRecord
                RecordType.HIDDEN_FOLDER -> hiddenFolders++
                RecordType.MEDIA -> media++
                RecordType.FAVORITE -> favorites++
                RecordType.ALBUM -> albums++
                RecordType.PERSON -> {
                    val person = raw.decode() as PersonRecord
                    if (!person.name.isNullOrBlank() || person.photos >= PeopleRepository.MIN_PHOTOS) people++
                }
                RecordType.FACE -> faces++
                RecordType.SEARCH -> searches++
                else -> Unit
            }
        }
        val first = header ?: throw BackupArchiveException(Reason.CORRUPTED)
        val existing = database.faceDao().observePeople(includeHidden = true, minPhotos = PeopleRepository.MIN_PHOTOS).first().size
        return DataBackupSummary(
            createdAt = first.createdAt,
            appVersion = first.appVersion,
            favorites = favorites,
            albums = albums,
            people = people,
            hiddenFolders = hiddenFolders,
            media = media,
            faces = faces,
            searches = searches,
            existingPeople = existing,
            records = reader.records,
        )
    }

    // ── Restauration ─────────────────────────────────────────────────────────────────

    /**
     * Restaure la sauvegarde vérifiée [backup] (ses clés ne sont pas effacées) : tout ou rien, la
     * transaction n'étant validée qu'une fois l'archive relue jusqu'à sa fin. Réglages et dossiers
     * masqués suivent la validation.
     *
     * @param cancelled consulté entre deux enregistrements : vrai pour annuler (rien n'est modifié).
     */
    suspend fun restore(
        backup: InspectedBackup,
        cancelled: () -> Boolean = { false },
        onProgress: (BackupProgress) -> Unit = {},
    ): DataRestoreResult = try {
        mutex.withLock {
            withContext(io) {
                onProgress(BackupProgress(BackupProgress.Stage.PREPARING))
                // Photos tout juste copiées : l'index doit les connaître avant l'appariement.
                syncMedia()
                val progress = Progress(backup.summary.records, cancelled, coroutineContext.job, onProgress)
                val applied = read(backup.source) { archive ->
                    DataArchive.read(archive, backup.keys).use { reader ->
                        // Ni l'analyse des visages ni l'utilisateur n'écrivent pendant le remplacement.
                        editions.writes.withLock {
                            database.withTransaction { DataRestore(database, mediaStoreDates, progress::checkpoint, progress::record).run(reader) }
                        }
                    }
                }
                progress.finishing()
                withContext(NonCancellable) { finish(applied) }
            }
        }
    } catch (error: Failure) {
        DataRestoreResult.Failed(error.reason ?: DataBackupFailure.CORRUPTED)
    } catch (error: CancelledByUser) {
        DataRestoreResult.Cancelled
    }

    /** Après la validation : réglages et dossiers masqués (DataStore), personnes à recharger par l'analyse. */
    private suspend fun finish(applied: DataRestoreApplied): DataRestoreResult {
        editions.bump()
        val settingsRestored = applied.settings?.let { record ->
            try {
                settings.restoreBackup(record.values, record.keys)
                true
            } catch (error: IOException) {
                Log.w(TAG, "Réglages non restaurés", error)
                false
            }
        } ?: true
        val hidden = try {
            settings.hideFolders(applied.hiddenFolders)
        } catch (error: IOException) {
            Log.w(TAG, "Dossiers masqués non restaurés", error)
            0
        }
        return DataRestoreResult.Success(applied.report.copy(hiddenFolders = hidden, settings = settingsRestored))
    }

    /**
     * Ouvre l'archive [source], vérifie qu'elle est une sauvegarde des données et la passe à
     * [block] ; les refus deviennent des [Failure], une erreur de lecture [DataBackupFailure.UNREADABLE].
     */
    private suspend fun <T> read(source: Uri, block: suspend (SealedArchive) -> T): T = try {
        val input = resolver.openInputStream(source) ?: throw FileNotFoundException("Lecture impossible : $source")
        input.buffered(BUFFER).use { block(DataArchive.open(it)) }
    } catch (error: BackupArchiveException) {
        throw Failure(
            when (error.reason) {
                Reason.WRONG_PASSWORD -> null
                Reason.NOT_AN_ARCHIVE -> DataBackupFailure.NOT_AN_ARCHIVE
                Reason.OTHER_BACKUP -> DataBackupFailure.VAULT_BACKUP
                Reason.UNSUPPORTED -> DataBackupFailure.UNSUPPORTED
                Reason.CORRUPTED -> {
                    Log.w(TAG, "Sauvegarde refusée", error)
                    DataBackupFailure.CORRUPTED
                }
            },
        )
    } catch (error: CancelledByUser) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (error: SQLiteFullException) {
        throw Failure(DataBackupFailure.NO_SPACE)
    } catch (error: Exception) {
        Log.w(TAG, "Sauvegarde illisible", error)
        throw Failure(if (BackupDocuments.isNoSpace(error)) DataBackupFailure.NO_SPACE else DataBackupFailure.UNREADABLE)
    }

    /**
     * Points d'annulation et avancement, publié au plus toutes les [UPDATE_INTERVAL_NS] : [total]
     * enregistrements attendus (estimés à la sauvegarde, comptés par la vérification).
     */
    private class Progress(
        private val total: Int,
        private val cancelled: () -> Boolean,
        private val job: Job,
        private val onProgress: (BackupProgress) -> Unit,
    ) {
        private var done = 0
        private var publishedAt = 0L

        fun checkpoint() {
            if (cancelled()) throw CancelledByUser()
            job.ensureActive()
        }

        fun start() = publish(force = true)

        fun record() {
            done++
            if (done % CHECK_EVERY == 0) checkpoint()
            publish(force = false)
        }

        fun finishing() {
            onProgress(BackupProgress(BackupProgress.Stage.FINISHING, total, total))
        }

        private fun publish(force: Boolean) {
            val now = System.nanoTime()
            if (!force && now - publishedAt < UPDATE_INTERVAL_NS) return
            publishedAt = now
            // Estimation dépassée (sauvegarde pendant l'analyse) : la fin reste à 100 %.
            onProgress(BackupProgress(BackupProgress.Stage.TRANSFERRING, done, maxOf(total, done)))
        }
    }

    /** Refus de l'archive ; [reason] null : mot de passe incorrect. */
    private class Failure(val reason: DataBackupFailure?) : RuntimeException()

    private companion object {
        const val TAG = "DataBackup"
        const val BUFFER = 256 * 1024
        const val PAGE = 1_000
        const val FACE_PAGE = 500
        const val CHECK_EVERY = 64
        const val UPDATE_INTERVAL_NS = 100_000_000L
    }
}

/** Annulation demandée par l'utilisateur ; distincte de celle de la coroutine. */
private class CancelledByUser : RuntimeException()
