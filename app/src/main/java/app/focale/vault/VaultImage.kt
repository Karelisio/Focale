package app.focale.vault

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import okio.buffer
import okio.source

/**
 * Donnée Coil désignant un fichier chiffré du coffre (média ou miniature).
 *
 * Le déchiffrement se fait en flux, directement vers le décodeur : rien n'est écrit
 * en clair sur le disque (l'application n'a pas de cache disque Coil).
 */
data class VaultImage(val fileName: String) {
    companion object {
        /** Préfixe des clés de cache mémoire, purgées au verrouillage. */
        const val KEY_PREFIX = "vault:"
    }
}

class VaultImageFetcher(
    private val data: VaultImage,
    private val options: Options,
    private val storage: VaultStorage,
    private val crypto: VaultCrypto,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val stream = crypto.openDecrypting(storage.file(data.fileName))
        return SourceFetchResult(
            source = ImageSource(source = stream.source().buffer(), fileSystem = options.fileSystem),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(
        private val storage: VaultStorage,
        private val crypto: VaultCrypto,
    ) : Fetcher.Factory<VaultImage> {
        override fun create(data: VaultImage, options: Options, imageLoader: ImageLoader): Fetcher =
            VaultImageFetcher(data, options, storage, crypto)
    }
}

class VaultImageKeyer : Keyer<VaultImage> {
    override fun key(data: VaultImage, options: Options): String = VaultImage.KEY_PREFIX + data.fileName
}
