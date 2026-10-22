package app.focale.backup

import app.focale.backup.BackupArchiveException.Reason
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.util.SecretBytes
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Sauvegarde chiffrée par mot de passe. Chaque format a sa signature, sa version et le domaine de
 * ses clés : l'écran de restauration de l'un reconnaît le fichier de l'autre et le refuse
 * ([BackupArchiveException.Reason.OTHER_BACKUP]), et un même mot de passe ne donne pas les mêmes
 * clés aux deux.
 */
enum class ArchiveFormat(signature: String, val version: Int, domain: String, internal val keyEncoding: Byte) {
    /** Sauvegarde du coffre-fort (`.fvault`, voir `VaultArchive`). */
    VAULT("FOCALE-VAULT", version = 1, domain = "focale-vault-backup", keyEncoding = 1),

    /** Sauvegarde des données de l'application (`.focale`, voir [DataArchive]) : signature suivie d'un octet nul. */
    DATA("FOCALE-DATA\u0000", version = 1, domain = "focale-data-backup", keyEncoding = 2),
    ;

    internal val magic: ByteArray = signature.toByteArray(Charsets.US_ASCII)
    internal val witnessInfo: ByteArray = "$domain/check".toByteArray(Charsets.US_ASCII)
    internal val streamInfo: ByteArray = "$domain/stream".toByteArray(Charsets.US_ASCII)

    init {
        require(magic.size == PasswordArchive.MAGIC_BYTES) { "Signature de ${magic.size} octets" }
    }
}

/**
 * Chiffrement des sauvegardes par un mot de passe plutôt que par la clé de l'appareil, pour
 * survivre à une désinstallation ou à un changement de téléphone : en-tête en clair, dérivation
 * du mot de passe et flux chiffré, communs au coffre (`.fvault`) et aux données (`.focale`). Le
 * contenu du flux est propre à chaque format.
 *
 * En-tête, en clair et données associées de tout le flux chiffré (entiers en big-endian) :
 *
 * ```
 *   signature     12 o   propre au format ([ArchiveFormat])
 *   version        1 o   du format
 *   dérivation     1 o   1 = PBKDF2-HMAC-SHA256, mot de passe normalisé NFC puis UTF-8
 *   itérations     4 o   600 000 à l'écriture, 10 000 000 au plus à la lecture
 *   sel            1 o   longueur (16), suivie du sel aléatoire
 *   chiffrement    1 o   1 = StreamingAead Tink AES-256-GCM-HKDF-SHA256, segments de 1 Mio
 *   témoin        32 o   HKDF-SHA256(clé dérivée, « <domaine>/check »)
 * Flux chiffré, clé HKDF-SHA256(clé dérivée, « <domaine>/stream »)
 * ```
 *
 * Chaque segment du flux est authentifié, numéroté, et le dernier est marqué comme tel : une
 * modification, une troncature, un ajout ou une permutation font échouer la lecture. Le témoin ne
 * facilite pas une attaque (le premier segment suffit déjà à tester un mot de passe) ; il
 * distingue un mot de passe faux d'un fichier endommagé, sans rien déchiffrer.
 *
 * Les clés tirées du mot de passe ([deriveKeys]) peuvent écrire plusieurs archives : celles d'une
 * sauvegarde automatique, qui partagent alors l'en-tête (sel, itérations, témoin) et se restaurent
 * toutes avec le mot de passe.
 */
object PasswordArchive {

    const val DEFAULT_ITERATIONS = 600_000

    /** Itérations acceptées à la lecture : un en-tête forgé ne peut pas bloquer l'appareil. */
    const val MAX_ITERATIONS = 10_000_000

    /** Type proposé au sélecteur de documents : contenu binaire opaque. */
    const val MIME_TYPE = "application/octet-stream"

    internal const val MAGIC_BYTES = 12
    internal const val KDF_PBKDF2_SHA256 = 1
    internal const val CIPHER_AES256_GCM_HKDF_1MIB = 1
    internal const val SALT_BYTES = 16
    internal const val WITNESS_BYTES = 32
    internal const val KEY_BYTES = 32

    private const val SEGMENT_BYTES = 1 shl 20
    private const val HKDF = "HMACSHA256"

