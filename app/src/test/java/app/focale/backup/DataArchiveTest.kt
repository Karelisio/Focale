package app.focale.backup

import app.focale.backup.BackupArchiveException.Reason
import app.focale.vault.ArchiveManifest
import app.focale.vault.VaultArchive
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.util.zip.DeflaterOutputStream
import kotlin.random.Random
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test

/**
 * Format des sauvegardes des données (`.focale`) : aller-retour de chaque enregistrement, mot de
 * passe faux, fichiers tronqués ou modifiés, version ultérieure, et refus croisé avec les
 * sauvegardes du coffre (`.fvault`).
 */
class DataArchiveTest {

    // Itérations réduites : le coût de la dérivation n'est pas ce qui est testé ici.
    private val iterations = 1_000
    private val password = "cheval correct agrafe".toCharArray()
    private val random = Random(11)

    private val records: List<DataRecord> = listOf(
        HeaderRecord(createdAt = 1_760_000_000_000, appVersion = "1.9.0", faceVersion = 1),
        SettingsRecord(
            values = mapOf("theme_mode" to JsonPrimitive("DARK"), "grid_columns" to JsonPrimitive(5), "amoled" to JsonPrimitive(true)),
            keys = listOf("theme_mode", "grid_columns", "amoled", "show_memories"),
        ),
        HiddenFolderRecord(bucketId = -1_313_584_517, path = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", name = "WhatsApp Images"),
        HiddenFolderRecord(bucketId = 42),
        MediaRecord(path = "DCIM/Camera/", name = "IMG_20240714_183012.jpg", size = 3_456_789, taken = 1_720_974_612_000),
        MediaRecord(path = "Pictures/Vacances d'été/", name = "Plage « 1 ».jpg", size = 12, taken = 0),
        MediaDateRecord(media = 1, taken = 1_600_000_000_000),
        FavoriteRecord(media = 0, addedAt = 1_750_000_000_000),
        AlbumRecord(id = 7, name = "Vacances", createdAt = 1_740_000_000_000, cover = 1),
        AlbumRecord(id = 8, name = "Sans couverture", createdAt = 1_740_000_000_001),
        AlbumMemberRecord(album = 7, media = 0, addedAt = 1_741_000_000_000),
        PersonRecord(id = 3, name = "Alice", pinned = true, createdAt = 1_730_000_000_000, cover = 12, photos = 8),
        PersonRecord(id = 4, hidden = true, createdAt = 1_730_000_000_001, photos = 2),
        RefusalRecord(low = 3, high = 4),
        FaceScanRecord(media = 0, version = 1, faces = 2),
        FaceScanRecord(media = 1, version = 1, faces = 0, current = false),
        FaceRecord(
            id = 12, media = 0, person = 3, left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.45f,
            embedding = random.nextBytes(512), quality = 0.87f,
        ),
        FaceRecord(
            id = 13, media = 0, left = 0.5f, top = 0.5f, right = 0.6f, bottom = 0.6f,
            embedding = random.nextBytes(512), quality = 0.4f, manual = true,
        ),
        SearchRecord(query = "plage", searchedAt = 1_760_000_000_000),
        SearchRecord(query = """{"chips":[{"kind":"person","id":3,"name":"Alice"}]}""", searchedAt = 1_759_000_000_000),
    )

    @Test
    fun `chaque enregistrement fait l'aller-retour à l'identique`() {
        val bytes = archive()
        val read = readAll(bytes)
        assertEquals(records.size, read.size)
        // Empreintes comparées à part : un tableau n'est égal qu'à lui-même.
        val none = ByteArray(0)
        records.zip(read).forEach { (expected, actual) ->
            if (expected is FaceRecord) {
                actual as FaceRecord
                assertArrayEquals(expected.embedding, actual.embedding)
                assertEquals(expected.copy(embedding = none), actual.copy(embedding = none))
            } else {
                assertEquals(expected, actual)
            }
        }
        // Rien en clair : ni les noms, ni les chemins, ni les réglages.
        for (secret in listOf("IMG_20240714", "Alice", "Vacances", "WhatsApp", "theme_mode")) {
            assertFalse(secret, bytes.contains(secret.toByteArray()))
        }
    }

    @Test
    fun `l'en-tête est celui des sauvegardes de Focale, avec sa propre signature`() {
        val bytes = archive()
        assertArrayEquals("FOCALE-DATA\u0000".toByteArray(Charsets.US_ASCII), bytes.copyOf(12))
        assertEquals(1, bytes[12].toInt())
        // Même disposition que le coffre : dérivation, itérations, sel, chiffrement, témoin.
        assertEquals(PasswordArchive.KDF_PBKDF2_SHA256, bytes[13].toInt())
        assertEquals(iterations, ByteBuffer.wrap(bytes, 14, 4).int)
        assertEquals(PasswordArchive.SALT_BYTES, bytes[18].toInt())
    }

    @Test
    fun `un mot de passe faux est reconnu sans rien déchiffrer`() {
        val bytes = archive()
        assertReason(Reason.WRONG_PASSWORD) { DataArchive.open(bytes.inputStream()).keysFor("cheval correct agrafé".toCharArray()) }
        // Des clés d'une autre sauvegarde (autre sel) ne l'ouvrent pas non plus.
        val other = PasswordArchive.deriveKeys(ArchiveFormat.DATA, password, iterations)
        assertReason(Reason.WRONG_PASSWORD) { DataArchive.read(DataArchive.open(bytes.inputStream()), other) }
    }

    @Test
    fun `les clés d'une première lecture rouvrent la même sauvegarde`() {
        val bytes = archive()
        val keys = DataArchive.open(bytes.inputStream()).keysFor(password)
        repeat(2) { assertEquals(records.size, readAll(bytes, keys).size) }
    }

    @Test
    fun `une sauvegarde du coffre est refusée, et une sauvegarde des données par le coffre`() {
        val vault = requireNotNull(javaClass.getResourceAsStream(LEGACY_VAULT)).use { it.readBytes() }
        assertReason(Reason.OTHER_BACKUP) { DataArchive.open(vault.inputStream()) }
        assertReason(Reason.OTHER_BACKUP) { VaultArchive.open(archive().inputStream(), password) }
        // Même mot de passe, autres clés : le domaine de chaque format les sépare.
        val dataKeys = PasswordArchive.deriveKeys(ArchiveFormat.DATA, password, iterations)
        assertThrows(IllegalArgumentException::class.java) { VaultArchive.write(ByteArrayOutputStream(), dataKeys, VAULT_MANIFEST) {} }
        val vaultKeys = VaultArchive.deriveKeys(password, iterations)
        assertThrows(IllegalArgumentException::class.java) { DataArchive.write(ByteArrayOutputStream(), vaultKeys) }
        assertThrows(GeneralSecurityException::class.java) { ArchiveKeys.decode(ArchiveFormat.VAULT, dataKeys.encode()) }
        assertEquals(ArchiveFormat.DATA, ArchiveKeys.decode(ArchiveFormat.DATA, dataKeys.encode()).format)
    }

    @Test
    fun `un fichier quelconque n'est pas une sauvegarde`() {
        assertReason(Reason.NOT_AN_ARCHIVE) { DataArchive.open(ByteArray(0).inputStream()) }
        assertReason(Reason.NOT_AN_ARCHIVE) { DataArchive.open("FOCALE-DATA".toByteArray().inputStream()) }
        assertReason(Reason.NOT_AN_ARCHIVE) { DataArchive.open(Random(1).nextBytes(4_096).inputStream()) }
    }

    @Test
    fun `une sauvegarde d'une version plus récente est signalée`() {
        val bytes = archive()
        assertReason(Reason.UNSUPPORTED) { DataArchive.open(bytes.copyOf().also { it[12] = 2 }.inputStream()) }
        assertReason(Reason.CORRUPTED) { DataArchive.open(bytes.copyOf().also { it[12] = 0 }.inputStream()) }
    }

    @Test
    fun `toute troncature est refusée`() {
        val bytes = archive()
        val header = 12 + 1 + 1 + 4 + 1 + PasswordArchive.SALT_BYTES + 1 + PasswordArchive.WITNESS_BYTES
        for (cut in listOf(15, header - 1, header, header + 20, bytes.size / 2, bytes.size - 17, bytes.size - 1)) {
            try {
                readAll(bytes.copyOf(cut))
                fail("Coupure à $cut acceptée")
            } catch (error: BackupArchiveException) {
                assertEquals("coupure à $cut", Reason.CORRUPTED, error.reason)
            }
        }
    }

    @Test
    fun `toute modification est refusée`() {
        val bytes = archive()
        val header = 12 + 1 + 1 + 4 + 1 + PasswordArchive.SALT_BYTES + 1 + PasswordArchive.WITNESS_BYTES
        for (position in listOf(header, header + 5, header + 60, bytes.size / 2, bytes.size - 1)) {
            assertReason(Reason.CORRUPTED, "octet $position") { readAll(flipped(bytes, position)) }
        }
        assertReason(Reason.WRONG_PASSWORD, "sel") { readAll(flipped(bytes, 20)) }
        assertReason(Reason.CORRUPTED, "octet ajouté") { readAll(bytes + byteArrayOf(0)) }
    }

    @Test
    fun `un type d'enregistrement inconnu et des champs inconnus sont ignorés`() {
        // Archive d'une version ultérieure : un type 99 et un champ de plus, que celle-ci ne connaît pas.
        val keys = PasswordArchive.deriveKeys(ArchiveFormat.DATA, password, iterations)
        val output = ByteArrayOutputStream()
        val data = DataOutputStream(DeflaterOutputStream(PasswordArchive.encrypt(output, keys)))
        fun frame(type: Int, json: String) {
            val bytes = json.toByteArray()
            data.writeByte(type)
            data.writeInt(bytes.size)
            data.write(bytes)
        }
        frame(RecordType.HEADER.id, """{"createdAt":1,"appVersion":"9.0.0","faceVersion":1,"device":"nouveau"}""")
        frame(99, """{"inconnu":true}""")
        frame(RecordType.FAVORITE.id, """{"media":0,"addedAt":5,"note":"plus tard"}""")
        data.writeByte(RecordType.END)
        data.close()
        val read = readAll(output.toByteArray())
        assertEquals(listOf(HeaderRecord(1, "9.0.0", 1), FavoriteRecord(0, 5)), read)
    }

    @Test
    fun `un enregistrement illisible rend la sauvegarde endommagée`() {
        val keys = PasswordArchive.deriveKeys(ArchiveFormat.DATA, password, iterations)
        val output = ByteArrayOutputStream()
        val data = DataOutputStream(DeflaterOutputStream(PasswordArchive.encrypt(output, keys)))
        val broken = """{"id":1,"media":0,"left":0,"top":0,"right":1,"bottom":1,"embedding":"pas du base64 !","quality":1}""".toByteArray()
        data.writeByte(RecordType.FACE.id)
        data.writeInt(broken.size)
        data.write(broken)
        data.writeByte(RecordType.END)
        data.close()
        val sealed = DataArchive.open(output.toByteArray().inputStream())
        val reader = DataArchive.read(sealed, sealed.keysFor(password))
        assertReason(Reason.CORRUPTED) { reader.next()?.decode() }
    }

    @Test
    fun `l'empreinte ne dépend que du contenu, pas de la date de la sauvegarde`() {
        fun fingerprint(header: HeaderRecord) = FingerprintSink().apply { (listOf(header) + records.drop(1)).forEach(::write) }.fingerprint()
        val first = fingerprint(HeaderRecord(1, "1.9.0", 1))
        assertEquals(first, fingerprint(HeaderRecord(2, "1.9.1", 1)))
        val changed = FingerprintSink().apply { records.dropLast(1).forEach(::write) }.fingerprint()
        assertFalse(first == changed)
        // L'archive calcule la même empreinte que ce qu'elle écrit.
        val sink = FingerprintSink()
        archive(fingerprint = sink)
        assertEquals(first, sink.fingerprint())
    }

    private fun archive(fingerprint: FingerprintSink? = null): ByteArray {
        val output = ByteArrayOutputStream()
        PasswordArchive.deriveKeys(ArchiveFormat.DATA, password, iterations).use { keys ->
            DataArchive.write(output, keys, fingerprint).use { writer ->
                records.forEach(writer::write)
                writer.finish()
            }
        }
        return output.toByteArray()
    }

    /** Lit tout le contenu, fin comprise. */
    private fun readAll(bytes: ByteArray, keys: ArchiveKeys? = null): List<DataRecord> {
        val sealed = DataArchive.open(bytes.inputStream())
        return DataArchive.read(sealed, keys ?: sealed.keysFor(password)).use { reader ->
            generateSequence { reader.next() }.mapNotNull { it.decode() }.toList().also { assertNull(reader.next()) }
        }
    }

    private fun assertReason(expected: Reason, context: String = "", block: () -> Unit) {
        try {
            block()
            fail("Archive acceptée à tort $context")
        } catch (error: BackupArchiveException) {
            assertEquals(context, expected, error.reason)
        }
    }

    private fun flipped(bytes: ByteArray, position: Int): ByteArray =
        bytes.copyOf().also { it[position] = (it[position].toInt() xor 0x01).toByte() }

    private fun ByteArray.contains(part: ByteArray): Boolean =
        (0..size - part.size).any { start -> part.indices.all { this[start + it] == part[it] } }

    private companion object {
        /** Sauvegarde du coffre de référence (`VaultArchiveTest`), mot de passe « cheval correct agrafe ». */
        const val LEGACY_VAULT = "/vault/sauvegarde-sans-albums.fvault"
        val VAULT_MANIFEST = ArchiveManifest(createdAt = 1, items = emptyList())
    }
}
