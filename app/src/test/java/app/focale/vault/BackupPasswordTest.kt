package app.focale.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPasswordTest {

    @Test
    fun `la dérivation suit PBKDF2-HMAC-SHA256 (vecteurs de la RFC 7914)`() {
        assertArrayEquals(
            hex("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc"),
            BackupPassword.derive("passwd".toCharArray(), "salt".toByteArray(), 1),
        )
        assertArrayEquals(
            hex("4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56"),
            BackupPassword.derive("Password".toCharArray(), "NaCl".toByteArray(), 80_000),
        )
    }

    @Test
    fun `un même mot de passe saisi sous deux formes Unicode donne la même clé`() {
        val salt = "focale".toByteArray()
        val composed = BackupPassword.derive("pâté été".toCharArray(), salt, 1_000)
        val decomposed = BackupPassword.derive("pâté été".toCharArray(), salt, 1_000)
        assertArrayEquals(composed, decomposed)
        // Encodage UTF-8 explicite (référence calculée indépendamment).
        assertArrayEquals(hex("c134df48b7413f9b72390ac0a4e986e2531a3058e7b43ee9a39916680dfb6226"), BackupPassword.derive("Pâté-été".toCharArray(), salt, 1_000))
        assertFalse(composed.contentEquals(BackupPassword.derive("pate ete".toCharArray(), salt, 1_000)))
    }

    @Test
    fun `seule la longueur minimale est imposée, en caractères Unicode`() {
        assertFalse(BackupPassword.isValid("1234567"))
        assertTrue(BackupPassword.isValid("12345678"))
        // Huit émojis : seize unités UTF-16, mais bien huit caractères.
        assertTrue(BackupPassword.isValid("🔒".repeat(8)))
        assertFalse(BackupPassword.isValid("🔒".repeat(7)))
    }

    @Test
    fun `l'indicateur tient compte de la longueur et de la variété`() {
        assertEquals(PasswordStrength.TOO_SHORT, BackupPassword.strength("Ab1!"))
        assertEquals(PasswordStrength.WEAK, BackupPassword.strength("vacances"))
        assertEquals(PasswordStrength.WEAK, BackupPassword.strength("aaaaaaaaaaaaaaaaaaaa"))
        assertEquals(PasswordStrength.FAIR, BackupPassword.strength("Vacances24"))
        assertEquals(PasswordStrength.FAIR, BackupPassword.strength("vacancesalamer"))
        assertEquals(PasswordStrength.STRONG, BackupPassword.strength("Vacances-2024!"))
        assertEquals(PasswordStrength.STRONG, BackupPassword.strength("cheval correct agrafe"))
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
