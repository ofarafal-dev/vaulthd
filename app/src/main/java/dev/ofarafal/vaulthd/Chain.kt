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

data class Net(val name: String, val chainId: Long, val rpc: String, val sym: String)
data class Fee(val tip: BigInteger, val maxFee: BigInteger, val gas: BigInteger)
data class Token(val chainId: Long, val address: String, val symbol: String, val decimals: Int)

val NETS = listOf(
    Net("Sepolia", 11155111, "https://ethereum-sepolia-rpc.publicnode.com", "ETH"),
    Net("Ethereum", 1, "https://ethereum-rpc.publicnode.com", "ETH"),
    Net("Polygon", 137, "https://polygon-bor-rpc.publicnode.com", "POL"),
    Net("BSC", 56, "https://bsc-rpc.publicnode.com", "BNB"),
    Net("Arbitrum", 42161, "https://arbitrum-one-rpc.publicnode.com", "ETH"),
    Net("Base", 8453, "https://base-rpc.publicnode.com", "ETH"),
)

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

    fun fee(n: Net, from: String, t: Token?, to: String, amount: BigInteger): Fee {
        val w = w3(n)
        val gp = w.ethGasPrice().send().gasPrice
        val tip = runCatching { w.ethMaxPriorityFeePerGas().send().maxPriorityFeePerGas }
            .getOrDefault(BigInteger.valueOf(1_000_000_000L))
        val maxFee = gp.multiply(BigInteger.valueOf(2)).max(tip)
        val gas = if (t == null) BigInteger.valueOf(21000) else {
            val e = w.ethEstimateGas(Transaction.createEthCallTransaction(from, t.address, transferData(to, amount))).send()
            if (e.hasError()) throw RuntimeException(e.error.message)
            e.amountUsed.multiply(BigInteger.valueOf(13)).divide(BigInteger.valueOf(10))
        }
        return Fee(tip, maxFee, gas)
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
}
