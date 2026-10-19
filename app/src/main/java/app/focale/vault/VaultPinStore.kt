package app.focale.vault

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.focale.core.di.DefaultDispatcher
import app.focale.core.di.VaultStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Résultat d'une tentative de déverrouillage par code. */
sealed interface PinCheck {
    data object Success : PinCheck
    data class Wrong(val remainingAttempts: Int) : PinCheck
    data class LockedOut(val untilMs: Long) : PinCheck
}

/**
 * Code du coffre et préférences d'accès. Après [MAX_ATTEMPTS] erreurs, la saisie est
 * bloquée pendant un délai croissant.
 */
@Singleton
class VaultPinStore @Inject constructor(
    @VaultStore private val store: DataStore<Preferences>,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    val hasPin: Flow<Boolean> = store.data.map { it[Keys.HASH] != null }
    val biometricEnabled: Flow<Boolean> = store.data.map { it[Keys.BIOMETRIC] ?: false }
    val lockedUntil: Flow<Long> = store.data.map { it[Keys.LOCKED_UNTIL] ?: 0L }

    suspend fun setPin(pin: String) {
        require(PinHasher.isValid(pin))
        val hashed = withContext(dispatcher) { PinHasher.hash(pin) }
        store.edit {
            it[Keys.HASH] = hashed.hash
            it[Keys.SALT] = hashed.salt
            it[Keys.ITERATIONS] = hashed.iterations
            it[Keys.FAILED] = 0
            it[Keys.LOCKED_UNTIL] = 0L
        }
    }

    suspend fun check(pin: String, now: Long = System.currentTimeMillis()): PinCheck {
        val prefs = store.data.first()
        val lockedUntil = prefs[Keys.LOCKED_UNTIL] ?: 0L
        if (lockedUntil > now) return PinCheck.LockedOut(lockedUntil)
        val stored = PinHasher.Hashed(
            hash = prefs[Keys.HASH] ?: return PinCheck.Wrong(0),
            salt = prefs[Keys.SALT].orEmpty(),
            iterations = prefs[Keys.ITERATIONS] ?: PinHasher.ITERATIONS,
        )
        if (withContext(dispatcher) { PinHasher.verify(pin, stored) }) {
            store.edit {
                it[Keys.FAILED] = 0
                it[Keys.LOCKED_UNTIL] = 0L
            }
            return PinCheck.Success
        }
        val failed = (prefs[Keys.FAILED] ?: 0) + 1
        return if (failed >= MAX_ATTEMPTS) {
            val penalty = BASE_LOCKOUT_MS * (1L shl ((failed - MAX_ATTEMPTS).coerceAtMost(6)))
            store.edit {
                it[Keys.FAILED] = failed
                it[Keys.LOCKED_UNTIL] = now + penalty
            }
            PinCheck.LockedOut(now + penalty)
        } else {
            store.edit { it[Keys.FAILED] = failed }
            PinCheck.Wrong(MAX_ATTEMPTS - failed)
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        store.edit { it[Keys.BIOMETRIC] = enabled }
    }

    /** Réussite biométrique : remet le compteur d'erreurs à zéro. */
    suspend fun onBiometricSuccess() {
        store.edit {
            it[Keys.FAILED] = 0
            it[Keys.LOCKED_UNTIL] = 0L
        }
    }

    private object Keys {
        val HASH = stringPreferencesKey("pin_hash")
        val SALT = stringPreferencesKey("pin_salt")
        val ITERATIONS = intPreferencesKey("pin_iterations")
        val BIOMETRIC = booleanPreferencesKey("biometric")
        val FAILED = intPreferencesKey("failed_attempts")
        val LOCKED_UNTIL = longPreferencesKey("locked_until")
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        private const val BASE_LOCKOUT_MS = 30_000L
    }
}