    internal val streamParameters: AesGcmHkdfStreamingParameters = AesGcmHkdfStreamingParameters.builder()
        .setKeySizeBytes(KEY_BYTES)
        .setDerivedAesGcmKeySizeBytes(KEY_BYTES)
        .setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256)
        .setCiphertextSegmentSizeBytes(SEGMENT_BYTES)
        .build()

    /**
     * Tire de [password], avec un sel aléatoire, les clés de nouvelles archives du format [format]
     * (dérivation lente : [iterations] de PBKDF2). [password] n'est pas effacé, les clés le sont par
     * [ArchiveKeys.close].
     */
    fun deriveKeys(format: ArchiveFormat, password: CharArray, iterations: Int = DEFAULT_ITERATIONS): ArchiveKeys {
        require(iterations in 1..MAX_ITERATIONS)
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        return deriveKeys(format, password, salt, iterations)
    }

    /**
     * Écrit dans [output] l'en-tête des archives de [keys], puis renvoie le flux chiffré qui le suit.
     * Sa fermeture écrit le dernier segment, authentifié comme tel, et ferme [output] ; si une
     * exception interrompt l'écriture, l'archive reste inachevée et sera refusée à la lecture.
     *
     * Les mêmes clés peuvent écrire plusieurs archives sans s'affaiblir : pour chaque flux, Tink
     * tire un sel de 32 octets et un préfixe de nonce de 7 octets (`AesGcmHkdfStreaming`,
     * `randomSalt()` et `randomNonce()`), et chiffre ce flux avec sa propre clé AES-GCM,
     * HKDF(clé, sel, données associées) ; dans un flux, le nonce de chaque segment (préfixe,
     * numéro, marque du dernier) est unique. C'est l'usage prévu de `StreamingAead` (une clé
     * pour de nombreux fichiers, comme ceux du coffre) : deux archives, même d'en-tête
     * identique, ne partagent ni clé de segment ni nonce.
     */
    fun encrypt(output: OutputStream, keys: ArchiveKeys): OutputStream {
        val header = keys.header().encode()
        output.write(header)
        return keys.streamingAead().newEncryptingStream(output, header)
    }

    /**
     * Lit l'en-tête de l'archive [input], qui doit être du format [format] ; ses clés s'obtiennent
     * ensuite du mot de passe ([SealedArchive.keysFor]) ou sont déjà connues.
     *
     * @throws BackupArchiveException archive refusée, voir [BackupArchiveException.Reason].
     * @throws IOException lecture du support impossible.
     */
    fun read(input: InputStream, format: ArchiveFormat): SealedArchive {
        val source = SourceInput(input)
        return SealedArchive(source, readHeader(source, format))
    }

    /**
     * Lecture du flux chiffré : les erreurs du support remontent telles quelles, toutes les
     * autres (authentification refusée, fin prématurée…) signalent une archive endommagée.
     */
    internal inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (error: BackupArchiveException) {
        throw error
    } catch (error: IOException) {
        throw sourceError(error) ?: BackupArchiveException(Reason.CORRUPTED, error)
    } catch (error: GeneralSecurityException) {
        throw BackupArchiveException(Reason.CORRUPTED, error)
    }

    internal fun sourceError(error: Throwable): IOException? =
        generateSequence(error) { it.cause }.filterIsInstance<SourceException>().firstOrNull()?.cause

    private fun readHeader(source: InputStream, format: ArchiveFormat): ArchiveHeader {
        val data = DataInputStream(source)
        val magic = ByteArray(MAGIC_BYTES)
        var read = 0
        while (read < magic.size) {
            val count = guarded { data.read(magic, read, magic.size - read) }
            if (count < 0) break
            read += count
        }
        val found = ArchiveFormat.entries.takeIf { read == magic.size }?.firstOrNull { it.magic.contentEquals(magic) }
            ?: throw BackupArchiveException(Reason.NOT_AN_ARCHIVE)
        if (found != format) throw BackupArchiveException(Reason.OTHER_BACKUP)
        return guarded {
            val version = data.readUnsignedByte()
            if (version !in 1..format.version) throw BackupArchiveException(if (version > format.version) Reason.UNSUPPORTED else Reason.CORRUPTED)
            if (data.readUnsignedByte() != KDF_PBKDF2_SHA256) throw BackupArchiveException(Reason.UNSUPPORTED)
            val iterations = data.readInt()
            if (iterations <= 0) throw BackupArchiveException(Reason.CORRUPTED)
            if (iterations > MAX_ITERATIONS) throw BackupArchiveException(Reason.UNSUPPORTED)
            val salt = ByteArray(data.readUnsignedByte())
            if (salt.size < SALT_BYTES) throw BackupArchiveException(Reason.CORRUPTED)
            data.readFully(salt)
            if (data.readUnsignedByte() != CIPHER_AES256_GCM_HKDF_1MIB) throw BackupArchiveException(Reason.UNSUPPORTED)
            val witness = ByteArray(WITNESS_BYTES).also { data.readFully(it) }
            ArchiveHeader(format, version, iterations, salt, witness)
        }
    }

    /** Témoin du mot de passe et clé du flux, tirés de la même clé dérivée, effacée aussitôt. */
    internal fun deriveKeys(format: ArchiveFormat, password: CharArray, salt: ByteArray, iterations: Int): ArchiveKeys {
        val master = BackupPassword.derive(password, salt, iterations)
        try {
            return ArchiveKeys(
                format = format,
                iterations = iterations,
                salt = salt.copyOf(),
                witness = Hkdf.computeHkdf(HKDF, master, ByteArray(0), format.witnessInfo, WITNESS_BYTES),
                streamKey = Hkdf.computeHkdf(HKDF, master, ByteArray(0), format.streamInfo, KEY_BYTES),
            )
        } finally {
            master.fill(0)
        }
    }
}

