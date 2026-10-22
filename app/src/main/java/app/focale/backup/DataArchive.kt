package app.focale.backup

import app.focale.backup.BackupArchiveException.Reason
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Sauvegarde des données de l'application (`.focale`) : favoris, albums virtuels, personnes et
 * visages, dossiers masqués, réglages, dates d'origine et historique de recherche ; pas le coffre
 * (il a sa propre sauvegarde), ni la corbeille, ni ce que l'analyse recalcule (étiquettes, texte,
 * empreintes de recherche, lieux).
 *
 * Format, version 1 :
 *
 * ```
 * En-tête en clair de [PasswordArchive] : signature « FOCALE-DATA » suivie d'un octet nul,
 * domaine des clés « focale-data-backup » ([ArchiveFormat.DATA])
 * Flux chiffré : contenu compressé (zlib, RFC 1950)
 *   contenu        := enregistrement* · fin
 *   enregistrement := type (1 o) · longueur (4 o, big-endian, 1 o à 1 Mio) · JSON UTF-8
 *   fin            := type 0
 * ```
 *
 * Types ([RecordType]), dans cet ordre : en-tête (1, le premier), réglages (2), dossier masqué
 * (3), média (4), date d'origine (5), favori (6), album (7), contenu d'album (8), personne (9),
 * refus de fusion (10), passage de la détection des visages (11), visage (12), recherche (13). Un
 * type inconnu (version ultérieure) est ignoré, comme les champs inconnus de chaque JSON ; un
 * changement que l'ancienne version ne saurait pas ignorer change la version du format.
 *
 * L'archive s'écrit et se lit en flux, enregistrement par enregistrement : la mémoire ne dépend
 * pas de sa taille (une grande bibliothèque compte 100 000 visages et leurs empreintes). Elle
 * n'est vérifiée entière qu'à la lecture de la fin ([DataArchiveReader.next] renvoie null) :
 * rien ne doit être conservé avant.
 */
object DataArchive {

    /** Plus long enregistrement accepté (un visage en occupe moins de 1 Kio). */
    internal const val MAX_RECORD_BYTES = 1 shl 20

    /** Champs inconnus ignorés, valeurs par défaut omises : une version ultérieure peut compléter les enregistrements. */
    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    /**
     * Commence une archive dans [output] avec les clés [keys] (effacées par l'appelant) ; le
     * contenu s'y écrit enregistrement par enregistrement, puis [DataArchiveWriter.finish] termine
     * l'archive et ferme [output]. Interrompue avant, elle reste inachevée et sera refusée.
     *
     * @param fingerprint reçoit aussi chaque enregistrement écrit : empreinte de ce qui a été écrit.
     */
    fun write(output: OutputStream, keys: ArchiveKeys, fingerprint: FingerprintSink? = null): DataArchiveWriter {
        require(keys.format == ArchiveFormat.DATA) { "Clés d'une autre sauvegarde" }
        return DataArchiveWriter(PasswordArchive.encrypt(output, keys), fingerprint)
    }

    /**
     * Lit l'en-tête de [input] : une sauvegarde des données, sinon [BackupArchiveException]
     * ([Reason.OTHER_BACKUP] pour une sauvegarde du coffre). Ses clés s'obtiennent ensuite du mot de
     * passe ([SealedArchive.keysFor]), puis [read] en lit le contenu.
     */
    fun open(input: InputStream): SealedArchive = PasswordArchive.read(input, ArchiveFormat.DATA)

    /** Contenu de [archive], déchiffré avec [keys] (qui peuvent être effacées aussitôt). */
    fun read(archive: SealedArchive, keys: ArchiveKeys): DataArchiveReader =
        DataArchiveReader(archive.decrypt(keys), archive.version)
}

/** Type d'enregistrement, écrit devant lui ; les numéros ne changent jamais. */
internal enum class RecordType(val id: Int, val serializer: KSerializer<out DataRecord>) {
    HEADER(1, HeaderRecord.serializer()),
    SETTINGS(2, SettingsRecord.serializer()),
    HIDDEN_FOLDER(3, HiddenFolderRecord.serializer()),
    MEDIA(4, MediaRecord.serializer()),
    MEDIA_DATE(5, MediaDateRecord.serializer()),
    FAVORITE(6, FavoriteRecord.serializer()),
    ALBUM(7, AlbumRecord.serializer()),
    ALBUM_MEMBER(8, AlbumMemberRecord.serializer()),
    PERSON(9, PersonRecord.serializer()),
    REFUSAL(10, RefusalRecord.serializer()),
    FACE_SCAN(11, FaceScanRecord.serializer()),
    FACE(12, FaceRecord.serializer()),
    SEARCH(13, SearchRecord.serializer()),
    ;

    companion object {
        /** Fin du contenu. */
        const val END = 0

        private val byId = entries.associateBy { it.id }

        fun of(id: Int): RecordType? = byId[id]

        fun of(record: DataRecord): RecordType = when (record) {
            is HeaderRecord -> HEADER
            is SettingsRecord -> SETTINGS
            is HiddenFolderRecord -> HIDDEN_FOLDER
            is MediaRecord -> MEDIA
            is MediaDateRecord -> MEDIA_DATE
            is FavoriteRecord -> FAVORITE
            is AlbumRecord -> ALBUM
            is AlbumMemberRecord -> ALBUM_MEMBER
            is PersonRecord -> PERSON
            is RefusalRecord -> REFUSAL
            is FaceScanRecord -> FACE_SCAN
            is FaceRecord -> FACE
            is SearchRecord -> SEARCH
        }

        /** JSON UTF-8 de [record]. */
        @Suppress("UNCHECKED_CAST")
        fun encode(record: DataRecord): ByteArray =
            DataArchive.json.encodeToString(of(record).serializer as KSerializer<DataRecord>, record).toByteArray(Charsets.UTF_8)
    }
}

