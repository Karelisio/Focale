package app.focale.vault

import app.focale.backup.ArchiveFormat
import app.focale.backup.ArchiveKeys
import app.focale.backup.BackupArchiveException
import app.focale.backup.BackupArchiveException.Reason
import app.focale.backup.PasswordArchive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
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
 * En-tête en clair de [PasswordArchive] : signature « FOCALE-VAULT », domaine des clés
 * « focale-vault-backup » ([ArchiveFormat.VAULT])
 * Flux chiffré
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
 * L'en-tête, la dérivation du mot de passe et le flux chiffré sont ceux de toutes les
 * sauvegardes de Focale ([PasswordArchive]) ; une sauvegarde des données (`.focale`) est
 * reconnue et refusée ([Reason.OTHER_BACKUP]). L'archive s'écrit et se lit en flux : la
 * mémoire utilisée ne dépend pas de sa taille, et sa taille d'aucune limite (vidéos de
 * plusieurs Go).
 *
 * Les clés tirées du mot de passe ([deriveKeys]) peuvent écrire plusieurs archives : celles de
 * la sauvegarde automatique, qui partagent alors l'en-tête (sel, itérations, témoin) et se
 * restaurent toutes avec le mot de passe.
 */
object VaultArchive {

    const val VERSION = 1
    const val DEFAULT_ITERATIONS = PasswordArchive.DEFAULT_ITERATIONS
    /** Itérations acceptées à la lecture : un en-tête forgé ne peut pas bloquer l'appareil. */
    const val MAX_ITERATIONS = PasswordArchive.MAX_ITERATIONS
    /** Type proposé au sélecteur de documents : contenu binaire opaque. */
    const val MIME_TYPE = PasswordArchive.MIME_TYPE

    internal val MAGIC: ByteArray get() = ArchiveFormat.VAULT.magic
    internal const val SALT_BYTES = PasswordArchive.SALT_BYTES
    internal const val WITNESS_BYTES = PasswordArchive.WITNESS_BYTES
    internal const val KEY_BYTES = PasswordArchive.KEY_BYTES
    internal const val BLOB_END = 0
    internal const val BLOB_ABANDONED = -1
    /** Taille des morceaux écrits ; la lecture accepte jusqu'à [MAX_CHUNK_BYTES]. */
    internal const val CHUNK_BYTES = 256 * 1024
    internal const val MAX_CHUNK_BYTES = 1 shl 20
    internal const val MAX_MANIFEST_BYTES = 32 shl 20

    private const val BUFFER = 64 * 1024

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
        deriveKeys(password, iterations).use { keys -> write(output, keys, manifest, onBytes, content) }
    }

    /**
     * Écrit une archive avec des clés déjà tirées du mot de passe ([deriveKeys]), sans lui :
     * même format, même en-tête (itérations, sel, témoin), restaurable avec le mot de passe par
     * [open]. Pour le reste, comme [write] avec mot de passe ; [keys] ne sont pas effacées
     * (réutiliser les clés est sûr, voir [PasswordArchive.encrypt]).
     */
    fun write(
        output: OutputStream,
        keys: ArchiveKeys,
        manifest: ArchiveManifest,
        onBytes: (Int) -> Unit = {},
        content: (VaultArchiveWriter) -> Unit,
    ) {
        require(keys.format == ArchiveFormat.VAULT) { "Clés d'une autre sauvegarde" }
        val data = DataOutputStream(BufferedOutputStream(PasswordArchive.encrypt(output, keys), BUFFER))
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
     * Tire de [password], avec un sel aléatoire, les clés de nouvelles archives (dérivation lente :
     * [iterations] de PBKDF2). [password] n'est pas effacé, les clés le sont par
     * [ArchiveKeys.close].
     */
    fun deriveKeys(password: CharArray, iterations: Int = DEFAULT_ITERATIONS): ArchiveKeys =
        PasswordArchive.deriveKeys(ArchiveFormat.VAULT, password, iterations)

    /**
     * Ouvre l'archive lue dans [input] : en-tête, mot de passe puis manifeste. Le contenu se
     * lit ensuite avec le [VaultArchiveReader] renvoyé.
     *
     * @throws BackupArchiveException archive refusée, voir [BackupArchiveException.Reason].
     * @throws IOException lecture du support impossible.
     */
    fun open(input: InputStream, password: CharArray): VaultArchiveReader {
        val sealed = PasswordArchive.read(input, ArchiveFormat.VAULT)
        val stream = sealed.keysFor(password).use(sealed::decrypt)
        val data = DataInputStream(BufferedInputStream(stream, BUFFER))
        val size = PasswordArchive.guarded { data.readInt() }
        if (size !in 1..MAX_MANIFEST_BYTES) throw BackupArchiveException(Reason.CORRUPTED)
        val description = ByteArray(size).also { PasswordArchive.guarded { data.readFully(it) } }
        val manifest = try {
            json.decodeFromString(ArchiveManifest.serializer(), description.toString(Charsets.UTF_8))
        } catch (error: IllegalArgumentException) {
            // SerializationException comprise : contenu authentifié mais illisible.
            throw BackupArchiveException(Reason.CORRUPTED, error)
        }
        if (!manifest.isWellFormed()) throw BackupArchiveException(Reason.CORRUPTED)
        // Album sans nom (dossier racine…) : renommé plutôt que de refuser toute la sauvegarde.
        val albums = manifest.albums.map { it.copy(name = VaultAlbumNames.of(it.name)) }
        return VaultArchiveReader(data, manifest.copy(albums = albums))
    }

    private fun ArchiveManifest.isWellFormed(): Boolean = items.all { entry ->
        entry.name.isNotBlank() && entry.mimeType.isNotBlank() &&
            entry.size >= 0 && entry.durationMs >= 0 && entry.width >= 0 && entry.height >= 0
    } && albums.distinctBy { it.id }.size == albums.size
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
        if (PasswordArchive.guarded { input.read() } >= 0) throw BackupArchiveException(Reason.CORRUPTED)
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
                when (val next = PasswordArchive.guarded { input.readInt() }) {
                    VaultArchive.BLOB_END -> {
                        ended = true
                        complete = true
                    }
                    VaultArchive.BLOB_ABANDONED -> ended = true
                    in 1..VaultArchive.MAX_CHUNK_BYTES -> remaining = next
                    else -> throw BackupArchiveException(Reason.CORRUPTED)
                }
            }
            val read = PasswordArchive.guarded { input.read(b, off, minOf(len, remaining)) }
            if (read < 0) throw BackupArchiveException(Reason.CORRUPTED)
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

/** Nom d'un album du coffre : jamais vide (dossier racine, nom fait d'espaces…). */
object VaultAlbumNames {
    const val FALLBACK = "Album"

    fun of(name: String): String = name.trim().ifEmpty { FALLBACK }
}
