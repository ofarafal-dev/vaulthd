package dev.ofarafal.vaulthd

import org.web3j.crypto.Bip32ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.crypto.MnemonicUtils
import java.security.SecureRandom

object Wallet {
    const val COUNT = 50

    fun newMnemonic(): String {
        val entropy = ByteArray(16).also { SecureRandom().nextBytes(it) } // 12 słów
        return MnemonicUtils.generateMnemonic(entropy)
    }

    private fun master(mn: String, pass: String): Bip32ECKeyPair =
        Bip32ECKeyPair.generateKeyPair(MnemonicUtils.generateSeed(mn, pass))

    private fun derive(master: Bip32ECKeyPair, i: Int): Bip32ECKeyPair {
        val h = Bip32ECKeyPair.HARDENED_BIT
        return Bip32ECKeyPair.deriveKeyPair(master, intArrayOf(44 or h, 60 or h, 0 or h, 0, i)) // m/44'/60'/0'/0/i
    }

    fun keyPair(mn: String, pass: String, i: Int) = derive(master(mn, pass), i)

    fun addresses(mn: String, pass: String): List<String> {
        val m = master(mn, pass)
        return (0 until COUNT).map { Keys.toChecksumAddress(Keys.getAddress(derive(m, it))) }
    }
}
