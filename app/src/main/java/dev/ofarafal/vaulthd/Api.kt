package dev.ofarafal.vaulthd

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL

data class Hist(val hash: String, val ts: String, val out: Boolean, val peer: String, val amount: String, val sym: String, val ok: Boolean)

/** Dane zewnętrzne bez kluczy API: Blockscout (tokeny, historia) i DefiLlama (ceny). */
object Api {
    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "VaultHD/0.2"); c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k)

    fun prices(n: Net, tokens: List<Token>): Map<String, Double> {
        val l = n.llama ?: return emptyMap()
        val cg = n.cg ?: return emptyMap()
        val keys = listOf("coingecko:$cg") + tokens.map { "$l:${it.address}" }
        val coins = JSONObject(get("https://coins.llama.fi/prices/current/" + keys.joinToString(","))).optJSONObject("coins")
            ?: return emptyMap()
        val out = HashMap<String, Double>()
        for (k in coins.keys()) {
            val p = coins.getJSONObject(k).optDouble("price", Double.NaN)
            if (p.isNaN()) continue
            val kk = k.lowercase()
            out[if (kk == "coingecko:" + cg.lowercase()) "native" else kk.substringAfter(":")] = p
        }
        return out
    }

    fun discover(n: Net, addr: String): List<Token> {
        val bs = n.bs ?: return emptyList()
        val arr = JSONArray(get("$bs/api/v2/addresses/$addr/token-balances"))
        val out = ArrayList<Token>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val t = o.optJSONObject("token") ?: continue
            if (t.str("type") != "ERC-20") continue
            val a = (t.str("address_hash") ?: t.str("address"))?.lowercase() ?: continue
            val sym = t.str("symbol")?.trim() ?: continue
            val dec = t.str("decimals")?.toIntOrNull() ?: continue
            val v = o.str("value")?.toBigIntegerOrNull() ?: continue
            if (v.signum() <= 0 || sym.isEmpty() || sym.length > 12 || dec !in 0..36) continue
            if (sym.any { !it.isLetterOrDigit() && it !in "_-+" }) continue // typowy spam: "t.me/…", "claim at…"
            out.add(Token(n.chainId, a, sym, dec))
        }
        return out
    }

    fun history(n: Net, addr: String): List<Hist> {
        val bs = n.bs ?: throw RuntimeException("Historia niedostępna w tej sieci - użyj eksploratora")
        val me = addr.lowercase()
        val out = ArrayList<Hist>()
        val tx = JSONObject(get("$bs/api/v2/addresses/$addr/transactions")).optJSONArray("items") ?: JSONArray()
        for (i in 0 until tx.length()) {
            val o = tx.getJSONObject(i)
            val v = (o.str("value") ?: "0").toBigIntegerOrNull() ?: BigInteger.ZERO
            if (v.signum() == 0) continue
            val f = o.optJSONObject("from")?.str("hash")?.lowercase() ?: ""
            val t = o.optJSONObject("to")?.str("hash") ?: ""
            val outgoing = f == me
            out.add(Hist(o.str("hash") ?: "", o.str("timestamp") ?: "", outgoing, if (outgoing) t else f,
                fmtTok(v, 18), n.sym, o.str("status") != "error"))
        }
        runCatching {
            val tr = JSONObject(get("$bs/api/v2/addresses/$addr/token-transfers?type=ERC-20")).optJSONArray("items") ?: JSONArray()
            for (i in 0 until tr.length()) {
                val o = tr.getJSONObject(i)
                val token = o.optJSONObject("token"); val total = o.optJSONObject("total")
                val dec = (total?.str("decimals") ?: token?.str("decimals"))?.toIntOrNull() ?: 18
                val v = total?.str("value")?.toBigIntegerOrNull() ?: continue
                val f = o.optJSONObject("from")?.str("hash")?.lowercase() ?: ""
                val t = o.optJSONObject("to")?.str("hash") ?: ""
                val outgoing = f == me
                out.add(Hist(o.str("transaction_hash") ?: o.str("tx_hash") ?: "", o.str("timestamp") ?: "", outgoing,
                    if (outgoing) t else f, fmtTok(v, dec), token?.str("symbol") ?: "TOKEN", true))
            }
        }
        return out.sortedByDescending { it.ts }.take(50)
    }
}
