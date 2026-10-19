package app.focale.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {

    // Itérations réduites : le coût de PBKDF2 n'est pas ce qui est testé ici.
    private val iterations = 1_000

    @Test
    fun `le bon code est accepté, un autre refusé`() {
        val stored = PinHasher.hash("482915", iterations = iterations)
        assertTrue(PinHasher.verify("482915", stored))
        assertFalse(PinHasher.verify("482916", stored))
        assertFalse(PinHasher.verify("48291", stored))
    }

    @Test
    fun `chaque empreinte a son propre sel`() {
        val first = PinHasher.hash("1234", iterations = iterations)
        val second = PinHasher.hash("1234", iterations = iterations)
        assertNotEquals(first.salt, second.salt)
        assertNotEquals(first.hash, second.hash)
        assertEquals(iterations, first.iterations)
    }

    @Test
    fun `une empreinte corrompue est refusée sans exception`() {
        val stored = PinHasher.hash("1234", iterations = iterations)
        assertFalse(PinHasher.verify("1234", stored.copy(salt = "%%%")))
        assertFalse(PinHasher.verify("1234", stored.copy(hash = "")))
    }

    @Test
    fun `seuls 4 à 12 chiffres sont valides`() {
        assertTrue(PinHasher.isValid("0000"))
        assertTrue(PinHasher.isValid("123456789012"))
        assertFalse(PinHasher.isValid("123"))
        assertFalse(PinHasher.isValid("1234567890123"))
        assertFalse(PinHasher.isValid("12a4"))
        assertFalse(PinHasher.isValid(""))
    }
}
