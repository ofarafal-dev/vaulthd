package dev.ofarafal.vaulthd

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Ustawienia interfejsu (nic wrażliwego: motyw, blokada, RPC, szybkość opłaty). */
object Prefs {
    private var sp: SharedPreferences? = null
    var themeMode by mutableStateOf(0)   // 0 auto, 1 ciemny, 2 jasny
    var darkIdx by mutableStateOf(0)
    var lightIdx by mutableStateOf(0)
    var lockSec by mutableStateOf(30)
    var hideBelow by mutableStateOf(0)   // USD
    var feeSpeed by mutableStateOf(1)    // 0 wolna, 1 normalna, 2 szybka
    private val rpc = HashMap<Long, String>()
    private val legacy = HashMap<Long, String>() // stare, jawne wpisy RPC z poprzednich wersji (migracja po odblokowaniu)
    private var ctx: Context? = null

    fun init(c: Context) {
        val s = c.getSharedPreferences("ui", Context.MODE_PRIVATE); sp = s; ctx = c.applicationContext
        themeMode = s.getInt("theme", 0); darkIdx = s.getInt("dark", 0); lightIdx = s.getInt("light", 0)
        lockSec = s.getInt("lock", 30); hideBelow = s.getInt("hide", 0); feeSpeed = s.getInt("fee", 1)
        NETS.forEach { n -> s.getString("rpc_${n.chainId}", null)?.let { legacy[n.chainId] = it } }
    }

    fun save() {
        sp?.edit()?.putInt("theme", themeMode)?.putInt("dark", darkIdx)?.putInt("light", lightIdx)
            ?.putInt("lock", lockSec)?.putInt("hide", hideBelow)?.putInt("fee", feeSpeed)?.apply()
    }

    /** Adresy RPC mogą zawierać klucz API, więc trzymamy je zaszyfrowane i ładujemy dopiero po odblokowaniu. */
    fun loadRpc(c: Context) {
        rpc.clear()
        Vault.readEnc(c, "rpc.enc")?.lines()?.forEach { l ->
            val i = l.indexOf('|')
            if (i > 0) l.substring(0, i).toLongOrNull()?.let { rpc[it] = l.substring(i + 1) }
        }
        if (legacy.isNotEmpty()) {
            legacy.forEach { (k, v) -> rpc[k] = v }
            legacy.clear()
            saveRpc(c)
            sp?.edit()?.apply { NETS.forEach { remove("rpc_${it.chainId}") } }?.apply()
        }
    }
    fun clearRpc() { rpc.clear() }
    private fun saveRpc(c: Context) =
        Vault.writeEnc(c, "rpc.enc", rpc.entries.joinToString("\n") { "${it.key}|${it.value}" })

    fun rpcOverride(chainId: Long): String = rpc[chainId] ?: ""
    fun setRpc(chainId: Long, url: String) {
        val u = url.trim()
        if (u.isEmpty()) rpc.remove(chainId) else rpc[chainId] = u
        ctx?.let { saveRpc(it) }
    }
    fun rpcFor(n: Net): String = rpc[n.chainId]?.takeIf { it.startsWith("https://") } ?: n.rpc
}
