package app.focale.vault

import app.focale.vault.VaultArchiveException.Reason
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.util.SecretBytes
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Archive de sauvegarde du coffre (`.fvault`) : tout son contenu, chiffré par un mot de
 * passe plutôt que par la clé de l'appareil, pour survivre à une désinstallation ou à un
 * changement de téléphone.
 *
 * Format, version 1 (entiers en big-endian) :
 *
 * ```
 * En-tête en clair, données associées de tout le flux chiffré
 *   signature     12 o   « FOCALE-VAULT »
 *   version        1 o   1
 *   dérivation     1 o   1 = PBKDF2-HMAC-SHA256, mot de passe normalisé NFC puis UTF-8
 *   itérations     4 o   600 000 à l'écriture, 10 000 000 au plus à la lecture
 *   sel            1 o   longueur (16), suivie du sel aléatoire
 *   chiffrement    1 o   1 = StreamingAead Tink AES-256-GCM-HKDF-SHA256, segments de 1 Mio
 *   témoin        32 o   HKDF-SHA256(clé dérivée, « focale-vault-backup/check »)
 * Flux chiffré, clé HKDF-SHA256(clé dérivée, « focale-vault-backup/stream »)
 *   manifeste      4 o   longueur, suivie du JSON UTF-8 de [ArchiveManifest] : date de
 *                        création puis, par élément, nom, type MIME, dates, dossier
 *                        d'origine, dimensions, durée, taille, présence d'une miniature,
 *                        album ; enfin les albums (champs facultatifs : absents des
 *                        sauvegardes antérieures, ignorés par les versions qui les précèdent)
 *   éléments             dans l'ordre du manifeste, le média puis la miniature, en blobs
 *   fin du flux
 *
 * blob := { longueur (4 o, 1 à 1 Mio) · octets }* · fin (4 o)
 *         fin 0 : contenu complet ; fin -1 : source illisible à l'export, blob à ignorer
 * ```
 *
 * Chaque segment du flux est authentifié, numéroté, et le dernier est marqué comme tel :
 * une modification, une troncature, un ajout ou une permutation font échouer la lecture.
 * Le témoin ne facilite pas une attaque (le premier segment suffit déjà à tester un mot
 * de passe) ; il distingue un mot de passe faux d'un fichier endommagé, sans rien
 * déchiffrer. L'archive s'écrit et se lit en flux : la mémoire utilisée ne dépend pas de
 * sa taille, et sa taille d'aucune limite (vidéos de plusieurs Go).
 */
object VaultArchive {

    const val VERSION = 1
    const val DEFAULT_ITERATIONS = 600_000
    /** Itérations acceptées à la lecture : un en-tête forgé ne peut pas bloquer l'appareil. */
    const val MAX_ITERATIONS = 10_000_000
    /** Type proposé au sélecteur de documents : contenu binaire opaque. */
    const val MIME_TYPE = "application/octet-stream"

    internal val MAGIC = "FOCALE-VAULT".toByteArray(Charsets.US_ASCII)
    internal const val KDF_PBKDF2_SHA256 = 1
    internal const val CIPHER_AES256_GCM_HKDF_1MIB = 1
    internal const val SALT_BYTES = 16
    internal const val WITNESS_BYTES = 32
    internal const val BLOB_END = 0
    internal const val BLOB_ABANDONED = -1
    /** Taille des morceaux écrits ; la lecture accepte jusqu'à [MAX_CHUNK_BYTES]. */
    internal const val CHUNK_BYTES = 256 * 1024
    internal const val MAX_CHUNK_BYTES = 1 shl 20
    internal const val MAX_MANIFEST_BYTES = 32 shl 20

    private const val KEY_BYTES = 32
    private const val SEGMENT_BYTES = 1 shl 20
    private const val BUFFER = 64 * 1024
    private const val HKDF = "HMACSHA256"
    private val WITNESS_INFO = "focale-vault-backup/check".toByteArray(Charsets.US_ASCII)
    private val STREAM_INFO = "focale-vault-backup/stream".toByteArray(Charsets.US_ASCII)