/**
 * Archive dont l'en-tête est lu ([PasswordArchive.read]), à déchiffrer avec les clés de son mot de
 * passe ([keysFor]) ou des clés déjà connues : celles d'une première lecture du même fichier, ou
 * celles qui ont écrit les sauvegardes automatiques.
 */
class SealedArchive internal constructor(private val source: InputStream, private val header: ArchiveHeader) {

    /** Version du format de l'archive : la plus récente connue, ou une précédente. */
    val version: Int get() = header.version

    /**
     * Clés de l'archive tirées de [password] (dérivation lente), à effacer après usage ; [password]
     * n'est pas effacé.
     *
     * @throws BackupArchiveException [BackupArchiveException.Reason.WRONG_PASSWORD] : mot de passe
     *   incorrect, ou paramètres de dérivation altérés.
     */
    fun keysFor(password: CharArray): ArchiveKeys {
        val keys = PasswordArchive.deriveKeys(header.format, password, header.salt, header.iterations)
        if (!keys.matches(header)) {
            keys.close()
            throw BackupArchiveException(Reason.WRONG_PASSWORD)
        }
        return keys
    }

    /**
     * Flux déchiffré du contenu, à lire sous [PasswordArchive.guarded] ; [keys] ne sont pas effacées
     * et peuvent l'être aussitôt.
     *
     * @throws BackupArchiveException [BackupArchiveException.Reason.WRONG_PASSWORD] : clés d'une
     *   autre archive (autre sel, autre mot de passe).
     */
    fun decrypt(keys: ArchiveKeys): InputStream {
        if (!keys.matches(header)) throw BackupArchiveException(Reason.WRONG_PASSWORD)
        val aead = keys.streamingAead()
        return PasswordArchive.guarded { aead.newDecryptingStream(source, header.encode()) }
    }
}

/** Archive refusée à la lecture. */
class BackupArchiveException(val reason: Reason, cause: Throwable? = null) :
    IOException("Archive refusée : $reason", cause) {

    enum class Reason {
        /** Pas une sauvegarde de Focale (signature absente). */
        NOT_AN_ARCHIVE,

        /** L'autre sauvegarde de Focale : celle des données à la place de celle du coffre, ou l'inverse. */
        OTHER_BACKUP,

        /** Version, algorithme ou paramètres inconnus : sauvegarde d'une version plus récente. */
        UNSUPPORTED,

        /** Mot de passe incorrect, ou paramètres de dérivation altérés. */
        WRONG_PASSWORD,

        /** Contenu modifié, tronqué ou mal formé. */
        CORRUPTED,
    }
}

