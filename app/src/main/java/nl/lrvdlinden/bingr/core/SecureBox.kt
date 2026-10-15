package nl.lrvdlinden.bingr

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * build 97: kleine versleuteling voor de geheimen in `bingr.secrets` (IPTV-wachtwoorden, tokens, pincodes):
 * AES-256-GCM met een sleutel in de Android Keystore (verlaat het apparaat nooit, ook niet via een back-up).
 * Opgeslagen als "enc1:" + Base64(iv ‖ ciphertext). Waarden zonder dat voorvoegsel zijn van vóór build 97 (platte
 * tekst) en worden gewoon gelezen; [Store] zet ze eenmalig om.
 * Werkt de Keystore niet (sommige tv-boxen), dan blijft de waarde als platte tekst staan zoals vroeger.
 */
object SecureBox {
    private const val ALIAS = "bingr.secrets.v1"
    private const val PREFIX = "enc1:"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    @Volatile private var cached: SecretKey? = null
    @Volatile private var broken = false

    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    @Synchronized
    private fun key(): SecretKey? {
        cached?.let { return it }
        if (broken) return null
        return runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: run {
                val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                gen.init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                gen.generateKey()
            }
        }.onFailure { broken = true }.getOrNull()?.also { cached = it }
    }

    /** Versleutelen; null = de Keystore werkt niet (dan bewaart de aanroeper de platte tekst). */
    fun encrypt(plain: String): String? {
        val k = key() ?: return null
        return runCatching {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.ENCRYPT_MODE, k)
            val iv = c.iv
            if (iv == null || iv.size != IV_BYTES) return null
            val out = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + out, Base64.NO_WRAP)
        }.getOrNull()
    }

    /** Ontsleutelen; platte tekst (van vóór build 97) komt ongewijzigd terug, een onleesbare waarde als null. */
    fun decrypt(stored: String): String? {
        if (!isEncrypted(stored)) return stored
        val k = key() ?: return null
        return runCatching {
            val all = Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
            if (all.size <= IV_BYTES) return null
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES))
            String(c.doFinal(all, IV_BYTES, all.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }
}
