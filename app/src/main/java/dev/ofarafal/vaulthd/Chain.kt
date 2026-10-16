package dev.ofarafal.vaulthd

import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.core.methods.response.EthCall
import org.web3j.protocol.core.methods.response.EthGetBalance
import org.web3j.protocol.http.HttpService
import org.web3j.tx.RawTransactionManager
import org.web3j.utils.Numeric
import java.math.BigInteger

data class Net(
    val name: String, val chainId: Long, val rpc: String, val sym: String, val testnet: Boolean,
    val explorer: String, val bs: String?, val llama: String?, val cg: String?,
)
data class Gas(val tip: BigInteger, val maxFee: BigInteger)
data class Fee(val tip: BigInteger, val maxFee: BigInteger, val gas: BigInteger)
data class Token(val chainId: Long, val address: String, val symbol: String, val decimals: Int)
data class SweepItem(val idx: Int, val amount: BigInteger, val gas: BigInteger, val note: String?)

// Publiczne RPC (publicnode) - jeśli któryś nie działa, podmień na własny.
val NETS = listOf(
    Net("Ethereum", 1, "https://ethereum-rpc.publicnode.com", "ETH", false,
        "https://eth.blockscout.com", "https://eth.blockscout.com", "ethereum", "ethereum"),
    Net("Polygon", 137, "https://polygon-bor-rpc.publicnode.com", "POL", false,
        "https://polygon.blockscout.com", "https://polygon.blockscout.com", "polygon", "polygon-ecosystem-token"),
    Net("BSC", 56, "https://bsc-rpc.publicnode.com", "BNB", false,
        "https://bscscan.com", null, "bsc", "binancecoin"),
    Net("Arbitrum", 42161, "https://arbitrum-one-rpc.publicnode.com", "ETH", false,
        "https://arbitrum.blockscout.com", "https://arbitrum.blockscout.com", "arbitrum", "ethereum"),
    Net("Base", 8453, "https://base-rpc.publicnode.com", "ETH", false,
        "https://base.blockscout.com", "https://base.blockscout.com", "base", "ethereum"),
)

val CG_PLATFORM = mapOf(
    1L to "ethereum", 137L to "polygon-pos", 56L to "binance-smart-chain", 42161L to "arbitrum-one", 8453L to "base",
)

/** Multicall3 (ten sam adres na większości sieci): setki wywołań balanceOf w jednym zapytaniu RPC. */
object Multi {
    const val ADDR = "0xca11bde05977b3631167028862be2a173976ca11"
    private fun word(v: BigInteger) = Numeric.toHexStringNoPrefixZeroPadded(v, 64)

    /** calls: (adres kontraktu, calldata hex bez 0x) -> dane aggregate3 */
    fun encode(calls: List<Pair<String, String>>): String {
        val sb = StringBuilder("0x82ad56cb")
        sb.append(word(BigInteger.valueOf(32))).append(word(BigInteger.valueOf(calls.size.toLong())))
        val bodies = calls.map { (t, d) ->
            val len = d.length / 2
            val padded = d.padEnd(((len + 31) / 32) * 64, '0')
            word(BigInteger(t.removePrefix("0x"), 16)) + word(BigInteger.ONE) + word(BigInteger.valueOf(96)) +
                word(BigInteger.valueOf(len.toLong())) + padded
        }
        var off = calls.size * 32
        bodies.forEach { sb.append(word(BigInteger.valueOf(off.toLong()))); off += it.length / 2 }
        bodies.forEach { sb.append(it) }
        return sb.toString()
    }

    /** Zwraca pierwsze słowo returnData (uint256) albo null, gdy wywołanie się nie powiodło. */
    fun decode(hex: String): List<BigInteger?> {
        val b = Numeric.hexStringToByteArray(hex)
        fun w(i: Int) = BigInteger(1, b.copyOfRange(i, i + 32))
        val arr = w(0).toInt(); val n = w(arr).toInt(); val base = arr + 32
        return (0 until n).map { i ->
            val st = base + w(base + 32 * i).toInt()
            val ok = w(st).signum() != 0
            val bo = w(st + 32).toInt()
            val len = w(st + bo).toInt()
            if (!ok || len < 32) null else BigInteger(1, b.copyOfRange(st + bo + 32, st + bo + 64))
        }
    }
}