/** En-tête en clair ; son encodage, canonique, sert de données associées au flux chiffré. */
internal class ArchiveHeader(
    val format: ArchiveFormat,
    val version: Int,
    val iterations: Int,
    val salt: ByteArray,
    val witness: ByteArray,
) {
    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            write(format.magic)
            writeByte(version)
            writeByte(PasswordArchive.KDF_PBKDF2_SHA256)
            writeInt(iterations)
            writeByte(salt.size)
            write(salt)
            writeByte(PasswordArchive.CIPHER_AES256_GCM_HKDF_1MIB)
            write(witness)
        }
        return bytes.toByteArray()
    }
}

/**
 * Clés d'archive tirées d'un mot de passe ([PasswordArchive.deriveKeys]) : format, paramètres de
 * l'en-tête (itérations, sel, témoin) et clé du flux chiffré. Elles écrivent des archives que le
 * mot de passe restaure, sans lui ; ni lui ni la clé dont elles sont tirées n'en sont retrouvables
 * (PBKDF2 puis HKDF). [close] efface la clé du flux, et les clés ne servent plus ; les copies
 * qu'en fait Tink pour un flux, hors de portée, disparaissent avec lui.
 */
class ArchiveKeys internal constructor(
    val format: ArchiveFormat,
    internal val iterations: Int,
    internal val salt: ByteArray,
    /** Témoin du mot de passe : public, il figure dans l'en-tête de chaque archive. */
    internal val witness: ByteArray,
    private val streamKey: ByteArray,
) : Closeable {

    @Volatile
    private var closed = false

    internal fun header(): ArchiveHeader = ArchiveHeader(format, format.version, iterations, salt, witness)

    /** Clés de l'archive d'en-tête [header] : même format, mêmes paramètres, même témoin. */
    internal fun matches(header: ArchiveHeader): Boolean =
        header.format == format && header.iterations == iterations && header.salt.contentEquals(salt) &&
            MessageDigest.isEqual(header.witness, witness)

    /** Primitive du flux chiffré ; refusée une fois les clés effacées (la clé serait nulle). */
    internal fun streamingAead(): StreamingAead {
        check(!closed) { "Clés effacées" }
        val key = AesGcmHkdfStreamingKey.create(
            PasswordArchive.streamParameters,
            SecretBytes.copyFrom(streamKey, InsecureSecretKeyAccess.get()),
        )
        return AesGcmHkdfStreaming.create(key)
    }

    /**
     * Forme sérialisée, à ne conserver que chiffrée et à effacer après usage : format (1 o, propre à
     * [format]), itérations (4 o), longueur du sel (1 o) et sel, témoin (32 o), clé du flux (32 o).
     */
    fun encode(): ByteArray {
        check(!closed) { "Clés effacées" }
        // Tampon de la taille exacte : aucune copie de la clé ne reste ailleurs que dans le résultat.
        return ByteBuffer.allocate(1 + 4 + 1 + salt.size + witness.size + streamKey.size)
            .put(format.keyEncoding)
            .putInt(iterations)
            .put(salt.size.toByte())
            .put(salt)
            .put(witness)
            .put(streamKey)
            .array()
    }

    override fun close() {
        closed = true
        streamKey.fill(0)
    }

    companion object {
        /**
         * Clés du format [format] lues de [bytes], forme de [encode] ; [bytes] n'est pas effacé.
         *
         * @throws GeneralSecurityException forme inconnue ou d'un autre format, tronquée, ou
         *   paramètres invalides.
         */
        fun decode(format: ArchiveFormat, bytes: ByteArray): ArchiveKeys {
            val buffer = ByteBuffer.wrap(bytes)
            try {
                if (buffer.get() != format.keyEncoding) throw GeneralSecurityException("Clés d'un format inconnu")
                val iterations = buffer.getInt()
                val salt = ByteArray(buffer.get().toInt() and 0xFF).also { buffer.get(it) }
                val witness = ByteArray(PasswordArchive.WITNESS_BYTES).also { buffer.get(it) }
                val streamKey = ByteArray(PasswordArchive.KEY_BYTES).also { buffer.get(it) }
                if (iterations !in 1..PasswordArchive.MAX_ITERATIONS || salt.size < PasswordArchive.SALT_BYTES || buffer.hasRemaining()) {
                    streamKey.fill(0)
                    throw GeneralSecurityException("Clés invalides")
                }
                return ArchiveKeys(format, iterations, salt, witness, streamKey)
            } catch (error: BufferUnderflowException) {
                throw GeneralSecurityException("Clés tronquées", error)
            }
        }
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
