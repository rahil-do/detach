package io.github.detach

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Optional password that must be entered before any Detach switch can be changed.
 * Only a salted PBKDF2 hash is stored (in private app storage); the password itself never is.
 */
class Lock(context: Context) {
    private val p = context.getSharedPreferences("lock", Context.MODE_PRIVATE)

    val isSet: Boolean get() = p.contains("hash")

    fun set(password: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        p.edit()
            .putString("salt", b64(salt))
            .putString("hash", b64(hash(password, salt)))
            .apply()
    }

    fun clear() {
        p.edit().clear().apply()
    }

    /** Seconds left before another attempt is allowed (after 5 wrong tries). */
    fun lockoutSeconds(): Long {
        val until = lockedUntil
        val now = SystemClock.elapsedRealtime()
        return if (until > now) (until - now + 999) / 1000 else 0
    }

    fun verify(password: String): Boolean {
        if (lockoutSeconds() > 0) return false
        val salt = p.getString("salt", null)?.let { unb64(it) } ?: return false
        val stored = p.getString("hash", null)?.let { unb64(it) } ?: return false
        val ok = MessageDigest.isEqual(stored, hash(password, salt))
        if (ok) {
            failures = 0
        } else if (++failures >= 5) {
            failures = 0
            lockedUntil = SystemClock.elapsedRealtime() + 30_000
        }
        return ok
    }

    private fun hash(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, 120_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        // Kept in memory: closing the app doesn't reset the 30 s wait, a reboot does.
        @Volatile private var failures = 0
        @Volatile private var lockedUntil = 0L
    }
}