object Chain {
    private fun w3(n: Net) = Web3j.build(HttpService(n.rpc))
    private fun pad(h: String) = h.removePrefix("0x").lowercase().padStart(64, '0')
    private fun call(w: Web3j, from: String?, to: String, data: String): String? =
        w.ethCall(Transaction.createEthCallTransaction(from, to, data), DefaultBlockParameterName.LATEST).send().value

    fun balances(n: Net, addrs: List<String>): Map<String, BigInteger> {
        val w = w3(n); val out = HashMap<String, BigInteger>()
        addrs.chunked(25).forEach { chunk ->
            val b = w.newBatch()
            chunk.forEach { b.add(w.ethGetBalance(it, DefaultBlockParameterName.LATEST)) }
            b.send().responses.forEachIndexed { i, r -> out[chunk[i]] = (r as EthGetBalance).balance }
        }
        return out
    }

    fun tokenBalances(n: Net, t: Token, addrs: List<String>): Map<String, BigInteger> {
        val w = w3(n); val out = HashMap<String, BigInteger>()
        addrs.chunked(25).forEach { chunk ->
            val b = w.newBatch()
            chunk.forEach { a ->
                b.add(w.ethCall(Transaction.createEthCallTransaction(a, t.address, "0x70a08231" + pad(a)), DefaultBlockParameterName.LATEST))
            }
            b.send().responses.forEachIndexed { i, r ->
                val v = (r as EthCall).value
                out[chunk[i]] = if (v == null || v.length < 3) BigInteger.ZERO else Numeric.toBigInt(v)
            }
        }
        return out
    }

    fun tokenBalancesMulti(n: Net, tokens: List<Token>, addrs: List<String>): Map<String, Map<String, BigInteger>> {
        val w = w3(n)
        val out = HashMap<String, HashMap<String, BigInteger>>()
        tokens.flatMap { t -> addrs.map { a -> t to a } }.chunked(300).forEach { chunk ->
            val data = Multi.encode(chunk.map { (t, a) -> t.address to ("70a08231" + pad(a)) })
            val res = call(w, null, Multi.ADDR, data)
            if (res == null || res.length < 10) throw RuntimeException("multicall: brak odpowiedzi RPC")
            val vals = Multi.decode(res)
            chunk.forEachIndexed { i, (t, a) -> out.getOrPut(t.address) { HashMap() }[a] = vals.getOrNull(i) ?: BigInteger.ZERO }
        }
        return out
    }

    fun decimals(n: Net, addrs: List<String>): Map<String, Int> {
        val w = w3(n); val out = HashMap<String, Int>()
        addrs.chunked(200).forEach { chunk ->
            val res = call(w, null, Multi.ADDR, Multi.encode(chunk.map { it to "313ce567" }))
            if (res != null && res.length > 10) Multi.decode(res).forEachIndexed { i, v ->
                if (v != null && v.bitLength() < 8 && v.toInt() <= 36) out[chunk[i]] = v.toInt()
            }
        }
        return out
    }

    private fun decodeString(hex: String?): String? {
        if (hex == null || hex.length < 4) return null
        val b = Numeric.hexStringToByteArray(hex)
        if (b.size == 32) return String(b).trim { it == '\u0000' }
        if (b.size < 64) return null
        val len = BigInteger(1, b.copyOfRange(32, 64)).toInt()
        if (len < 0 || 64 + len > b.size) return null
        return String(b, 64, len)
    }

