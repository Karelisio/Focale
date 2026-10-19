package app.focale.vault

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Chiffrement du coffre et lecture avec accès aléatoire, avec un jeu de clés en
 * mémoire (le Keystore n'existe pas hors appareil) et de petits segments pour
 * traverser plusieurs frontières de segment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultCryptoTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val crypto = VaultCrypto {
        StreamingAeadConfig.register()
        KeysetHandle.generateNew(KeyTemplates.get("AES128_GCM_HKDF_4KB"))
            .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    }

    private val plain = Random(42).nextBytes(50_000)

    private fun encrypted(): File = folder.newFile("item.bin").also { file ->
        assertEquals(plain.size.toLong(), crypto.encrypt(plain.inputStream(), file))
    }

    @Test
    fun `aller-retour complet et contrôle de taille`() {
        val file = encrypted()
        assertFalse(file.readBytes().contentEquals(plain))
        assertEquals(plain.size.toLong(), crypto.plaintextSize(file))
        assertArrayEquals(plain, crypto.openDecrypting(file).use { it.readBytes() })
    }

    @Test
    fun `un fichier renommé ne se déchiffre plus`() {
        val file = encrypted()
        val renamed = File(folder.root, "other.bin").also { file.renameTo(it) }
        try {
            crypto.plaintextSize(renamed)
            fail("Le nom du fichier fait partie des données authentifiées")
        } catch (expected: IOException) {
            // Attendu : authentification refusée.
        }
    }

    @Test
    fun `la source vidéo lit une plage arbitraire`() {
        val file = encrypted()
        val source = EncryptedFileDataSource(crypto)
        val length = source.open(DataSpec.Builder().setUri(Uri.fromFile(file)).setPosition(12_345).setLength(20_000).build())
        assertEquals(20_000L, length)
        assertArrayEquals(plain.copyOfRange(12_345, 32_345), source.readAll())
        source.close()
    }

    @Test
    fun `la source vidéo lit jusqu'à la fin sans longueur imposée`() {
        val file = encrypted()
        val source = EncryptedFileDataSource(crypto)
        val length = source.open(DataSpec.Builder().setUri(Uri.fromFile(file)).setPosition(45_000).build())
        assertEquals(5_000L, length)
        assertArrayEquals(plain.copyOfRange(45_000, plain.size), source.readAll())
        source.close()
    }

    private fun EncryptedFileDataSource.readAll(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(3_000)
        while (true) {
            val read = read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
