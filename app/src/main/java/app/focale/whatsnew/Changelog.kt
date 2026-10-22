package app.focale.whatsnew

import androidx.compose.runtime.Immutable
import app.focale.update.AppVersion
import app.focale.update.ReleaseNotes

/**
 * Journal des versions, embarqué dans les assets de l'application : CHANGELOG.md en français,
 * CHANGELOG.en.md en anglais, aux mêmes sections.
 */
object Changelog {

    const val ASSET = "CHANGELOG.md"
    const val ENGLISH_ASSET = "CHANGELOG.en.md"

    /** Journal de la langue [language] (code ISO 639) : l'anglais pour toute autre que le français. */
    fun asset(language: String): String = if (language == "fr") ASSET else ENGLISH_ASSET

    @Immutable
    data class Release(val version: String, val blocks: List<ReleaseNotes.Block>)

    /** Titre de section : « ## 1.2.0 », éventuellement suivi d'une date. */
    private val header = Regex("""^##\s+v?(\d+\.\d+\.\d+)\b.*$""")

    /** Sections du journal, dans l'ordre du fichier ; le texte avant la première est ignoré. */
    fun parse(markdown: String): List<Release> {
        val releases = mutableListOf<Release>()
        var version: String? = null
        val body = StringBuilder()
        fun flush() {
            version?.let { releases += Release(it, ReleaseNotes.parse(body.toString())) }
            body.clear()
        }
        markdown.lineSequence().forEach { line ->
            val match = header.matchEntire(line.trim())
            if (match != null) {
                flush()
                version = match.groupValues[1]
            } else if (version != null) {
                body.appendLine(line)
            }
        }
        flush()
        return releases
    }

    /**
     * Versions à présenter après une mise à jour de [previous] vers [current] : plus
     * récentes que [previous], jusqu'à [current] incluse, de la plus récente à la plus ancienne.
     */
    fun since(releases: List<Release>, previous: String, current: String): List<Release> =
        releases
            .filter { AppVersion.isNewer(it.version, previous) && !AppVersion.isNewer(it.version, current) }
            .sortedWith { a, b -> if (AppVersion.isNewer(a.version, b.version)) -1 else if (AppVersion.isNewer(b.version, a.version)) 1 else 0 }
}
