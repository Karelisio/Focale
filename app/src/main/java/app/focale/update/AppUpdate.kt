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
    /** Notes de version (Markdown GitHub), éventuellement vides. */
    val notes: String = "",
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
        val body: String? = null,
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
            notes = release.body.orEmpty().trim(),
        )
    }

    /** APK de la variante standard pour l'architecture de l'appareil, à défaut l'universel. */
    fun pickAsset(assets: List<Asset>, abis: List<String>): Asset? {
        val byName = assets.associateBy { it.name }
        return abis.firstNotNullOfOrNull { abi -> byName["app-standard-$abi-release.apk"] }
            ?: byName["app-standard-universal-release.apk"]
    }
}

/** Notes de version réduites à l'essentiel du Markdown GitHub : titres, puces, paragraphes. */
object ReleaseNotes {

    sealed interface Block {
        val text: String

        data class Heading(override val text: String) : Block
        data class Bullet(override val text: String) : Block
        data class Paragraph(override val text: String) : Block
    }

    private val heading = Regex("""^#{1,6}\s+(.*)$""")
    private val bullet = Regex("""^\s*[-*+]\s+(.*)$""")
    private val link = Regex("""\[([^\]]+)]\([^)]+\)""")
    private val emphasis = Regex("""(\*\*|__|`)""")
    /** Suffixe des notes générées par GitHub : « by @auteur in https://…/pull/12 ». */
    private val attribution = Regex("""\s+by @\S+ in https?://\S+$""")

    /**
     * Ligne qui sépare, dans la description d'une version GitHub, les notes en français de leur
     * traduction anglaise (CHANGELOG.en.md), ajoutée dessous par la CI (`.github/workflows/android.yml`).
     * Commentaire HTML : invisible sur GitHub.
     */
    const val ENGLISH_MARKER = "<!-- en -->"

    /**
     * Notes de [markdown] dans la langue [language] (code ISO 639) : la partie française, avant
     * [ENGLISH_MARKER], ou l'anglaise, après, pour toute autre langue ; l'autre si la sienne est
     * vide. Sans marque (versions antérieures, notes écrites sur GitHub), les notes entières.
     */
    fun forLanguage(markdown: String, language: String): String {
        val lines = markdown.lines()
        val marker = lines.indexOfFirst { it.trim() == ENGLISH_MARKER }
        if (marker < 0) return markdown
        val french = lines.subList(0, marker).joinToString("\n").trim()
        val english = lines.subList(marker + 1, lines.size).joinToString("\n").trim()
        return if (language == "fr") french.ifEmpty { english } else english.ifEmpty { french }
    }

    /**
     * Les lignes consécutives forment un même bloc (paragraphe ou puce continuée sur la
     * ligne suivante) ; une ligne vide, un titre ou une nouvelle puce le terminent.
     */
    fun parse(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        var pending: StringBuilder? = null
        var pendingIsBullet = false
        fun close() {
            pending?.let { text ->
                val cleaned = clean(text.toString())
                if (cleaned.isNotEmpty()) blocks += if (pendingIsBullet) Block.Bullet(cleaned) else Block.Paragraph(cleaned)
            }
            pending = null
        }
        markdown.lineSequence().map { it.trimEnd() }.forEach { line ->
            when {
                line.trimStart().startsWith("<!--") -> Unit
                line.isBlank() -> close()
                heading.matches(line) -> {
                    close()
                    clean(heading.matchEntire(line)!!.groupValues[1]).takeIf { it.isNotEmpty() }?.let { blocks += Block.Heading(it) }
                }
                bullet.matches(line) -> {
                    close()
                    pending = StringBuilder(bullet.matchEntire(line)!!.groupValues[1])
                    pendingIsBullet = true
                }
                pending != null -> pending!!.append(' ').append(line.trim())
                else -> {
                    pending = StringBuilder(line.trim())
                    pendingIsBullet = false
                }
            }
        }
        close()
        return blocks
    }

    private fun clean(text: String): String = text
        .replace(link, "$1")
        .replace(emphasis, "")
        .replace(attribution, "")
        .trim()
}
