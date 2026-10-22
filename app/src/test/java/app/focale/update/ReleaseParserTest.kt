package app.focale.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseParserTest {

    private val release = ReleaseParser.parse(RELEASE_JSON)

    @Test
    fun `les versions sont comparées numériquement`() {
        assertTrue(AppVersion.isNewer("v1.10.0", "1.9.3"))
        assertTrue(AppVersion.isNewer("2.0.0", "1.99.99"))
        assertTrue(AppVersion.isNewer("1.0.1", "1.0.0-debug"))
        assertFalse(AppVersion.isNewer("v1.0.0", "1.0.0"))
        assertFalse(AppVersion.isNewer("1.0.0", "1.1.0"))
        assertFalse(AppVersion.isNewer("nightly", "1.0.0"))
    }

    @Test
    fun `la réponse de l'API GitHub est lue`() {
        assertEquals("v1.2.0", release.tagName)
        assertEquals(4, release.assets.size)
        assertFalse(release.draft || release.prerelease)
    }

    @Test
    fun `l'APK de l'architecture de l'appareil est préféré`() {
        val update = ReleaseParser.updateFrom(release, current = "1.1.0", abis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"))!!
        assertEquals("1.2.0", update.version)
        assertEquals("https://github.com/Karelisio/Focale/releases/download/v1.2.0/app-standard-arm64-v8a-release.apk", update.apkUrl)
        assertEquals(25_162_099L, update.apkSize)
        assertEquals("ce90d922dbbe8b73494dbbb6717cedb87620c2aa13ec997352c838305ec4f510", update.sha256)
        assertEquals("https://github.com/Karelisio/Focale/releases/tag/v1.2.0", update.pageUrl)
        assertTrue(update.notes.startsWith("## Nouveautés"))
    }

    @Test
    fun `les notes Markdown sont réduites à des titres, puces et paragraphes`() {
        val blocks = ReleaseNotes.parse(
            """
            ## What's Changed
            * **Carte** visible en thème sombre by @Karelisio in https://github.com/Karelisio/Focale/pull/3
            - Voir [la documentation](https://example.org)
            <!-- commentaire -->

            **Full Changelog**: https://github.com/Karelisio/Focale/compare/v1.1.0...v1.2.0
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                ReleaseNotes.Block.Heading("What's Changed"),
                ReleaseNotes.Block.Bullet("Carte visible en thème sombre"),
                ReleaseNotes.Block.Bullet("Voir la documentation"),
                ReleaseNotes.Block.Paragraph("Full Changelog: https://github.com/Karelisio/Focale/compare/v1.1.0...v1.2.0"),
            ),
            blocks,
        )
    }

    @Test
    fun `les notes d'une version sont affichées dans la langue de Focale`() {
        // Description écrite par la CI (corps rendu par GitHub avec des fins de ligne CRLF).
        val body = "## Nouveautés\r\n\r\n- Collages de 2 à 9 photos\r\n\r\n${ReleaseNotes.ENGLISH_MARKER}\r\n\r\n## What's new\r\n\r\n- Collages of 2 to 9 photos\r\n"
        assertEquals(
            listOf(ReleaseNotes.Block.Heading("Nouveautés"), ReleaseNotes.Block.Bullet("Collages de 2 à 9 photos")),
            ReleaseNotes.parse(ReleaseNotes.forLanguage(body, "fr")),
        )
        val english = listOf(ReleaseNotes.Block.Heading("What's new"), ReleaseNotes.Block.Bullet("Collages of 2 to 9 photos"))
        assertEquals(english, ReleaseNotes.parse(ReleaseNotes.forLanguage(body, "en")))
        assertEquals(english, ReleaseNotes.parse(ReleaseNotes.forLanguage(body, "de")))
        // Sans partie anglaise (versions antérieures, notes écrites sur GitHub) : les notes entières.
        assertEquals("## Nouveautés\n\n- Dessins", ReleaseNotes.forLanguage("## Nouveautés\n\n- Dessins", "en"))
        // Partie de la langue vide : l'autre.
        assertEquals("- Only English", ReleaseNotes.forLanguage("${ReleaseNotes.ENGLISH_MARKER}\n- Only English", "fr"))
    }

    @Test
    fun `l'APK universel sert aux autres architectures, jamais la variante strict`() {
        val update = ReleaseParser.updateFrom(release, current = "1.1.0", abis = listOf("riscv64"))!!
        assertEquals("https://github.com/Karelisio/Focale/releases/download/v1.2.0/app-standard-universal-release.apk", update.apkUrl)
        assertNull(update.sha256)
    }

    @Test
    fun `pas de mise à jour pour une version ancienne, une préversion ou sans APK`() {
        assertNull(ReleaseParser.updateFrom(release, current = "1.2.0", abis = listOf("arm64-v8a")))
        assertNull(ReleaseParser.updateFrom(release.copy(prerelease = true), current = "1.1.0", abis = listOf("arm64-v8a")))
        assertNull(ReleaseParser.updateFrom(release.copy(assets = emptyList()), current = "1.1.0", abis = listOf("arm64-v8a")))
    }

    private companion object {
        /** Extrait d'une réponse réelle de `releases/latest` (champs inutiles en partie conservés). */
        val RELEASE_JSON = """
            {
              "tag_name": "v1.2.0",
              "target_commitish": "main",
              "name": "focale 1.2.0",
              "draft": false,
              "prerelease": false,
              "html_url": "https://github.com/Karelisio/Focale/releases/tag/v1.2.0",
              "body": "## Nouveautés\n\n- Dessins dans l'éditeur",
              "assets": [
                {
                  "id": 656108883,
                  "name": "app-standard-arm64-v8a-release.apk",
                  "content_type": "application/vnd.android.package-archive",
                  "size": 25162099,
                  "digest": "sha256:CE90D922DBBE8B73494DBBB6717CEDB87620C2AA13EC997352C838305EC4F510",
                  "browser_download_url": "https://github.com/Karelisio/Focale/releases/download/v1.2.0/app-standard-arm64-v8a-release.apk",
                  "uploader": { "login": "github-actions[bot]" }
                },
                {
                  "name": "app-standard-universal-release.apk",
                  "size": 56842611,
                  "digest": null,
                  "browser_download_url": "https://github.com/Karelisio/Focale/releases/download/v1.2.0/app-standard-universal-release.apk"
                },
                {
                  "name": "app-strict-arm64-v8a-release.apk",
                  "size": 25145255,
                  "browser_download_url": "https://github.com/Karelisio/Focale/releases/download/v1.2.0/app-strict-arm64-v8a-release.apk"
                },
                {
                  "name": "app-strict-universal-release.apk",
                  "size": 56825767,
                  "browser_download_url": "https://github.com/Karelisio/Focale/releases/download/v1.2.0/app-strict-universal-release.apk"
                }
              ]
            }
        """.trimIndent()
    }
}
