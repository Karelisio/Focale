package app.focale.backup

import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Appariement des médias d'une sauvegarde à ceux d'un autre téléphone : même dossier, dossier
 * déplacé, noms en double, médias absents, dates décalées d'un fuseau, un média pour un seul.
 */
class MediaMatcherTest {

    private class Media(val path: String, val name: String, val size: Long, val taken: Long)

    private fun table(media: List<Media>): MediaKeyTable =
        MediaKeyTable.Builder().apply { media.forEach { add(it.path, it.name, it.size, it.taken) } }.build()

    private fun match(backup: List<Media>, library: List<Media>): List<Int> = MediaMatcher.match(table(backup), table(library)).toList()

    @Test
    fun `même dossier et même nom, sans casse ni différence de normalisation`() {
        val backup = listOf(
            Media("DCIM/Camera/", "IMG_0001.jpg", 1_000, T),
            Media("Pictures/Été/", "Plage.JPG", 2_000, T + 1),
        )
        val library = listOf(
            Media("Pictures/" + Normalizer.normalize("Été", Normalizer.Form.NFD) + "/", "plage.jpg", 2_000, T + 1),
            Media("dcim/camera", "img_0001.JPG", 1_000, T),
        )
        assertEquals(listOf(1, 0), match(backup, library))
    }

    @Test
    fun `au même chemin, la taille ou la date suffit, sinon c'est un autre fichier au même nom`() {
        val backup = listOf(
            // Retouché après la copie : autre taille, même date de prise de vue.
            Media("DCIM/Camera/", "IMG_0001.jpg", 1_000, T),
            // Copié sans sa date de fichier, sans EXIF : autre date, même taille.
            Media("DCIM/Camera/", "IMG_0002.jpg", 2_000, T),
            // Numérotation de l'appareil photo reprise à zéro : même nom, rien d'autre en commun.
            Media("DCIM/Camera/", "IMG_0003.jpg", 3_000, T),
        )
        val library = listOf(
            Media("DCIM/Camera/", "IMG_0001.jpg", 1_234, T),
            Media("DCIM/Camera/", "IMG_0002.jpg", 2_000, T + 86_400_000L * 30),
            Media("DCIM/Camera/", "IMG_0003.jpg", 3_001, T + 123_456),
        )
        assertEquals(listOf(0, 1, -1), match(backup, library))
    }

    @Test
    fun `dans un dossier déplacé, il faut le même nom, la même taille et la même date`() {
        val backup = listOf(
            Media("DCIM/Camera/", "IMG_0001.jpg", 1_000, T),
            Media("DCIM/Camera/", "IMG_0002.jpg", 2_000, T),
            Media("DCIM/Camera/", "IMG_0003.jpg", 3_000, T),
        )
        val library = listOf(
            Media("Pictures/Ancien téléphone/", "IMG_0001.jpg", 1_000, T),
            // Même nom ailleurs, mais autre taille : pas le même fichier.
            Media("Pictures/Ancien téléphone/", "IMG_0002.jpg", 2_001, T),
            // Même taille, date décalée d'un fuseau horaire (EXIF sans fuseau, date de fichier perdue).
            Media("Download/", "IMG_0003.jpg", 3_000, T - 2 * HOUR),
        )
        assertEquals(listOf(0, -1, 2), match(backup, library))
    }

    @Test
    fun `un décalage qui n'est pas celui d'un fuseau ne suffit pas à un dossier déplacé`() {
        val backup = listOf(Media("DCIM/Camera/", "IMG_0001.jpg", 1_000, T))
        assertEquals(listOf(-1), match(backup, listOf(Media("Download/", "IMG_0001.jpg", 1_000, T + 7 * 60_000))))
        assertEquals(listOf(-1), match(backup, listOf(Media("Download/", "IMG_0001.jpg", 1_000, T + 25 * HOUR))))
        assertEquals(listOf(0), match(backup, listOf(Media("Download/", "IMG_0001.jpg", 1_000, T + 5 * HOUR + 45 * 60_000))))
    }

