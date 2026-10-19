package app.focale.update

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Version publiée sur GitHub, plus récente que celle installée (conservée entre deux lancements). */
@Immutable
@Serializable
data class AppUpdate(
    val version: String,
    val apkUrl: String,
    val apkSize: Long,
    /** Empreinte SHA-256 publiée par GitHub (hexadécimal), contrôlée après téléchargement. */
    val sha256: String?,
    val pageUrl: String,
)

/** Comparaison de versions « X.Y.Z » ; les suffixes (« -debug »…) sont ignorés. */
object AppVersion {
    private val pattern = Regex("""(\d+)\.(\d+)\.(\d+)""")

    fun parse(value: String): List<Int>? = pattern.find(value)?.groupValues?.drop(1)?.map(String::toInt)

    fun isNewer(candidate: String, current: String): Boolean {
        val next = parse(candidate) ?: return false
        val installed = parse(current) ?: return true
        for (i in 0..2) {
            if (next[i] != installed[i]) return next[i] > installed[i]
        }
        return false
    }
}

/** Lecture de la réponse de l'API GitHub `releases/latest`. */
object ReleaseParser {

    @Serializable
    data class Release(
        @SerialName("tag_name") val tagName: String,
        @SerialName("html_url") val htmlUrl: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    data class Asset(
        val name: String,
        val size: Long,
        /** « sha256:… » pour les fichiers publiés récemment. */
        val digest: String? = null,
        @SerialName("browser_download_url") val url: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): Release = json.decodeFromString(Release.serializer(), body)

    /**
     * Mise à jour proposée par [release] pour la version [current], ou null : brouillon,
     * préversion, version pas plus récente ou aucun APK compatible.
     */
    fun updateFrom(release: Release, current: String, abis: List<String>): AppUpdate? {
        if (release.draft || release.prerelease || !AppVersion.isNewer(release.tagName, current)) return null
        val asset = pickAsset(release.assets, abis)?.takeIf { it.url.startsWith("https://") } ?: return null
        return AppUpdate(
            version = release.tagName.removePrefix("v"),
            apkUrl = asset.url,
            apkSize = asset.size,
            sha256 = asset.digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
            pageUrl = release.htmlUrl,
        )
    }

    /** APK de la variante standard pour l'architecture de l'appareil, à défaut l'universel. */
    fun pickAsset(assets: List<Asset>, abis: List<String>): Asset? {
        val byName = assets.associateBy { it.name }
        return abis.firstNotNullOfOrNull { abi -> byName["app-standard-$abi-release.apk"] }
            ?: byName["app-standard-universal-release.apk"]
    }
}