    private val streamParameters: AesGcmHkdfStreamingParameters = AesGcmHkdfStreamingParameters.builder()
        .setKeySizeBytes(KEY_BYTES)
        .setDerivedAesGcmKeySizeBytes(KEY_BYTES)
        .setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256)
        .setCiphertextSegmentSizeBytes(SEGMENT_BYTES)
        .build()

    /** Champs inconnus ignorés : une version ultérieure peut compléter le manifeste. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Écrit une archive dans [output], fermé une fois l'archive terminée. [content] y écrit,
     * dans l'ordre du manifeste, le média puis la miniature de chaque élément. Si une
     * exception interrompt l'écriture, l'archive reste inachevée et sera refusée à la
     * lecture.
     *
     * @param onBytes appelé après chaque morceau de contenu écrit (progression, annulation).
     */
    fun write(
        output: OutputStream,
        password: CharArray,
        manifest: ArchiveManifest,
        iterations: Int = DEFAULT_ITERATIONS,
        onBytes: (Int) -> Unit = {},
        content: (VaultArchiveWriter) -> Unit,
    ) {
        require(iterations in 1..MAX_ITERATIONS)
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val keys = deriveKeys(password, salt, iterations)
        val header = ArchiveHeader(iterations, salt, keys.witness).encode()
        output.write(header)
        val data = DataOutputStream(BufferedOutputStream(keys.stream.newEncryptingStream(output, header), BUFFER))
        val description = json.encodeToString(ArchiveManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)
        check(description.size <= MAX_MANIFEST_BYTES) { "Manifeste trop volumineux" }
        data.writeInt(description.size)
        data.write(description)
        val writer = VaultArchiveWriter(data, onBytes)
        content(writer)
        check(writer.blobs == manifest.items.size * 2) { "Archive incomplète : ${writer.blobs} blobs" }
        // La fermeture écrit le dernier segment, authentifié comme tel.
        data.close()
    }

    /**
     * Ouvre l'archive lue dans [input] : en-tête, mot de passe puis manifeste. Le contenu se
     * lit ensuite avec le [VaultArchiveReader] renvoyé.
     *
     * @throws VaultArchiveException archive refusée, voir [VaultArchiveException.Reason].
     * @throws IOException lecture du support impossible.
     */
    fun open(input: InputStream, password: CharArray): VaultArchiveReader {
        val source = SourceInput(input)
        val header = readHeader(source)
        val keys = deriveKeys(password, header.salt, header.iterations)
        if (!MessageDigest.isEqual(keys.witness, header.witness)) throw VaultArchiveException(Reason.WRONG_PASSWORD)
        val data = guarded {
            DataInputStream(BufferedInputStream(keys.stream.newDecryptingStream(source, header.encode()), BUFFER))
        }
        val size = guarded { data.readInt() }
        if (size !in 1..MAX_MANIFEST_BYTES) throw VaultArchiveException(Reason.CORRUPTED)
        val description = ByteArray(size).also { guarded { data.readFully(it) } }
        val manifest = try {
            json.decodeFromString(ArchiveManifest.serializer(), description.toString(Charsets.UTF_8))
        } catch (error: IllegalArgumentException) {
            // SerializationException comprise : contenu authentifié mais illisible.
            throw VaultArchiveException(Reason.CORRUPTED, error)
        }
        if (!manifest.isWellFormed()) throw VaultArchiveException(Reason.CORRUPTED)
        return VaultArchiveReader(data, manifest)
    }

    /**
     * Lecture du flux chiffré : les erreurs du support remontent telles quelles, toutes les
     * autres (authentification refusée, fin prématurée…) signalent une archive endommagée.
     */
    internal inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (error: VaultArchiveException) {
        throw error
    } catch (error: IOException) {
        throw sourceError(error) ?: VaultArchiveException(Reason.CORRUPTED, error)
    } catch (error: GeneralSecurityException) {
        throw VaultArchiveException(Reason.CORRUPTED, error)
    }

    internal fun sourceError(error: Throwable): IOException? =
        generateSequence(error) { it.cause }.filterIsInstance<SourceException>().firstOrNull()?.cause

    private fun readHeader(source: InputStream): ArchiveHeader {
        val data = DataInputStream(source)
        val magic = ByteArray(MAGIC.size)
        var read = 0
        while (read < magic.size) {
            val count = guarded { data.read(magic, read, magic.size - read) }
            if (count < 0) break
            read += count
        }
        if (read < magic.size || !magic.contentEquals(MAGIC)) throw VaultArchiveException(Reason.NOT_AN_ARCHIVE)
        return guarded {
            val version = data.readUnsignedByte()
            if (version != VERSION) throw VaultArchiveException(if (version > VERSION) Reason.UNSUPPORTED else Reason.CORRUPTED)
            if (data.readUnsignedByte() != KDF_PBKDF2_SHA256) throw VaultArchiveException(Reason.UNSUPPORTED)
            val iterations = data.readInt()
            if (iterations <= 0) throw VaultArchiveException(Reason.CORRUPTED)
            if (iterations > MAX_ITERATIONS) throw VaultArchiveException(Reason.UNSUPPORTED)
            val salt = ByteArray(data.readUnsignedByte())
            if (salt.size < SALT_BYTES) throw VaultArchiveException(Reason.CORRUPTED)
            data.readFully(salt)
            if (data.readUnsignedByte() != CIPHER_AES256_GCM_HKDF_1MIB) throw VaultArchiveException(Reason.UNSUPPORTED)
            val witness = ByteArray(WITNESS_BYTES).also { data.readFully(it) }
            ArchiveHeader(iterations, salt, witness)
        }
    }

    /** Témoin du mot de passe et primitive du flux, tirés de la même clé dérivée. */
    private class Keys(val witness: ByteArray, val stream: StreamingAead)

    private fun deriveKeys(password: CharArray, salt: ByteArray, iterations: Int): Keys {
        val master = BackupPassword.derive(password, salt, iterations)
        val streamKey = Hkdf.computeHkdf(HKDF, master, ByteArray(0), STREAM_INFO, KEY_BYTES)
        try {
            val witness = Hkdf.computeHkdf(HKDF, master, ByteArray(0), WITNESS_INFO, WITNESS_BYTES)
            val key = AesGcmHkdfStreamingKey.create(
                streamParameters,
                SecretBytes.copyFrom(streamKey, InsecureSecretKeyAccess.get()),
            )
            return Keys(witness, AesGcmHkdfStreaming.create(key))
        } finally {
            master.fill(0)
            streamKey.fill(0)
        }
    }

    private fun ArchiveManifest.isWellFormed(): Boolean = items.all { entry ->
        entry.name.isNotBlank() && entry.mimeType.isNotBlank() &&
            entry.size >= 0 && entry.durationMs >= 0 && entry.width >= 0 && entry.height >= 0
    } && albums.all { it.name.isNotBlank() } && albums.distinctBy { it.id }.size == albums.size
}

