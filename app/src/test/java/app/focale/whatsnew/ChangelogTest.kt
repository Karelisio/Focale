package app.focale.whatsnew

import app.focale.update.ReleaseNotes
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogTest {

    private val releases = Changelog.parse(
        """
        # Nouveautés de Focale

        Texte d'introduction ignoré.

        ## 1.3.0 — 2026-12-01

        - Personnes
        - Recherche en langage
          naturel

        ## 1.2.0

        - Dessins

        ## 1.1.0

        - Mises à jour intégrées
        """.trimIndent(),
    )

    @Test
    fun `les sections sont lues avec leurs puces, continuations comprises`() {
        assertEquals(listOf("1.3.0", "1.2.0", "1.1.0"), releases.map { it.version })
        assertEquals(
            listOf(ReleaseNotes.Block.Bullet("Personnes"), ReleaseNotes.Block.Bullet("Recherche en langage naturel")),
            releases.first().blocks,
        )
    }

    @Test
    fun `seules les versions installées depuis la précédente sont présentées`() {
        assertEquals(listOf("1.3.0", "1.2.0"), Changelog.since(releases, previous = "1.1.0", current = "1.3.0").map { it.version })
        assertEquals(listOf("1.2.0"), Changelog.since(releases, previous = "1.1.0", current = "1.2.0-strict").map { it.version })
        assertTrue(Changelog.since(releases, previous = "1.3.0", current = "1.3.0").isEmpty())
    }

    @Test
    fun `le journal du dépôt est lisible et commence par la version la plus récente`() {
        val file = File("../CHANGELOG.md")
        val parsed = Changelog.parse(file.readText())
        assertTrue(parsed.isNotEmpty())
        assertTrue(parsed.all { it.blocks.isNotEmpty() })
        val sorted = parsed.sortedWith { a, b -> if (app.focale.update.AppVersion.isNewer(a.version, b.version)) -1 else 1 }
        assertEquals(sorted.map { it.version }, parsed.map { it.version })
    }
}
