package dev.ofarafal.vaulthd

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Hasło trzymane w pamięci tylko, gdy aplikacja jest odblokowana. */
object Session {
    var pw: CharArray? = null
    var unlocked by mutableStateOf(false)
    var dk: ByteArray? = null // klucz danych (adresy, tokeny, RPC) - tylko w pamięci, tylko po odblokowaniu
    fun lock() { pw?.fill('\u0000'); pw = null; dk?.fill(0); dk = null; unlocked = false; Prefs.clearRpc() }
}