/** Description de l'archive, en tête du flux chiffré. */
@Serializable
data class ArchiveManifest(
    /** Création de la sauvegarde (ms depuis l'époque). */
    val createdAt: Long,
    val items: List<ArchiveEntry>,
    /** Albums du coffre ; aucun dans les sauvegardes antérieures aux albums. */
    val albums: List<ArchiveAlbum> = emptyList(),
) {
    /** Taille cumulée des médias en clair. */
    val totalBytes: Long get() = items.sumOf { it.size }
}

/** Album du coffre, recréé à la restauration. */
@Serializable
data class ArchiveAlbum(
    /** Identifiant propre à l'archive, auquel renvoie [ArchiveEntry.album]. */
    val id: Long,
    val name: String,
    val createdAt: Long,
    /** Dossier d'origine d'un album-dossier : `BUCKET_ID` et `RELATIVE_PATH`. */
    val sourceBucketId: Long? = null,
    val sourcePath: String? = null,
    /** Dernier import depuis le dossier d'origine (ms). */
    val lastImportAt: Long? = null,
)

/** Métadonnées d'un élément du coffre, telles que restaurées. */
@Serializable
data class ArchiveEntry(
    val name: String,
    val mimeType: String,
    val video: Boolean,
    /** Dossier d'origine (`RELATIVE_PATH`), pour une restauration ultérieure dans la galerie. */
    val originalPath: String,
    val dateTaken: Long,
    val addedAt: Long,
    /** Taille du média en clair, en octets. */
    val size: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val hasThumbnail: Boolean,
    /** Album de l'élément ([ArchiveAlbum.id]), null hors album ou dans une sauvegarde antérieure. */
    val album: Long? = null,
)

/** Archive refusée à la lecture. */
class VaultArchiveException(val reason: Reason, cause: Throwable? = null) :
    IOException("Archive refusée : $reason", cause) {

    enum class Reason {
        /** Pas une sauvegarde du coffre (signature absente). */
        NOT_AN_ARCHIVE,
        /** Version, algorithme ou paramètres inconnus : sauvegarde d'une version plus récente. */
        UNSUPPORTED,
        /** Mot de passe incorrect, ou paramètres de dérivation altérés. */
        WRONG_PASSWORD,
        /** Contenu modifié, tronqué ou mal formé. */
        CORRUPTED,
    }
}