    @Test
    fun `parmi des noms en double, chacun retrouve le sien et un média ne sert qu'une fois`() {
        val backup = listOf(
            // Deux photos différentes au même nom, dans deux dossiers.
            Media("DCIM/Camera/", "photo.jpg", 1_000, T),
            Media("Pictures/WhatsApp/", "photo.jpg", 5_000, T + HOUR),
            // Deux copies identiques d'une même photo.
            Media("Pictures/A/", "copie.jpg", 9_000, T),
            Media("Pictures/B/", "copie.jpg", 9_000, T),
            Media("Pictures/C/", "copie.jpg", 9_000, T),
        )
        val library = listOf(
            Media("Pictures/WhatsApp/", "photo.jpg", 5_000, T + HOUR),
            Media("Pictures/Ailleurs/", "photo.jpg", 1_000, T),
            // Une copie restée à sa place, une déplacée ; la troisième n'est pas sur ce téléphone.
            Media("Download/", "copie.jpg", 9_000, T),
            Media("Pictures/B/", "copie.jpg", 9_000, T),
        )
        assertEquals(listOf(1, 0, 2, 3, -1), match(backup, library))
    }

    @Test
    fun `un média à sa place n'est pas pris par une copie déplacée d'un autre`() {
        // La première copie de la sauvegarde n'a plus son dossier ; la seconde a gardé le sien.
        val backup = listOf(
            Media("Pictures/Retouches/", "IMG_0001.jpg", 1_000, T),
            Media("DCIM/Camera/", "IMG_0001.jpg", 1_000, T),
        )
        val library = listOf(Media("DCIM/Camera/", "IMG_0001.jpg", 1_000, T))
        assertEquals(listOf(-1, 0), match(backup, library))
    }

    @Test
    fun `entre plusieurs fichiers au même chemin, la taille et la date les plus proches`() {
        // Même chemin sur la carte SD et dans la mémoire interne.
        val backup = listOf(Media("DCIM/Camera/", "IMG_0001.jpg", 1_000, T))
        val library = listOf(
            Media("DCIM/Camera/", "IMG_0001.jpg", 1_000, T + 3 * HOUR),
            Media("DCIM/Camera/", "IMG_0001.jpg", 1_000, T),
            Media("DCIM/Camera/", "IMG_0001.jpg", 9_999, T),
        )
        assertEquals(listOf(1), match(backup, library))
    }

    @Test
    fun `média absent, bibliothèque ou sauvegarde vide`() {
        val backup = listOf(Media("DCIM/Camera/", "IMG_0001.jpg", 1_000, T))
        assertEquals(listOf(-1), match(backup, emptyList()))
        assertEquals(emptyList<Int>(), match(emptyList(), backup))
        assertEquals(listOf(-1), match(backup, listOf(Media("DCIM/Camera/", "IMG_0002.jpg", 1_000, T))))
    }

    @Test
    fun `les clés distinguent le dossier et le nom`() {
        assertEquals("dcim/camera", MediaKeys.folder(" /DCIM/Camera/ "))
        assertEquals(MediaKeys.path("DCIM/Camera/", "IMG.jpg"), MediaKeys.path("/dcim/camera", "img.JPG"))
        assertNotEquals(MediaKeys.path("DCIM/Camera/", "IMG.jpg"), MediaKeys.path("DCIM/Camera2/", "IMG.jpg"))
        assertEquals(MediaKeys.name("IMG.jpg"), MediaKeys.name(" img.jpg "))
        assertEquals(MediaMatcher.DateMatch.SHIFTED, MediaMatcher.dates(T, T + 9 * HOUR + 30 * 60_000))
        assertEquals(MediaMatcher.DateMatch.EXACT, MediaMatcher.dates(T, T))
        assertEquals(MediaMatcher.DateMatch.NONE, MediaMatcher.dates(T, T + 1_000))
    }

    private companion object {
        const val T = 1_720_974_612_000L
        const val HOUR = 3_600_000L
    }
}
