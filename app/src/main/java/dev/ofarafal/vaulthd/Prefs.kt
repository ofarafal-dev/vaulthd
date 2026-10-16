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

    fun init(c: Context) {
        val s = c.getSharedPreferences("ui", Context.MODE_PRIVATE); sp = s
        themeMode = s.getInt("theme", 0); darkIdx = s.getInt("dark", 0); lightIdx = s.getInt("light", 0)
        lockSec = s.getInt("lock", 30); hideBelow = s.getInt("hide", 0); feeSpeed = s.getInt("fee", 1)
        NETS.forEach { n -> s.getString("rpc_${n.chainId}", null)?.let { rpc[n.chainId] = it } }
    }

    fun save() {
        sp?.edit()?.putInt("theme", themeMode)?.putInt("dark", darkIdx)?.putInt("light", lightIdx)
            ?.putInt("lock", lockSec)?.putInt("hide", hideBelow)?.putInt("fee", feeSpeed)?.apply()
    }

    fun rpcOverride(chainId: Long): String = rpc[chainId] ?: ""
    fun setRpc(chainId: Long, url: String) {
        val u = url.trim()
        if (u.isEmpty()) rpc.remove(chainId) else rpc[chainId] = u
        sp?.edit()?.apply { if (u.isEmpty()) remove("rpc_$chainId") else putString("rpc_$chainId", u) }?.apply()
    }
    fun rpcFor(n: Net): String = rpc[n.chainId]?.takeIf { it.startsWith("https://") } ?: n.rpc
}
