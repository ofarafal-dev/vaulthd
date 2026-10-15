package dev.ofarafal.vaulthd

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.web3j.crypto.MnemonicUtils
import org.web3j.crypto.WalletUtils
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

val Accent = Color(0xFF5EEAD4)
val Bg = Color(0xFF0A0E14)
val CardBg = Color(0xFF131A24)
val Muted = Color(0xFF8A97A8)
val Warn = Color(0xFFFBBF24)

class MainActivity : FragmentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Color.Black, background = Bg,
                surface = Bg, surfaceVariant = CardBg, onSurface = Color(0xFFE6EDF5), tertiary = Warn)) {
                Surface(Modifier.fillMaxSize(), color = Bg) { App(this) }
            }
        }
    }
}

fun fmtTok(v: BigInteger, dec: Int): String {
    val bd = BigDecimal(v).movePointLeft(dec).setScale(minOf(dec, 8), RoundingMode.DOWN)
    return bd.stripTrailingZeros().toPlainString()
}
fun parseAmt(s: String, dec: Int): BigInteger =
    BigDecimal(s.trim().replace(',', '.')).movePointRight(dec).toBigIntegerExact()

@Composable
fun App(act: FragmentActivity) {
    var has by remember { mutableStateOf(Vault.exists(act)) }
    var addrs by remember { mutableStateOf(Vault.addresses(act)) }
    var msg by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("VaultHD", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("  EVM · ${Wallet.COUNT} adresów", color = Accent, fontSize = 12.sp)
        }
        if (msg.isNotEmpty()) {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp).clickable { msg = "" }, shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg)) {
                Text(msg, Modifier.padding(12.dp), color = Warn, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (!has) Setup(act, { has = true; addrs = Vault.addresses(act); msg = "" }, { msg = it })
        else Main(act, addrs) { msg = it }
    }
}

@Composable
fun Setup(act: FragmentActivity, onDone: () -> Unit, say: (String) -> Unit) {
    var mn by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var gen by remember { mutableStateOf(false) }
    var w3 by remember { mutableStateOf("") }
    var w7 by remember { mutableStateOf("") }
    val kb = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false)
    val words = mn.trim().split(Regex("\\s+"))
    val valid = MnemonicUtils.validateMnemonic(mn.trim())
    val quizOk = !gen || (words.size > 6 && w3.trim() == words[2] && w7.trim() == words[6])
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Nowy portfel", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Button(onClick = { mn = Wallet.newMnemonic(); gen = true; w3 = ""; w7 = "" }, Modifier.fillMaxWidth()) {
                Text("Generuj nowy seed (12 słów)")
            }
            OutlinedTextField(mn, { mn = it; gen = false }, Modifier.fillMaxWidth(),
                label = { Text("Seed BIP39 (lub wpisz, by zaimportować)") }, keyboardOptions = kb)
            OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(),
                label = { Text("Passphrase (opcjonalnie)") }, keyboardOptions = kb,
                visualTransformation = PasswordVisualTransformation())
            if (gen) {
                Text("Zapisz seed OFFLINE na papierze. Potwierdź słowa:", color = Warn, fontSize = 13.sp)
                OutlinedTextField(w3, { w3 = it }, label = { Text("Słowo #3") }, keyboardOptions = kb)
                OutlinedTextField(w7, { w7 = it }, label = { Text("Słowo #7") }, keyboardOptions = kb)
            }
            Button(enabled = valid && quizOk, modifier = Modifier.fillMaxWidth(), onClick = {
                val m = mn.trim()
                Vault.save(act, m, pass, Wallet.addresses(m, pass), { mn = ""; w3 = ""; w7 = ""; onDone() }, say)
            }) { Text("Zapisz w sejfie (biometria)") }
        }
    }
}

