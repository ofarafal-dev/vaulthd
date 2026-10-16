package dev.ofarafal.vaulthd

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seed jest szyfrowany dwukrotnie:
 *  1) wewnątrz: AES-GCM, klucz z hasła (PBKDF2-HMAC-SHA256, losowa sól),
 *  2) na zewnątrz: AES-GCM, klucz w Android Keystore (StrongBox/TEE) wymagający biometrii/PIN-u przy każdym użyciu.
 * Weryfikator hasła (osobna sól) służy tylko do odblokowania ekranu; sam nie pozwala odczytać seeda.
 */
object Vault {
    private const val ALIAS = "vaulthd_key"
    private const val FILE = "vault.bin"
    private const val VER = "verifier.bin"
    private const val ATT = "attempts.txt"
    private const val ADDR = "addresses.txt"
    private const val DK = "dk.bin"
    private const val DALIAS = "vaulthd_data"
    private const val DADDR = "addr.enc"
    private const val BIO_ALIAS = "vaulthd_bio"
    private const val BIO = "bio.bin"
    private const val ASKED = "bio_asked.txt"
    private const val SEP = "\u0000"
    private const val ITER = 250_000

    private fun rnd(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }

    private fun kdf(pw: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pw, salt, ITER, 256)
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    private fun innerEnc(pw: CharArray, plain: ByteArray): ByteArray {
        val salt = rnd(16)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(kdf(pw, salt), "AES")) }
        return salt + c.iv + c.doFinal(plain)
    }

    private fun innerDec(pw: CharArray, blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(kdf(pw, blob.copyOfRange(0, 16)), "AES"),
                GCMParameterSpec(128, blob.copyOfRange(16, 28)))
        }
        return c.doFinal(blob.copyOfRange(28, blob.size)) // zły klucz -> AEADBadTagException
    }

    private fun verifierFor(pw: CharArray): ByteArray { val s = rnd(16); return s + kdf(pw, s) }

    fun verify(c: Context, pw: CharArray): Boolean {
        val f = File(c.filesDir, VER)
        if (!f.exists()) return false
        val b = f.readBytes()
        return MessageDigest.isEqual(kdf(pw, b.copyOfRange(0, 16)), b.copyOfRange(16, b.size))
    }

    // Ochrona przed zgadywaniem: rosnące opóźnienia po 3. błędnej próbie.
    private fun att(c: Context): Pair<Int, Long> {
        val f = File(c.filesDir, ATT)
        if (!f.exists()) return 0 to 0L
        val p = f.readText().split("|")
        return (p[0].toIntOrNull() ?: 0) to (p.getOrNull(1)?.toLongOrNull() ?: 0L)
    }
    fun waitMs(c: Context): Long = maxOf(0L, att(c).second - System.currentTimeMillis())
    fun registerFail(c: Context) {
        val n = att(c).first + 1
        val d = when { n < 3 -> 0L; n == 3 -> 30_000L; n == 4 -> 60_000L; n == 5 -> 300_000L; else -> 900_000L }
        File(c.filesDir, ATT).writeText("$n|${System.currentTimeMillis() + d}")
    }
    fun clearFails(c: Context) { File(c.filesDir, ATT).delete() }

    fun wipe(c: Context) {
        listOf(FILE, VER, ATT, ADDR, BIO, ASKED, DK, DADDR).forEach { File(c.filesDir, it).delete() }
        c.filesDir.listFiles()?.forEach { f -> if (f.name.endsWith(".enc") || f.name == "tokens.txt" || f.name.startsWith("top_")) f.delete() }
        runCatching { c.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().apply { NETS.forEach { remove("rpc_${it.chainId}") } }.apply() }
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.apply { deleteEntry(ALIAS); deleteEntry(BIO_ALIAS); deleteEntry(DALIAS) } }
    }

    fun exists(c: Context): Boolean {
        val v = File(c.filesDir, FILE).exists(); val r = File(c.filesDir, VER).exists()
        if (v && !r) { wipe(c); return false } // stary format bez hasła
        return v && r
    }

    fun addresses(c: Context): List<String> =
        readEnc(c, DADDR)?.lines()?.filter { it.isNotBlank() } ?: emptyList()

    // ---- szyfrowanie danych (adresy, tokeny, RPC): klucz danych (DK) opakowany hasłem (PBKDF2) i kluczem sprzętowym ----
    private fun dataKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(DALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        fun spec(sb: Boolean) = KeyGenParameterSpec.Builder(DALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setIsStrongBoxBacked(sb).build()
        return try { gen.init(spec(true)); gen.generateKey() }
        catch (e: StrongBoxUnavailableException) { gen.init(spec(false)); gen.generateKey() }
    }

    private fun wrapDk(dk: ByteArray, pw: CharArray): ByteArray {
        val inner = innerEnc(pw, dk)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, dataKey()) }
        return c.iv + c.doFinal(inner)
    }

    private fun migrateLegacy(c: Context) {
        val dir = c.filesDir
        File(dir, ADDR).takeIf { it.exists() }?.let { writeEnc(c, DADDR, it.readText()); it.delete() }
        File(dir, "tokens.txt").takeIf { it.exists() }?.let { writeEnc(c, "tokens.enc", it.readText().trimEnd()); it.delete() }
        dir.listFiles()?.filter { it.name.startsWith("top_") && it.name.endsWith(".txt") }?.forEach { f ->
            writeEnc(c, f.name.removeSuffix(".txt") + ".enc", f.readText().trimEnd()); f.delete()
        }
    }

    /** Odszyfrowuje klucz danych hasłem i kluczem sprzętowym; klucz trzymany w pamięci do blokady. */
    fun unlockData(c: Context, pw: CharArray): Boolean = try {
        val f = File(c.filesDir, DK)
        if (!f.exists()) { // pierwsze uruchomienie po aktualizacji: zaszyfruj stare dane jawne
            val dk = rnd(32)
            f.writeBytes(wrapDk(dk, pw)); Session.dk = dk; migrateLegacy(c); true
        } else {
            val raw = f.readBytes()
            val outer = Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, dataKey(), GCMParameterSpec(128, raw.copyOfRange(0, 12))) }
            Session.dk = innerDec(pw, outer.doFinal(raw.copyOfRange(12, raw.size))); true
        }
    } catch (e: Exception) { false }

    private fun encWith(dk: ByteArray, name: String, text: String): ByteArray {
        val iv = rnd(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(dk, "AES"), GCMParameterSpec(128, iv)); updateAAD(name.toByteArray())
        }
        return iv + c.doFinal(text.toByteArray())
    }

    fun writeEnc(c: Context, name: String, text: String) {
        val dk = Session.dk ?: return
        File(c.filesDir, name).writeBytes(encWith(dk, name, text))
    }

    fun readEnc(c: Context, name: String): String? {
        val dk = Session.dk ?: return null
        val f = File(c.filesDir, name)
        if (!f.exists()) return null
        return try {
            val raw = f.readBytes()
            val cph = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(dk, "AES"), GCMParameterSpec(128, raw.copyOfRange(0, 12))); updateAAD(name.toByteArray())
            }
            String(cph.doFinal(raw.copyOfRange(12, raw.size)))
        } catch (e: Exception) { null }
    }

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

    // --- Odblokowanie odciskiem: hasło zaszyfrowane kluczem Keystore wymagającym SILNEJ biometrii (bez PIN-u telefonu) ---
    fun bioEnabled(c: Context) = File(c.filesDir, BIO).exists()
    fun bioAsked(c: Context) = File(c.filesDir, ASKED).exists()
    fun markBioAsked(c: Context) { File(c.filesDir, ASKED).writeText("1") }
    fun bioAvailable(c: Context) =
        BiometricManager.from(c).canAuthenticate(Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    fun bioDisable(c: Context) {
        File(c.filesDir, BIO).delete()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(BIO_ALIAS) }
    }

    private fun bioKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(BIO_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        fun spec(sb: Boolean) = KeyGenParameterSpec.Builder(
            BIO_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .setIsStrongBoxBacked(sb)
            .build()
        return try { gen.init(spec(true)); gen.generateKey() }
        catch (e: StrongBoxUnavailableException) { gen.init(spec(false)); gen.generateKey() }
    }

    private fun bioAuth(a: FragmentActivity, cipher: Cipher, title: String, ok: (Cipher) -> Unit, fail: (String) -> Unit) {
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setNegativeButtonText("Użyj hasła")
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG).build()
        BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                try { ok(r.cryptoObject!!.cipher!!) } catch (e: Exception) { fail("Błąd: ${e.message}") }
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) { fail(msg.toString()) }
        }).authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    fun bioEnroll(a: FragmentActivity, pw: CharArray, done: () -> Unit, fail: (String) -> Unit) {
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, bioKey()) }
            bioAuth(a, c, "Włącz odblokowanie odciskiem", { cp ->
                val ct = cp.doFinal(String(pw).toByteArray())
                File(a.filesDir, BIO).writeBytes(cp.iv + ct)
                done()
            }, fail)
        } catch (e: Exception) { bioDisable(a); fail("Odcisk: ${e.message}") }
    }

    fun bioUnlock(a: FragmentActivity, ok: (CharArray) -> Unit, fail: (String) -> Unit) {
        try {
            val raw = File(a.filesDir, BIO).readBytes()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, bioKey(), GCMParameterSpec(128, raw.copyOfRange(0, 12))) }
            bioAuth(a, c, "Odblokuj VaultHD", { cp ->
                ok(String(cp.doFinal(raw.copyOfRange(12, raw.size))).toCharArray())
            }, fail)
        } catch (e: KeyPermanentlyInvalidatedException) {
            bioDisable(a); fail("Zmieniono odciski w telefonie - wpisz hasło")
        } catch (e: Exception) { fail("Odcisk: ${e.message}") }
    }

    fun save(a: FragmentActivity, mn: String, pass: String, addrs: List<String>, pw: CharArray,
             done: () -> Unit, fail: (String) -> Unit) {
        Thread {
            try {
                val inner = innerEnc(pw, (mn + SEP + pass).toByteArray())
                val ver = verifierFor(pw)
                val dk = Session.dk ?: rnd(32)
                val dkBlob = wrapDk(dk, pw)
                a.runOnUiThread {
                    try {
                        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
                        auth(a, c, "Zabezpiecz seed", { cp ->
                            val ct = cp.doFinal(inner)
                            File(a.filesDir, FILE).writeBytes(cp.iv + ct)
                            File(a.filesDir, VER).writeBytes(ver)
                            File(a.filesDir, DK).writeBytes(dkBlob)
                            File(a.filesDir, DADDR).writeBytes(encWith(dk, DADDR, addrs.joinToString("\n")))
                            File(a.filesDir, ADDR).delete()
                            Session.dk = dk
                            clearFails(a)
                            done()
                        }, fail)
                    } catch (e: Exception) { fail("Keystore: ${e.message}") }
                }
            } catch (e: Exception) { a.runOnUiThread { fail("Błąd: ${e.message}") } }
        }.start()
    }

    fun load(a: FragmentActivity, pw: CharArray, ok: (String, String) -> Unit, fail: (String) -> Unit) {
        try {
            val raw = File(a.filesDir, FILE).readBytes()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12))) }
            auth(a, c, "Potwierdź transakcję", { cp ->
                val inner = cp.doFinal(raw.copyOfRange(12, raw.size))
                Thread {
                    try {
                        val p = String(innerDec(pw, inner)).split(SEP)
                        a.runOnUiThread { ok(p[0], p.getOrElse(1) { "" }) }
                    } catch (e: AEADBadTagException) { a.runOnUiThread { fail("Złe hasło") } }
                    catch (e: Exception) { a.runOnUiThread { fail("Błąd: ${e.message}") } }
                }.start()
            }, fail)
        } catch (e: KeyPermanentlyInvalidatedException) {
            fail("Klucz unieważniony (zmiana biometrii). Przywróć z seeda.")
        } catch (e: Exception) { fail("Keystore: ${e.message}") }
    }
}