    fun tokenInfo(n: Net, addr: String): Token {
        val w = w3(n)
        val dec = call(w, null, addr, "0x313ce567")
        if (dec == null || dec.length < 3) throw RuntimeException("To nie jest kontrakt ERC-20 w tej sieci")
        val sym = runCatching { decodeString(call(w, null, addr, "0x95d89b41")) }.getOrNull()
        return Token(n.chainId, addr.lowercase(), (sym?.takeIf { it.isNotBlank() } ?: "TOKEN").take(12), Numeric.toBigInt(dec).toInt())
    }

    fun transferData(to: String, amount: BigInteger) =
        "0xa9059cbb" + pad(to) + Numeric.toHexStringNoPrefixZeroPadded(amount, 64)

    fun gasPrice(n: Net): Gas {
        val w = w3(n)
        val gp = w.ethGasPrice().send().gasPrice
        val tip = runCatching { w.ethMaxPriorityFeePerGas().send().maxPriorityFeePerGas }
            .getOrDefault(BigInteger.valueOf(1_000_000_000L))
        val maxFee = gp.multiply(BigInteger.valueOf(2)).max(tip)
        if (maxFee > BigInteger.valueOf(1_000_000_000_000L)) // 1000 gwei
            throw RuntimeException("Podejrzanie wysoka cena gazu z RPC - przerwano")
        return Gas(tip, maxFee)
    }

    fun estimate(n: Net, from: String, t: Token?, to: String, amount: BigInteger): BigInteger {
        if (t == null) return BigInteger.valueOf(21000)
        val e = w3(n).ethEstimateGas(Transaction.createEthCallTransaction(from, t.address, transferData(to, amount))).send()
        if (e.hasError()) throw RuntimeException(e.error.message)
        return e.amountUsed.multiply(BigInteger.valueOf(13)).divide(BigInteger.valueOf(10))
    }

    fun fee(n: Net, from: String, t: Token?, to: String, amount: BigInteger): Fee {
        val g = gasPrice(n)
        return Fee(g.tip, g.maxFee, estimate(n, from, t, to, amount))
    }

    fun send(n: Net, kp: ECKeyPair, to: String, amount: BigInteger, f: Fee, t: Token?): String {
        val tm = RawTransactionManager(w3(n), Credentials.create(kp), n.chainId)
        val r = if (t == null)
            tm.sendEIP1559Transaction(n.chainId, f.tip, f.maxFee, f.gas, to, "", amount)
        else
            tm.sendEIP1559Transaction(n.chainId, f.tip, f.maxFee, f.gas, t.address, transferData(to, amount), BigInteger.ZERO)
        if (r.hasError()) throw RuntimeException(r.error.message)
        return r.transactionHash
    }

    /** Plan zbierania środków z wielu adresów na jeden. */
    fun plan(n: Net, addrs: List<String>, native: Map<String, BigInteger>, tb: Map<String, Map<String, BigInteger>>,
             t: Token?, dest: String): Pair<List<SweepItem>, Gas> {
        val gp = gasPrice(n)
        val items = ArrayList<SweepItem>()
        for (i in addrs.indices) {
            val a = addrs[i]
            if (a.equals(dest, true)) continue
            val bal = (if (t == null) native[a] else tb[t.address]?.get(a)) ?: BigInteger.ZERO
            if (bal.signum() <= 0) continue
            if (t == null) {
                val gas = BigInteger.valueOf(21000)
                val reserve = gp.maxFee.multiply(gas)
                items.add(if (bal <= reserve) SweepItem(i, BigInteger.ZERO, gas, "za mało na opłatę")
                          else SweepItem(i, bal - reserve, gas, null))
            } else {
                val gas = runCatching { estimate(n, a, t, dest, bal) }.getOrDefault(BigInteger.valueOf(100000))
                val need = gp.maxFee.multiply(gas)
                items.add(if ((native[a] ?: BigInteger.ZERO) < need) SweepItem(i, bal, gas, "brak gazu (${n.sym})")
                          else SweepItem(i, bal, gas, null))
            }
        }
        return Pair(items, gp)
    }
}
