package app.focale.backup

import java.nio.CharBuffer
import java.text.Normalizer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Robustesse indicative d'un mot de passe de sauvegarde. */
enum class PasswordStrength { TOO_SHORT, WEAK, FAIR, STRONG }

/**
 * Mot de passe des sauvegardes (coffre-fort et données) : règles de saisie, indicateur de
 * robustesse et dérivation de clé.
 *
 * Contrairement au code du coffre, qui ne garde qu'un accès local, ce mot de passe protège
 * un fichier destiné à quitter l'appareil : il doit résister à une attaque hors ligne,
 * d'où une longueur minimale et une dérivation lente. Ni scrypt ni Argon2 ne sont fournis
 * par la plateforme : PBKDF2-HMAC-SHA256, avec un nombre élevé d'itérations, évite une
 * dépendance supplémentaire.
 */
object BackupPassword {

    /** Longueur minimale, en caractères Unicode. */
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 256
    private const val GOOD_LENGTH = 12
    private const val STRONG_LENGTH = 16
    /** En deçà, le mot de passe répète quelques caractères (« aaaaaaaa », « abababab »). */
    private const val MIN_DISTINCT = 5
    private const val HMAC = "HmacSHA256"

    fun isValid(password: CharSequence): Boolean = codePoints(password).size in MIN_LENGTH..MAX_LENGTH

    /**
     * Indicateur simple, fondé sur la longueur et la variété des caractères (minuscules,
     * majuscules, chiffres, autres). Seule la longueur minimale est imposée.
     */
    fun strength(password: CharSequence): PasswordStrength {
        val points = codePoints(password)
        if (points.size < MIN_LENGTH) return PasswordStrength.TOO_SHORT
        val classes = listOf(
            points.any { Character.isLowerCase(it) },
            points.any { Character.isUpperCase(it) },
            points.any { Character.isDigit(it) },
            points.any { !Character.isLetterOrDigit(it) },
        ).count { it }
        return when {
            points.toSet().size < MIN_DISTINCT -> PasswordStrength.WEAK
            points.size >= STRONG_LENGTH || (points.size >= GOOD_LENGTH && classes >= 3) -> PasswordStrength.STRONG
            points.size >= GOOD_LENGTH || classes >= 3 -> PasswordStrength.FAIR
            else -> PasswordStrength.WEAK
        }
    }

    /**
     * Clé de 32 octets tirée de [password] : PBKDF2-HMAC-SHA256 (RFC 8018), un seul bloc.
     *
     * Écrite sur `HmacSHA256` plutôt que demandée à `SecretKeyFactory` : le mot de passe est
     * normalisé (NFC, un « é » saisi de deux façons donne la même clé) puis encodé en UTF-8
     * explicitement, sans dépendre du fournisseur cryptographique de l'appareil qui
     * restaurera la sauvegarde.
     */
    fun derive(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        require(iterations > 0)
        val secret = Normalizer.normalize(CharBuffer.wrap(password), Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        try {
            require(secret.isNotEmpty()) { "Mot de passe vide" }
            val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(secret, HMAC)) }
            // U1 = HMAC(P, S ‖ 1), Ui = HMAC(P, Ui-1), clé = U1 ⊕ … ⊕ Uc.
            val block = ByteArray(mac.macLength)
            mac.update(salt)
            mac.update(byteArrayOf(0, 0, 0, 1))
            mac.doFinal(block, 0)
            val key = block.copyOf()
            repeat(iterations - 1) {
                mac.update(block)
                mac.doFinal(block, 0)
                for (i in key.indices) key[i] = (key[i].toInt() xor block[i].toInt()).toByte()
            }
            block.fill(0)
            return key
        } finally {
            secret.fill(0)
        }
    }

    private fun codePoints(text: CharSequence): List<Int> {
        val points = ArrayList<Int>(text.length)
        var index = 0
        while (index < text.length) {
            val point = Character.codePointAt(text, index)
            points += point
            index += Character.charCount(point)
        }
        return points
    }
}
