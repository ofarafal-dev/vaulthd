package dev.ofarafal.vaulthd

import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.response.EthGetBalance
import org.web3j.protocol.http.HttpService
import org.web3j.tx.RawTransactionManager
import java.math.BigInteger

data class Net(val name: String, val chainId: Long, val rpc: String, val sym: String)
data class Fee(val tip: BigInteger, val maxFee: BigInteger)

// Publiczne RPC - zweryfikuj URL-e / podmień na własne.
val NETS = listOf(
    Net("Sepolia", 11155111, "https://ethereum-sepolia-rpc.publicnode.com", "ETH"),
    Net("Ethereum", 1, "https://ethereum-rpc.publicnode.com", "ETH"),
    Net("Polygon", 137, "https://polygon-bor-rpc.publicnode.com", "POL"),
    Net("BSC", 56, "https://bsc-rpc.publicnode.com", "BNB"),
    Net("Arbitrum", 42161, "https://arbitrum-one-rpc.publicnode.com", "ETH"),
    Net("Base", 8453, "https://base-rpc.publicnode.com", "ETH"),
)

const val GAS_NATIVE = 21000L

object Chain {
    private fun w3(n: Net) = Web3j.build(HttpService(n.rpc))

    fun balances(n: Net, addrs: List<String>): Map<String, BigInteger> {
        val w = w3(n); val out = HashMap<String, BigInteger>()
        addrs.chunked(25).forEach { chunk ->
            val b = w.newBatch()
            chunk.forEach { b.add(w.ethGetBalance(it, DefaultBlockParameterName.LATEST)) }
            b.send().responses.forEachIndexed { i, r -> out[chunk[i]] = (r as EthGetBalance).balance }
        }
        return out
    }

    fun fee(n: Net): Fee {
        val w = w3(n)
        val gp = w.ethGasPrice().send().gasPrice
        val tip = runCatching { w.ethMaxPriorityFeePerGas().send().maxPriorityFeePerGas }
            .getOrDefault(BigInteger.valueOf(1_000_000_000L))
        return Fee(tip, gp.multiply(BigInteger.valueOf(2)).max(tip))
    }

    fun send(n: Net, kp: ECKeyPair, to: String, wei: BigInteger, f: Fee): String {
        val w = w3(n)
        val tm = RawTransactionManager(w, Credentials.create(kp), n.chainId)
        val r = tm.sendEIP1559Transaction(n.chainId, f.tip, f.maxFee, BigInteger.valueOf(GAS_NATIVE), to, "", wei)
        if (r.hasError()) throw RuntimeException(r.error.message)
        return r.transactionHash
    }
}