@Composable
fun Main(act: FragmentActivity, addrs: List<String>, say: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var ni by remember { mutableIntStateOf(0) }
    var tokVer by remember { mutableIntStateOf(0) }
    val net = NETS[ni]
    val tokens = remember(ni, tokVer) { Tokens.forChain(act, net.chainId) }
    var native by remember { mutableStateOf<Map<String, BigInteger>>(emptyMap()) }
    var tb by remember { mutableStateOf<Map<String, Map<String, BigInteger>>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var hideEmpty by remember { mutableStateOf(false) }
    var from by remember { mutableStateOf<Int?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun refresh() {
        job?.cancel()
        job = scope.launch {
            loading = true
            try {
                native = withContext(Dispatchers.IO) { Chain.balances(net, addrs) }
                for (t in tokens) {
                    val m = withContext(Dispatchers.IO) {
                        runCatching { Chain.tokenBalances(net, t, addrs) }.getOrDefault(emptyMap())
                    }
                    tb = tb + (t.address to m)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { say("Błąd RPC: ${e.message}") }
            loading = false
        }
    }
    LaunchedEffect(ni, tokVer) { native = emptyMap(); tb = emptyMap(); refresh() }

    fun hasAny(a: String) = (native[a]?.signum() ?: 0) > 0 ||
        tokens.any { (tb[it.address]?.get(a)?.signum() ?: 0) > 0 }
    val visible = addrs.indices.filter { !hideEmpty || hasAny(addrs[it]) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NETS.forEachIndexed { i, n -> FilterChip(selected = ni == i, onClick = { ni = i }, label = { Text(n.name) }) }
        }
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CardBg)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${net.name} · ${tokens.size} tokenów", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { refresh() }, enabled = !loading) { Text(if (loading) "Ładuję…" else "Odśwież") }
                    OutlinedButton(onClick = { showAdd = true }) { Text("+ Token") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = hideEmpty, onCheckedChange = { hideEmpty = it })
                    Text("  Tylko adresy z saldem", fontSize = 13.sp, color = Muted)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visible) { i ->
                val a = addrs[i]
                Card(Modifier.fillMaxWidth().clickable { from = i }, shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBg)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(30.dp).background(Accent.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                                Text("${i + 1}", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Text("  ${a.take(8)}…${a.takeLast(6)}", Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                            TextButton(onClick = {
                                act.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("addr", a))
                                say("Skopiowano adres #${i + 1}")
                            }) { Text("Kopiuj") }
                        }
                        AssetRow(net.sym, native[a]?.let { fmtTok(it, 18) } ?: "…", (native[a]?.signum() ?: 0) == 0)
                        tokens.forEach { t ->
                            val v = tb[t.address]?.get(a)
                            val zero = (v?.signum() ?: 0) == 0
                            if (!(hideEmpty && zero)) AssetRow(t.symbol, v?.let { fmtTok(it, t.decimals) } ?: "…", zero)
                        }
                    }
                }
            }
        }
    }
    from?.let { SendDialog(act, it, addrs, net, tokens, native, tb, { from = null; refresh() }, say) }
    if (showAdd) AddTokenDialog(act, net, { changed -> showAdd = false; if (changed) tokVer++ }, say)
}

@Composable
fun AssetRow(sym: String, amount: String, dim: Boolean) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp)) {
        Text(sym, fontWeight = FontWeight.Medium, color = if (dim) Muted else Color.White, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        Text(amount, color = if (dim) Muted else Accent, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
    }
}

@Composable
fun AddTokenDialog(act: FragmentActivity, net: Net, onClose: (Boolean) -> Unit, say: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var addr by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<Token?>(null) }
    var changed by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(Tokens.custom(act).filter { it.chainId == net.chainId }) }
    AlertDialog(onDismissRequest = { onClose(changed) },
        title = { Text("Dodaj token (${net.name})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(addr, { addr = it; info = null }, Modifier.fillMaxWidth(),
                    label = { Text("Adres kontraktu 0x…") }, keyboardOptions = KeyboardOptions(autoCorrect = false))
                info?.let {
                    Text("Znaleziono: ${it.symbol} (${it.decimals} dec.)\nSprawdź, czy to OFICJALNY kontrakt (np. na CoinGecko). Fałszywe tokeny są częste.",
                        color = Warn, fontSize = 12.sp)
                }
                if (custom.isNotEmpty()) {
                    Text("Własne tokeny:", color = Muted, fontSize = 12.sp)
                    custom.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${t.symbol}  ${t.address.take(8)}…${t.address.takeLast(4)}", Modifier.weight(1f),
                                fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            TextButton(onClick = {
                                Tokens.remove(act, t); changed = true
                                custom = Tokens.custom(act).filter { it.chainId == net.chainId }
                            }) { Text("Usuń") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val t = info
                if (t != null) {
                    Tokens.add(act, t); changed = true
                    custom = Tokens.custom(act).filter { it.chainId == net.chainId }
                    info = null; addr = ""
                } else {
                    if (!WalletUtils.isValidAddress(addr.trim())) { say("Nieprawidłowy adres kontraktu"); return@Button }
                    scope.launch {
                        try { info = withContext(Dispatchers.IO) { Chain.tokenInfo(net, addr.trim()) } }
                        catch (e: Exception) { say("${e.message}") }
                    }
                }
            }) { Text(if (info == null) "Sprawdź" else "Dodaj") }
        },
        dismissButton = { TextButton(onClick = { onClose(changed) }) { Text("Zamknij") } })
}

