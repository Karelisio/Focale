package app.focale.vault

import android.content.Context
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chiffrement des fichiers du coffre avec Tink `StreamingAead`
 * (AES-256-GCM-HKDF, segments de 1 Mo) : chiffrement en flux, déchiffrement en flux
 * ou avec accès aléatoire (lecture vidéo), authentification de chaque segment.
 *
 * Le jeu de clés est lui-même chiffré par une clé maîtresse non exportable de
 * l'Android Keystore. Les données associées liant chaque fichier à son nom, un
 * fichier chiffré ne peut pas être substitué à un autre.
 */
@Singleton
class VaultCrypto(primitive: () -> StreamingAead) {

    @Inject
    constructor(@ApplicationContext context: Context) : this({ keystoreBackedPrimitive(context) })

    private val aead: StreamingAead by lazy(primitive)

    /**
     * Chiffre [input] dans [target] et renvoie le nombre d'octets en clair écrits ;
     * le fichier est supprimé en cas d'échec.
     */
    fun encrypt(input: InputStream, target: File): Long {
        try {
            return target.outputStream().use { raw ->
                aead.newEncryptingStream(raw, associatedData(target)).use { encrypted ->
                    input.copyTo(encrypted, BUFFER)
                }
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    /**
     * Déchiffre entièrement [file] (chaque segment est authentifié) et renvoie sa
     * taille en clair : contrôle d'intégrité avant de supprimer un original.
     */
    fun plaintextSize(file: File): Long = openDecrypting(file).use { stream ->
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
        }
        total
    }

    fun openDecrypting(file: File): InputStream =
        aead.newDecryptingStream(file.inputStream().buffered(BUFFER), associatedData(file))

    /** Canal déchiffrant avec accès aléatoire (la position et la taille sont en clair). */
    fun openSeekable(file: File): SeekableByteChannel =
        aead.newSeekableDecryptingChannel(FileInputStream(file).channel, associatedData(file))

    private fun associatedData(file: File): ByteArray = file.name.toByteArray(Charsets.UTF_8)

    private companion object {
        const val KEYSET_NAME = "vault_keyset"
        const val PREFS_NAME = "focale_vault_keyset"
        const val MASTER_KEY_URI = "android-keystore://focale_vault_master_key"
        const val BUFFER = 256 * 1024

        /** Jeu de clés créé au premier usage, chiffré par la clé maîtresse du Keystore. */
        fun keystoreBackedPrimitive(context: Context): StreamingAead {
            StreamingAeadConfig.register()
            return AndroidKeysetManager.Builder()
                .withSharedPref(context, KEYSET_NAME, PREFS_NAME)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM_HKDF_1MB"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
                .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        }
    }
}