/** Destination des enregistrements d'une sauvegarde : l'archive, ou une empreinte de son contenu. */
fun interface RecordSink {
    fun write(record: DataRecord)
}

/**
 * Empreinte SHA-256 des enregistrements écrits, en-tête excepté (sa date change à chaque
 * sauvegarde) : la même tant que le contenu sauvegardé ne change pas.
 */
class FingerprintSink : RecordSink {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val length = ByteArray(Int.SIZE_BYTES)

    override fun write(record: DataRecord) {
        if (record !is HeaderRecord) add(RecordType.of(record), RecordType.encode(record))
    }

    /** Enregistrement de type [type] déjà encodé ([bytes]) par l'archive. */
    internal fun add(type: RecordType, bytes: ByteArray) {
        if (type == RecordType.HEADER) return
        digest.update(type.id.toByte())
        for (i in length.indices) length[i] = (bytes.size ushr (8 * (length.size - 1 - i))).toByte()
        digest.update(length)
        digest.update(bytes)
    }

    fun fingerprint(): String = Base64.getEncoder().encodeToString(digest.digest())
}

/** Écriture du contenu d'une archive ([DataArchive.write]) ; [close] libère le compresseur. */
class DataArchiveWriter internal constructor(
    private val encrypted: OutputStream,
    private val fingerprint: FingerprintSink?,
) : RecordSink, Closeable {
    private val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
    private val compressed = DeflaterOutputStream(encrypted, deflater, BUFFER)
    private val data = DataOutputStream(BufferedOutputStream(compressed, BUFFER))

    /** Enregistrements écrits, fin exceptée. */
    var records = 0
        private set

    override fun write(record: DataRecord) {
        val type = RecordType.of(record)
        val bytes = RecordType.encode(record)
        check(bytes.size <= DataArchive.MAX_RECORD_BYTES) { "Enregistrement trop volumineux : ${bytes.size} octets" }
        data.writeByte(type.id)
        data.writeInt(bytes.size)
        data.write(bytes)
        fingerprint?.add(type, bytes)
        records++
    }

    /** Écrit la fin du contenu et termine l'archive : dernier segment authentifié comme tel, flux fermé. */
    fun finish() {
        data.writeByte(RecordType.END)
        data.flush()
        compressed.finish()
        encrypted.close()
        deflater.end()
    }

    override fun close() {
        deflater.end()
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}

/** Enregistrement lu, pas encore décodé : la vérification d'une archive n'a pas à décoder ses visages. */
class RawRecord internal constructor(internal val type: RecordType?, private val bytes: ByteArray) {

    /**
     * Enregistrement décodé ; null pour un type inconnu (version ultérieure), à ignorer.
     *
     * @throws BackupArchiveException [Reason.CORRUPTED] : contenu authentifié mais illisible.
     */
    fun decode(): DataRecord? {
        val serializer = type?.serializer ?: return null
        return try {
            DataArchive.json.decodeFromString(serializer, bytes.toString(Charsets.UTF_8))
        } catch (error: IllegalArgumentException) {
            // SerializationException comprise ; aussi le Base64 d'une empreinte.
            throw BackupArchiveException(Reason.CORRUPTED, error)
        }
    }
}

/**
 * Lecture séquentielle du contenu d'une archive ([DataArchive.read]). Les enregistrements rendus
 * sont authentifiés, mais l'archive n'est vérifiée entière qu'une fois sa fin lue ([next] renvoie
 * null) ; [close] libère le décompresseur.
 */
class DataArchiveReader internal constructor(private val decrypted: InputStream, val version: Int) : Closeable {
    private val inflater = Inflater()
    private val inflating = InflaterInputStream(decrypted, inflater, BUFFER)
    private val data = DataInputStream(BufferedInputStream(inflating, BUFFER))
    private var ended = false

    /** Enregistrements lus, fin exceptée. */
    var records = 0
        private set

    /**
     * Enregistrement suivant, ou null une fois la fin lue et vérifiée : rien après elle, flux
     * chiffré authentifié jusqu'à son dernier segment.
     *
     * @throws BackupArchiveException [Reason.CORRUPTED] : contenu modifié, tronqué ou mal formé.
     */
    fun next(): RawRecord? {
        if (ended) return null
        val type = PasswordArchive.guarded { data.read() }
        if (type < 0) throw BackupArchiveException(Reason.CORRUPTED)
        if (type == RecordType.END) {
            verifyEnd()
            ended = true
            return null
        }
        val size = PasswordArchive.guarded { data.readInt() }
        if (size !in 1..DataArchive.MAX_RECORD_BYTES) throw BackupArchiveException(Reason.CORRUPTED)
        val bytes = ByteArray(size).also { PasswordArchive.guarded { data.readFully(it) } }
        records++
        return RawRecord(RecordType.of(type), bytes)
    }

    /** Après la fin : plus rien de décompressé, flux zlib terminé, flux chiffré lu jusqu'au bout. */
    private fun verifyEnd() {
        PasswordArchive.guarded {
            if (data.read() >= 0 || !inflater.finished() || inflater.remaining > 0 || decrypted.read() >= 0) {
                throw BackupArchiveException(Reason.CORRUPTED)
            }
        }
    }

    override fun close() {
        inflater.end()
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}