/** Écriture du contenu d'une archive, voir [VaultArchive.write]. */
class VaultArchiveWriter internal constructor(
    private val output: DataOutputStream,
    private val onBytes: (Int) -> Unit,
) {
    internal var blobs = 0
        private set
    private val buffer = ByteArray(VaultArchive.CHUNK_BYTES)

    /**
     * Écrit le flux ouvert par [open] (vide si null) comme blob suivant. Une source
     * illisible, dès l'ouverture ou en cours de lecture, termine le blob comme abandonné :
     * l'archive reste valide et l'élément sera ignoré à la restauration. Les erreurs
     * d'écriture, elles, remontent.
     *
     * @return faux si la source était illisible.
     */
    fun blob(open: () -> InputStream?): Boolean {
        blobs++
        val source = try {
            open()
        } catch (error: IOException) {
            return abandon()
        } catch (error: GeneralSecurityException) {
            return abandon()
        }
        source?.use { input ->
            while (true) {
                val read = try {
                    input.read(buffer)
                } catch (error: IOException) {
                    return abandon()
                }
                if (read < 0) break
                if (read > 0) {
                    output.writeInt(read)
                    output.write(buffer, 0, read)
                    onBytes(read)
                }
            }
        }
        output.writeInt(VaultArchive.BLOB_END)
        return true
    }

    private fun abandon(): Boolean {
        output.writeInt(VaultArchive.BLOB_ABANDONED)
        return false
    }
}

/**
 * Lecture séquentielle d'une archive ouverte par [VaultArchive.open]. Les octets rendus
 * sont authentifiés, mais l'archive n'est vérifiée entière (fin comprise) qu'une fois
 * [finish] passé : rien ne doit être conservé avant.
 */
class VaultArchiveReader internal constructor(
    private val input: DataInputStream,
    val manifest: ArchiveManifest,
) {
    /** Appelé après chaque lecture de contenu, avec le nombre d'octets lus (progression, annulation). */
    var onBytes: (Int) -> Unit = {}

    private var blobs = 0
    private var current: Blob? = null
    private val scratch = ByteArray(VaultArchive.CHUNK_BYTES)

    /** Blob suivant : le média puis la miniature de chaque élément ; le précédent est lu jusqu'au bout. */
    fun nextBlob(): Blob {
        current?.drain()
        check(blobs < manifest.items.size * 2) { "Fin du contenu" }
        blobs++
        return Blob().also { current = it }
    }

    /** Lit le reste du contenu, puis vérifie que l'archive se termine là, authentifiée jusqu'au bout. */
    fun finish() {
        while (blobs < manifest.items.size * 2) nextBlob()
        current?.drain()
        if (VaultArchive.guarded { input.read() } >= 0) throw VaultArchiveException(Reason.CORRUPTED)
    }

    /** Contenu en clair d'un blob, rendu à mesure de la lecture. */
    inner class Blob internal constructor() : InputStream() {
        private var remaining = 0
        private var ended = false

        /** Une fois la fin lue : vrai si le blob est entier, faux s'il a été abandonné à l'écriture. */
        var complete = false
            private set

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (remaining == 0) {
                if (ended) return -1
                when (val next = VaultArchive.guarded { input.readInt() }) {
                    VaultArchive.BLOB_END -> {
                        ended = true
                        complete = true
                    }
                    VaultArchive.BLOB_ABANDONED -> ended = true
                    in 1..VaultArchive.MAX_CHUNK_BYTES -> remaining = next
                    else -> throw VaultArchiveException(Reason.CORRUPTED)
                }
            }
            val read = VaultArchive.guarded { input.read(b, off, minOf(len, remaining)) }
            if (read < 0) throw VaultArchiveException(Reason.CORRUPTED)
            remaining -= read
            onBytes(read)
            return read
        }

        /** Lit le reste du blob sans le garder : l'authentification du flux se poursuit. */
        internal fun drain() {
            do {
                val read = read(scratch, 0, scratch.size)
            } while (read >= 0)
        }
    }
}

/** En-tête en clair ; son encodage, canonique, sert de données associées au flux chiffré. */
internal class ArchiveHeader(val iterations: Int, val salt: ByteArray, val witness: ByteArray) {
    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            write(VaultArchive.MAGIC)
            writeByte(VaultArchive.VERSION)
            writeByte(VaultArchive.KDF_PBKDF2_SHA256)
            writeInt(iterations)
            writeByte(salt.size)
            write(salt)
            writeByte(VaultArchive.CIPHER_AES256_GCM_HKDF_1MIB)
            write(witness)
        }
        return bytes.toByteArray()
    }
}

/** Erreur du support lu, transportée telle quelle à travers le déchiffrement. */
internal class SourceException(override val cause: IOException) : IOException(cause)

/** Marque les erreurs du support pour les distinguer d'une archive endommagée. */
private class SourceInput(input: InputStream) : FilterInputStream(input) {
    override fun read(): Int = marked { super.read() }
    override fun read(b: ByteArray, off: Int, len: Int): Int = marked { super.read(b, off, len) }
    override fun skip(n: Long): Long = marked { super.skip(n) }
    override fun available(): Int = marked { super.available() }

    private inline fun <T> marked(block: () -> T): T = try {
        block()
    } catch (error: IOException) {
        throw SourceException(error)
    }
}
