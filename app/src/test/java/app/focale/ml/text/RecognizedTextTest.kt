package app.focale.ml.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognizedTextTest {

    @Test
    fun `les lignes sont nettoyées et les lignes vides ignorées`() {
        val text = RecognizedText.assemble(listOf("  FACTURE   n° 42 ", "", "   ", "Total :\t12,50 €"))
        assertEquals("FACTURE n° 42\nTotal : 12,50 €", text)
    }

    @Test
    fun `aucune ligne donne un texte vide`() {
        assertEquals("", RecognizedText.assemble(emptyList()))
        assertEquals("", RecognizedText.searchKey(""))
        assertEquals("", RecognizedText.searchKey(" \n — "))
    }

    @Test
    fun `la clé est en minuscules, sans accents ni ponctuation`() {
        assertEquals("cafe creme 2 50 eur", RecognizedText.searchKey("CAFÉ Crème\n2,50 EUR"))
        assertEquals("oeuf a la coque", RecognizedText.searchKey("Œuf à la coque"))
    }

    @Test
    fun `les mots composés gagnent une forme compacte`() {
        val key = RecognizedText.searchKey("Réseau Wi-Fi : Livebox-1234\nS.N.C.F.")
        assertTrue(key.startsWith("reseau wi fi livebox 1234 s n c f"))
        val words = key.split(' ')
        assertTrue("wifi" in words)
        assertTrue("livebox1234" in words)
        assertTrue("sncf" in words)
    }

    @Test
    fun `un numéro écrit par paires est aussi indexé sans espaces`() {
        val words = RecognizedText.searchKey("Tél. 06 12 34 56 78 · 06.98.76.54.32").split(' ')
        assertTrue("0612345678" in words)
        assertTrue("0698765432" in words)
    }

    @Test
    fun `une forme compacte déjà présente n'est pas répétée`() {
        assertEquals("wifi wi fi", RecognizedText.searchKey("WIFI Wi-Fi"))
        assertEquals("wifi", RecognizedText.searchKey("WiFi"))
    }
}
