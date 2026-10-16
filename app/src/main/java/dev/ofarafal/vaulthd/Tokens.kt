package dev.ofarafal.vaulthd

import android.content.Context

/** Listy tokenów są szyfrowane kluczem danych (nie leżą jawnym tekstem). */
object Tokens {
    private val DEF = emptyList<Token>()
    private const val CUSTOM = "tokens.enc"
    private fun topName(chain: Long) = "top_$chain.enc"

    fun top(c: Context, chain: Long): List<Token> =
        Vault.readEnc(c, topName(chain))?.lines()?.mapNotNull { l ->
            val p = l.split("|")
            val d = p.getOrNull(2)?.toIntOrNull()
            if (p.size == 3 && d != null) Token(chain, p[0], p[1], d) else null
        } ?: emptyList()

    fun saveTop(c: Context, chain: Long, l: List<Token>) =
        Vault.writeEnc(c, topName(chain), l.joinToString("\n") { "${it.address}|${it.symbol}|${it.decimals}" })

    fun custom(c: Context): List<Token> =
        Vault.readEnc(c, CUSTOM)?.lines()?.mapNotNull { l ->
            val p = l.split("|")
            val ch = p.getOrNull(0)?.toLongOrNull(); val d = p.getOrNull(3)?.toIntOrNull()
            if (p.size == 4 && ch != null && d != null) Token(ch, p[1], p[2], d) else null
        } ?: emptyList()

    private fun saveCustom(c: Context, l: List<Token>) =
        Vault.writeEnc(c, CUSTOM, l.joinToString("\n") { "${it.chainId}|${it.address}|${it.symbol}|${it.decimals}" })

    fun forChain(c: Context, chainId: Long): List<Token> =
        (DEF + top(c, chainId) + custom(c)).filter { it.chainId == chainId }.distinctBy { it.address }

    fun add(c: Context, t: Token) {
        if (forChain(c, t.chainId).any { it.address == t.address }) return
        saveCustom(c, custom(c) + t)
    }

    fun remove(c: Context, t: Token) {
        saveCustom(c, custom(c).filter { !(it.chainId == t.chainId && it.address == t.address) })
    }
}
