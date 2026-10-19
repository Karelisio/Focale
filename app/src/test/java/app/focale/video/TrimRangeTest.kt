package app.focale.video

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimRangeTest {

    private val video = TrimRange(durationMs = 10_000)

    @Test
    fun `la plage initiale est la vidéo entière`() {
        assertTrue(video.isFull)
        assertEquals(10_000L, video.lengthMs)
        assertTrue(video.canTrim)
    }

    @Test
    fun `le début reste dans la vidéo`() {
        assertEquals(0L, video.withStart(-500).startMs)
        assertEquals(2_500L, video.withStart(2_500).startMs)
        assertTrue(video.withStart(2_500).trimsStart)
    }

    @Test
    fun `la fin reste dans la vidéo`() {
        assertEquals(10_000L, video.withEnd(12_000).endMs)
        assertEquals(7_000L, video.withEnd(7_000).endMs)
        assertTrue(video.withEnd(7_000).trimsEnd)
    }

    @Test
    fun `les poignées gardent la durée minimale entre elles`() {
        val cut = video.withEnd(6_000)
        assertEquals(6_000L - TrimRange.MIN_LENGTH_MS, cut.withStart(5_800).startMs)

        val late = video.withStart(4_000)
        assertEquals(4_000L + TrimRange.MIN_LENGTH_MS, late.withEnd(4_200).endMs)
        assertEquals(TrimRange.MIN_LENGTH_MS, late.withEnd(0).lengthMs)
    }

    @Test
    fun `une vidéo trop courte ne se coupe pas`() {
        val short = TrimRange(durationMs = 800)
        assertFalse(short.canTrim)
        assertEquals(short, short.withStart(200))
        assertEquals(short, short.withEnd(500))
        assertTrue(short.withStart(200).isFull)
    }

    @Test
    fun `of corrige une plage incohérente`() {
        val range = TrimRange.of(durationMs = 10_000, startMs = 9_800, endMs = 3_000)
        assertEquals(9_000L, range.startMs)
        assertEquals(10_000L, range.endMs)
        assertEquals(TrimRange(5_000), TrimRange.of(5_000, -10, 99_999))
    }

    @Test
    fun `une durée corrigée garde une fin laissée au bout`() {
        val untouched = video.withStart(1_000)
        assertEquals(10_040L, untouched.withDuration(10_040).endMs)
        assertFalse(untouched.withDuration(10_040).trimsEnd)

        val cut = video.withEnd(8_000)
        assertEquals(8_000L, cut.withDuration(10_040).endMs)
        assertEquals(5_000L, cut.withDuration(5_000).endMs)
    }

    @Test
    fun `la lecture reste dans la plage`() {
        val range = video.withStart(2_000).withEnd(6_000)
        assertEquals(2_000L, range.clamp(500))
        assertEquals(6_000L, range.clamp(9_000))
        assertEquals(4_000L, range.playbackStart(4_000))
        // Hors plage ou en bout de plage : la lecture repart du début.
        assertEquals(2_000L, range.playbackStart(1_000))
        assertEquals(2_000L, range.playbackStart(5_950))
    }

    @Test
    fun `positions au dixième de seconde selon la langue`() {
        assertEquals("0:07,4", TrimFormat.position(7_490, Locale.FRENCH))
        assertEquals("0:07.4", TrimFormat.position(7_490, Locale.ENGLISH))
        assertEquals("12:05.0", TrimFormat.position(725_000, Locale.ENGLISH))
        assertEquals("1:02:05.9", TrimFormat.position(3_725_999, Locale.ENGLISH))
        assertEquals("0:00.0", TrimFormat.position(-20, Locale.ENGLISH))
    }

    @Test
    fun `durée conservée en secondes puis en minutes`() {
        assertEquals("7,4 s", TrimFormat.length(7_499, Locale.FRENCH))
        assertEquals("59.9 s", TrimFormat.length(59_990, Locale.ENGLISH))
        assertEquals("1:05", TrimFormat.length(65_400, Locale.ENGLISH))
    }

    @Test
    fun `une copie sans coupe ni son retiré ne change rien`() {
        assertFalse(TrimRequest(video, removeAudio = false).hasChanges)
        assertTrue(TrimRequest(video, removeAudio = true).hasChanges)
        assertTrue(TrimRequest(video.withEnd(9_000), removeAudio = false).hasChanges)
    }
}
