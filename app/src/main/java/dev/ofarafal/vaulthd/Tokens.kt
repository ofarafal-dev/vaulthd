package dev.ofarafal.vaulthd

import android.content.Context
import java.io.File

/** Domyślne tokeny + własne (dowolny kontrakt ERC-20). Przechowujemy tylko dane publiczne. */
object Tokens {
    private val DEF = emptyList<Token>()

    private fun file(c: Context) = File(c.filesDir, "tokens.txt")

    private fun topFile(c: Context, chain: Long) = File(c.filesDir, "top_$chain.txt")

    fun top(c: Context, chain: Long): List<Token> =
        topFile(c, chain).takeIf { it.exists() }?.readLines()?.mapNotNull { l ->
            val p = l.split("|")
            val d = p.getOrNull(2)?.toIntOrNull()
            if (p.size == 3 && d != null) Token(chain, p[0], p[1], d) else null
        } ?: emptyList()

    fun saveTop(c: Context, chain: Long, l: List<Token>) =
        topFile(c, chain).writeText(l.joinToString("") { "${it.address}|${it.symbol}|${it.decimals}\n" })

    fun custom(c: Context): List<Token> =
        file(c).takeIf { it.exists() }?.readLines()?.mapNotNull { l ->
            val p = l.split("|")
            if (p.size == 4) Token(p[0].toLong(), p[1], p[2], p[3].toInt()) else null
        } ?: emptyList()

    fun forChain(c: Context, chainId: Long): List<Token> =
        (DEF + top(c, chainId) + custom(c)).filter { it.chainId == chainId }.distinctBy { it.address }

    fun add(c: Context, t: Token) {
        if (forChain(c, t.chainId).any { it.address == t.address }) return
        file(c).appendText("${t.chainId}|${t.address}|${t.symbol}|${t.decimals}\n")
    }

    fun remove(c: Context, t: Token) {
        file(c).writeText(custom(c).filter { !(it.chainId == t.chainId && it.address == t.address) }
            .joinToString("") { "${it.chainId}|${it.address}|${it.symbol}|${it.decimals}\n" })
    }
}
