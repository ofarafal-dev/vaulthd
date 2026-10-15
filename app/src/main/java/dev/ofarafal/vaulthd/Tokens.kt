package dev.ofarafal.vaulthd

import android.content.Context
import java.io.File

/** Domyślne tokeny + własne (dowolny kontrakt ERC-20). Przechowujemy tylko dane publiczne. */
object Tokens {
    private val DEF = listOf(
        Token(1, "0xdac17f958d2ee523a2206206994597c13d831ec7", "USDT", 6),
        Token(1, "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "USDC", 6),
        Token(1, "0x514910771af9ca656af840dff83e8264ecf986ca", "LINK", 18),
        Token(1, "0x6982508145454ce325ddbe47a25d4ec3d2311933", "PEPE", 18),
        Token(137, "0xc2132d05d31c914a87c6611c10748aeb04b58e8f", "USDT", 6),
        Token(137, "0x3c499c542cef5e3811e1192ce70d8cc03d5c3359", "USDC", 6),
        Token(56, "0x55d398326f99059ff775485246999027b3197955", "USDT", 18),
        Token(56, "0x8ac76a51cc950d9822d68b83fe1ad97b32cd580d", "USDC", 18),
        Token(42161, "0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9", "USDT", 6),
        Token(42161, "0xaf88d065e77c8cc2239327c5edb3a432268e5831", "USDC", 6),
        Token(8453, "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", "USDC", 6),
    )

    private fun file(c: Context) = File(c.filesDir, "tokens.txt")

    fun custom(c: Context): List<Token> =
        file(c).takeIf { it.exists() }?.readLines()?.mapNotNull { l ->
            val p = l.split("|")
            if (p.size == 4) Token(p[0].toLong(), p[1], p[2], p[3].toInt()) else null
        } ?: emptyList()

    fun forChain(c: Context, chainId: Long): List<Token> =
        (DEF + custom(c)).filter { it.chainId == chainId }.distinctBy { it.address }

    fun add(c: Context, t: Token) {
        if (forChain(c, t.chainId).any { it.address == t.address }) return
        file(c).appendText("${t.chainId}|${t.address}|${t.symbol}|${t.decimals}\n")
    }

    fun remove(c: Context, t: Token) {
        file(c).writeText(custom(c).filter { !(it.chainId == t.chainId && it.address == t.address) }
            .joinToString("") { "${it.chainId}|${it.address}|${it.symbol}|${it.decimals}\n" })
    }
}
