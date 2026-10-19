package app.focale.vault

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Dérivation et vérification du code du coffre (PBKDF2-HMAC-SHA256, sel aléatoire). */
object PinHasher {

    const val ITERATIONS = 120_000
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 12
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    data class Hashed(val hash: String, val salt: String, val iterations: Int)

    fun hash(pin: String, salt: ByteArray = newSalt(), iterations: Int = ITERATIONS): Hashed {
        val derived = derive(pin, salt, iterations)
        return Hashed(encode(derived), encode(salt), iterations)
    }

    /** Comparaison en temps constant. */
    fun verify(pin: String, stored: Hashed): Boolean {
        val salt = runCatching { decode(stored.salt) }.getOrNull() ?: return false
        val expected = runCatching { decode(stored.hash) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(pin, salt, stored.iterations), expected)
    }

    /** Code valide : [MIN_LENGTH] à [MAX_LENGTH] chiffres. */
    fun isValid(pin: String): Boolean = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all(Char::isDigit)

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)
}