@Composable
fun SendDialog(act: FragmentActivity, idx: Int, addrs: List<String>, net: Net, tokens: List<Token>,
               native: Map<String, BigInteger>, tb: Map<String, Map<String, BigInteger>>,
               onClose: () -> Unit, say: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val from = addrs[idx]
    var sel by remember { mutableIntStateOf(0) }
    var to by remember { mutableStateOf("") }
    var amt by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf<Fee?>(null) }
    var units by remember { mutableStateOf(BigInteger.ZERO) }
    val tok = if (sel == 0) null else tokens[sel - 1]
    val sym = tok?.symbol ?: net.sym
    val dec = tok?.decimals ?: 18
    val avail = if (tok == null) native[from] else tb[tok.address]?.get(from)
    AlertDialog(onDismissRequest = onClose,
        title = { Text("Wyślij z #${idx + 1} · ${net.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(from, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Muted)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf(net.sym) + tokens.map { it.symbol }).forEachIndexed { i, s ->
                        FilterChip(selected = sel == i, onClick = { if (fee == null) sel = i }, label = { Text(s) })
                    }
                }
                Text("Dostępne: ${avail?.let { fmtTok(it, dec) } ?: "…"} $sym", fontSize = 12.sp, color = Muted)
                OutlinedTextField(to, { to = it }, enabled = fee == null, label = { Text("Adres odbiorcy 0x…") },
                    keyboardOptions = KeyboardOptions(autoCorrect = false))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(amt, { amt = it }, Modifier.weight(1f), enabled = fee == null,
                        label = { Text("Kwota ($sym)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    if (tok != null && avail != null && fee == null) {
                        TextButton(onClick = { amt = BigDecimal(avail).movePointLeft(dec).toPlainString() }) { Text("MAX") }
                    }
                }
                fee?.let {
                    Text("SPRAWDŹ:\nDo: ${to.trim()}\nKwota: $amt $sym\n" +
                        "Maks. opłata: ${fmtTok(it.maxFee.multiply(it.gas), 18)} ${net.sym}\nSieć: ${net.name}",
                        fontSize = 12.sp, color = Warn)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (fee == null) {
                    if (!WalletUtils.isValidAddress(to.trim())) { say("Nieprawidłowy adres"); return@Button }
                    units = try { parseAmt(amt, dec) } catch (e: Exception) { say("Nieprawidłowa kwota"); return@Button }
                    if (units.signum() <= 0) { say("Kwota musi być > 0"); return@Button }
                    if (avail != null && units > avail) { say("Za mało środków"); return@Button }
                    scope.launch {
                        try { fee = withContext(Dispatchers.IO) { Chain.fee(net, from, tok, to.trim(), units) } }
                        catch (e: Exception) { say("RPC: ${e.message}") }
                    }
                } else {
                    Vault.load(act, { mn, pass ->
                        scope.launch {
                            try {
                                val kp = Wallet.keyPair(mn, pass, idx)
                                val h = withContext(Dispatchers.IO) { Chain.send(net, kp, to.trim(), units, fee!!, tok) }
                                say("Wysłano: $h"); onClose()
                            } catch (e: Exception) { say("Błąd: ${e.message}") }
                        }
                    }, say)
                }
            }) { Text(if (fee == null) "Dalej" else "Podpisz") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Anuluj") } })
}
