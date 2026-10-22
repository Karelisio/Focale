package app.focale.cast.display

import app.focale.cast.display.PresentationDisplays.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Choix de l'écran externe où montrer la visionneuse et le diaporama. */
class PresentationDisplaysTest {

    private val hdmi = Candidate(id = 2, name = "HDMI Screen ", presentation = true, private = false, on = true)
    private val wireless = Candidate(id = 5, name = "Salon (Miracast)", presentation = true, private = false, on = true)

    @Test
    fun `écrans de présentation retenus, dans l'ordre du système`() {
        val candidates = listOf(
            // L'écran du téléphone, même annoncé comme écran de présentation.
            Candidate(id = 0, name = "Built-in Screen", presentation = true, private = false, on = true),
            hdmi,
            // Écran virtuel privé d'une autre application, écran sans présentation, écran éteint.
            Candidate(id = 3, name = "Virtual", presentation = true, private = true, on = true),
            Candidate(id = 4, name = "Overlay", presentation = false, private = false, on = true),
            Candidate(id = 6, name = "Off", presentation = true, private = false, on = false),
            wireless,
            hdmi,
        )
        assertEquals(
            listOf(ExternalDisplay(2, "HDMI Screen"), ExternalDisplay(5, "Salon (Miracast)")),
            PresentationDisplays.eligible(candidates),
        )
    }

    @Test
    fun `premier écran non arrêté par l'utilisateur`() {
        val available = PresentationDisplays.eligible(listOf(hdmi, wireless))
        assertEquals(ExternalDisplay(2, "HDMI Screen"), PresentationDisplays.choose(available, declined = emptySet()))
        assertEquals(ExternalDisplay(5, "Salon (Miracast)"), PresentationDisplays.choose(available, declined = setOf(2)))
        assertNull(PresentationDisplays.choose(available, declined = setOf(2, 5)))
        assertNull(PresentationDisplays.choose(emptyList(), declined = emptySet()))
    }
}
