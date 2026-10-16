package dev.ofarafal.vaulthd

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Mnemonik szyfrowany AES-GCM kluczem z Keystore; każde użycie klucza wymaga biometrii/PIN-u. */
object Vault {
    private const val ALIAS = "vaulthd_key"
    private const val FILE = "vault.bin"
    private const val ADDR = "addresses.txt"
    private const val SEP = "\u0000"

    fun wipe(c: Context) {
        File(c.filesDir, FILE).delete(); File(c.filesDir, ADDR).delete()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    fun exists(c: Context) = File(c.filesDir, FILE).exists()
    fun addresses(c: Context): List<String> =
        File(c.filesDir, ADDR).takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() } ?: emptyList()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        fun spec(sb: Boolean) = KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .setInvalidatedByBiometricEnrollment(true)
            .setIsStrongBoxBacked(sb)
            .build()
        return try { gen.init(spec(true)); gen.generateKey() }
        catch (e: StrongBoxUnavailableException) { gen.init(spec(false)); gen.generateKey() }
    }

    private fun auth(a: FragmentActivity, cipher: Cipher, title: String, ok: (Cipher) -> Unit, fail: (String) -> Unit) {
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title)
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL).build()
        BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                try { ok(r.cryptoObject!!.cipher!!) } catch (e: Exception) { fail("Błąd: ${e.message}") }
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) { fail(msg.toString()) }
        }).authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    fun save(a: FragmentActivity, mn: String, pass: String, addrs: List<String>, done: () -> Unit, fail: (String) -> Unit) {
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            auth(a, c, "Zabezpiecz seed", { cp ->
                val ct = cp.doFinal((mn + SEP + pass).toByteArray())
                File(a.filesDir, FILE).writeBytes(cp.iv + ct)
                File(a.filesDir, ADDR).writeText(addrs.joinToString("\n"))
                done()
            }, fail)
        } catch (e: Exception) { fail("Keystore: ${e.message}") }
    }

    fun load(a: FragmentActivity, ok: (String, String) -> Unit, fail: (String) -> Unit) {
        try {
            val raw = File(a.filesDir, FILE).readBytes()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12))) }
            auth(a, c, "Potwierdź transakcję", { cp ->
                val p = String(cp.doFinal(raw.copyOfRange(12, raw.size))).split(SEP)
                ok(p[0], p.getOrElse(1) { "" })
            }, fail)
        } catch (e: KeyPermanentlyInvalidatedException) {
            fail("Klucz unieważniony (zmiana biometrii). Przywróć z seeda.")
        } catch (e: Exception) { fail("Keystore: ${e.message}") }
    }
}
