package dev.ofarafal.vaulthd

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private var stoppedAt = 0L
    override fun onStop() { super.onStop(); stoppedAt = SystemClock.elapsedRealtime() }
    override fun onStart() {
        super.onStart()
        if (stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt > 30_000) Session.lock()
    }
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
fun toD(v: BigInteger, dec: Int): Double = BigDecimal(v).movePointLeft(dec).toDouble()
fun usdStr(x: Double?): String = if (x == null) "" else if (x < 0.01 && x > 0) "<\$0.01" else String.format("\$%.2f", x)
fun openUrl(act: FragmentActivity, url: String) = act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
fun parseAmt(s: String, dec: Int): BigInteger =
    BigDecimal(s.trim().replace(',', '.')).movePointRight(dec).toBigIntegerExact()

@Composable
fun App(act: FragmentActivity) {
    var has by remember { mutableStateOf(Vault.exists(act)) }
    var addrs by remember { mutableStateOf(Vault.addresses(act)) }
    var msg by remember { mutableStateOf("") }
    var confirmWipe by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("VaultHD", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("  EVM · ${Wallet.COUNT} adresów", color = Accent, fontSize = 12.sp)
            if (has) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { confirmWipe = true }) { Text("Zmień seed", color = Muted, fontSize = 12.sp) }
            }
        }
        if (confirmWipe) AlertDialog(onDismissRequest = { confirmWipe = false },
            title = { Text("Usunąć portfel z tego telefonu?") },
            text = { Text("Zaszyfrowany seed zostanie skasowany. Bez zapisanego seeda (12 słów) stracisz dostęp do środków. Potem wkleisz własny seed lub wygenerujesz nowy.") },
            confirmButton = { Button(onClick = {
                Vault.wipe(act); Session.lock(); has = false; addrs = emptyList(); confirmWipe = false; msg = ""
            }) { Text("Usuń") } },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Anuluj") } })
        if (msg.isNotEmpty()) {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp).clickable { msg = "" }, shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg)) {
                Text(msg, Modifier.padding(12.dp), color = Warn, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (!has) Setup(act, { c -> Session.pw = c; Session.unlocked = true; has = true; addrs = Vault.addresses(act); msg = "" }, { msg = it })
        else if (!Session.unlocked) LockScreen(act, { c -> Session.pw = c; Session.unlocked = true; msg = "" }, { msg = it })
        else Main(act, addrs) { msg = it }
    }
}

@Composable
fun Setup(act: FragmentActivity, onDone: (CharArray) -> Unit, say: (String) -> Unit) {
    var mnv by remember { mutableStateOf(TextFieldValue("")) }
    val mn = mnv.text
    var pass by remember { mutableStateOf("") }
    var gen by remember { mutableStateOf(false) }
    var w3 by remember { mutableStateOf("") }
    var w7 by remember { mutableStateOf("") }
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val kb = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false)
    val norm = mn.trim().lowercase().replace(Regex("\\s+"), " ")
    val words = norm.split(" ")
    val valid = MnemonicUtils.validateMnemonic(norm)
    val toks = norm.split(" ").filter { it.isNotEmpty() }
    val typing = mn.isNotEmpty() && !mn.last().isWhitespace()
    val lastTok = if (typing && toks.isNotEmpty()) toks.last() else ""
    val done = if (typing) toks.dropLast(1) else toks
    val sugg = if (gen) emptyList() else Bip39.suggest(lastTok)
    val unknown = if (gen) emptyList() else done.filter { it !in Bip39.set }
    val quizOk = !gen || (words.size > 6 && w3.trim() == words[2] && w7.trim() == words[6])
    val pwOk = p1.length >= 8 && p1 == p2
    Card(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Nowy portfel", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Button(onClick = { mnv = TextFieldValue(Wallet.newMnemonic()); gen = true; w3 = ""; w7 = "" }, Modifier.fillMaxWidth()) {
                Text("Generuj nowy seed (12 słów)")
            }
            OutlinedTextField(mnv, { mnv = it; gen = false }, Modifier.fillMaxWidth(),
                label = { Text("Seed BIP39 (lub wpisz / wklej własny)") }, keyboardOptions = kb)
            if (!gen && mn.isNotBlank()) {
                if (sugg.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        sugg.forEach { w ->
                            FilterChip(selected = false, label = { Text(w) }, onClick = {
                                val t = mn.dropLast(lastTok.length) + w + " "
                                mnv = TextFieldValue(t, TextRange(t.length))
                            })
                        }
                    }
                }
                if (unknown.isNotEmpty()) Text("Nieznane słowo: ${unknown.first()}", color = Color(0xFFF87171), fontSize = 12.sp)
                else if (valid) Text("✓ Poprawny seed (${words.size} słów)", color = Accent, fontSize = 12.sp)
                else Text("Słowa: ${toks.size} (potrzeba 12, 15, 18, 21 lub 24)", color = Muted, fontSize = 12.sp)
            }
            OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(),
                label = { Text("Passphrase BIP39 (opcjonalnie)") }, keyboardOptions = kb,
                visualTransformation = PasswordVisualTransformation())
            if (gen) {
                Text("Zapisz seed OFFLINE na papierze. Potwierdź słowa:", color = Warn, fontSize = 13.sp)
                OutlinedTextField(w3, { w3 = it }, label = { Text("Słowo #3") }, keyboardOptions = kb)
                OutlinedTextField(w7, { w7 = it }, label = { Text("Słowo #7") }, keyboardOptions = kb)
            }
            Text("Hasło do aplikacji szyfruje seed dodatkowo. Nie da się go odzyskać - przy utracie przywracasz portfel z seeda.",
                color = Muted, fontSize = 12.sp)
            OutlinedTextField(p1, { p1 = it }, Modifier.fillMaxWidth(), label = { Text("Hasło do aplikacji (min. 8 znaków)") },
                keyboardOptions = kb, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(p2, { p2 = it }, Modifier.fillMaxWidth(), label = { Text("Powtórz hasło") },
                keyboardOptions = kb, visualTransformation = PasswordVisualTransformation())
            Button(enabled = valid && quizOk && pwOk && !saving, modifier = Modifier.fillMaxWidth(), onClick = {
                val m = norm; val chars = p1.toCharArray()
                saving = true
                Vault.save(act, m, pass, Wallet.addresses(m, pass), chars,
                    { mnv = TextFieldValue(""); w3 = ""; w7 = ""; p1 = ""; p2 = ""; saving = false; onDone(chars) },
                    { saving = false; say(it) })
            }) { Text(if (saving) "Szyfruję…" else "Zapisz w sejfie (biometria)") }
        }
    }
}

@Composable
fun LockScreen(act: FragmentActivity, onUnlock: (CharArray) -> Unit, say: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var wait by remember { mutableLongStateOf(Vault.waitMs(act)) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(wait) { if (wait > 0) { delay(1000); wait = Vault.waitMs(act) } }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Odblokuj portfel", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth(), label = { Text("Hasło") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                visualTransformation = PasswordVisualTransformation())
            if (wait > 0) Text("Zbyt wiele prób. Odczekaj ${wait / 1000} s", color = Warn, fontSize = 12.sp)
            Button(enabled = !busy && wait == 0L && pw.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch {
                    busy = true
                    val chars = pw.toCharArray()
                    val ok = withContext(Dispatchers.IO) { Vault.verify(act, chars) }
                    if (ok) { Vault.clearFails(act); pw = ""; onUnlock(chars) }
                    else { Vault.registerFail(act); wait = Vault.waitMs(act); chars.fill('\u0000'); pw = ""; say("Złe hasło") }
                    busy = false
                }
            }) { Text(if (busy) "Sprawdzam…" else "Odblokuj") }
        }
    }
}

@Composable
fun Main(act: FragmentActivity, addrs: List<String>, say: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var test by remember { mutableStateOf(true) }
    var ni by remember { mutableIntStateOf(0) }
    var tokVer by remember { mutableIntStateOf(0) }
    val nets = remember(test) { NETS.filter { it.testnet == test } }
    val net = nets[ni.coerceIn(0, nets.size - 1)]
    val tokens = remember(net.chainId, tokVer) { Tokens.forChain(act, net.chainId) }
    var native by remember { mutableStateOf<Map<String, BigInteger>>(emptyMap()) }
    var tb by remember { mutableStateOf<Map<String, Map<String, BigInteger>>>(emptyMap()) }
    var prices by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var hideEmpty by remember { mutableStateOf(false) }
    var from by remember { mutableStateOf<Int?>(null) }
    var hist by remember { mutableStateOf<Int?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showSweep by remember { mutableStateOf(false) }
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
                if (!net.testnet) prices = withContext(Dispatchers.IO) {
                    runCatching { Api.prices(net, tokens) }.getOrDefault(emptyMap())
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { say("Błąd RPC: ${e.message}") }
            loading = false
        }
    }
    LaunchedEffect(test, ni, tokVer) { native = emptyMap(); tb = emptyMap(); prices = emptyMap(); refresh() }

    fun scan() {
        scope.launch {
            scanning = true
            val seen = HashSet<String>()
            withContext(Dispatchers.IO) {
                for ((i, a) in addrs.withIndex()) {
                    if (seen.size >= 60) break
                    val found = runCatching { Api.discover(net, a) }.getOrDefault(emptyList())
                    found.forEach { if (seen.add(it.address)) Tokens.add(act, it) }
                    say("Skanuję ${i + 1}/${addrs.size} · znaleziono ${seen.size}")
                    Thread.sleep(120)
                }
            }
            scanning = false
            say("Skan zakończony: ${seen.size} tokenów (spam odfiltrowany)")
            tokVer++
        }
    }

    fun hasAny(a: String) = (native[a]?.signum() ?: 0) > 0 ||
        tokens.any { (tb[it.address]?.get(a)?.signum() ?: 0) > 0 }
    fun addrUsd(a: String): Double? {
        if (prices.isEmpty()) return null
        var s = 0.0
        native[a]?.let { v -> prices["native"]?.let { p -> s += toD(v, 18) * p } }
        tokens.forEach { t -> tb[t.address]?.get(a)?.let { v -> prices[t.address]?.let { p -> s += toD(v, t.decimals) * p } } }
        return s
    }
    val visible = addrs.indices.filter { !hideEmpty || hasAny(addrs[it]) }
    val total = if (prices.isEmpty()) null else addrs.sumOf { addrUsd(it) ?: 0.0 }

    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = test, onClick = { test = true; ni = 0 }, label = { Text("Testnet") })
            FilterChip(selected = !test, onClick = { test = false; ni = 0 }, label = { Text("Mainnet") })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            nets.forEachIndexed { i, n -> FilterChip(selected = net == n, onClick = { ni = i }, label = { Text(n.name) }) }
        }
        if (!test) {
            Card(Modifier.fillMaxWidth().padding(top = 6.dp), shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3B1218))) {
                Text("MAINNET - prawdziwe środki. Zaczynaj od małych kwot.", Modifier.padding(10.dp),
                    color = Color(0xFFF87171), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CardBg)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${net.name} · ${tokens.size} tokenów", fontWeight = FontWeight.SemiBold)
                total?.let { Text(usdStr(it), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Accent) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { refresh() }, enabled = !loading) { Text(if (loading) "Ładuję…" else "Odśwież") }
                    OutlinedButton(onClick = { showSweep = true }) { Text("Zbierz środki") }
                    OutlinedButton(onClick = { showAdd = true }) { Text("+ Token") }
                    if (net.bs != null) OutlinedButton(onClick = { scan() }, enabled = !scanning) {
                        Text(if (scanning) "Skanuję…" else "Skanuj tokeny")
                    }
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
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text("${a.take(8)}…${a.takeLast(6)}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                                addrUsd(a)?.let { Text(usdStr(it), color = Muted, fontSize = 11.sp) }
                            }
                            TextButton(onClick = { hist = i }) { Text("Hist.") }
                            TextButton(onClick = {
                                act.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("addr", a))
                                say("Skopiowano adres #${i + 1}")
                            }) { Text("Kopiuj") }
                        }
                        val nv = native[a]
                        AssetRow(net.sym, nv?.let { fmtTok(it, 18) } ?: "…", (nv?.signum() ?: 0) == 0,
                            nv?.let { v -> prices["native"]?.let { toD(v, 18) * it } })
                        tokens.forEach { t ->
                            val v = tb[t.address]?.get(a)
                            val zero = (v?.signum() ?: 0) == 0
                            if (!(hideEmpty && zero)) AssetRow(t.symbol, v?.let { fmtTok(it, t.decimals) } ?: "…", zero,
                                v?.let { x -> prices[t.address]?.let { toD(x, t.decimals) * it } })
                        }
                    }
                }
            }
        }
    }
    from?.let { SendDialog(act, it, addrs, net, tokens, native, tb, { from = null; refresh() }, say) }
    hist?.let { HistoryDialog(act, net, addrs[it]) { hist = null } }
    if (showAdd) AddTokenDialog(act, net, { changed -> showAdd = false; if (changed) tokVer++ }, say)
    if (showSweep) SweepDialog(act, net, addrs, tokens, native, tb, { showSweep = false; refresh() }, say)
}

@Composable
fun AssetRow(sym: String, amount: String, dim: Boolean, usd: Double?) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(sym, fontWeight = FontWeight.Medium, color = if (dim) Muted else Color.White, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        if (usd != null && !dim) Text(usdStr(usd) + "  ", color = Muted, fontSize = 12.sp)
        Text(amount, color = if (dim) Muted else Accent, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
    }
}

@Composable
fun HistoryDialog(act: FragmentActivity, net: Net, addr: String, onClose: () -> Unit) {
    var items by remember { mutableStateOf<List<Hist>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(addr, net) {
        try { items = withContext(Dispatchers.IO) { Api.history(net, addr) } }
        catch (e: Exception) { err = e.message; items = emptyList() }
    }
    AlertDialog(onDismissRequest = onClose,
        title = { Text("Historia · ${addr.take(6)}…${addr.takeLast(4)}") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (items == null) Text("Ładuję…", color = Muted)
                err?.let { Text(it, color = Warn, fontSize = 12.sp) }
                if (items?.isEmpty() == true && err == null) Text("Brak transakcji", color = Muted)
                items?.forEach { h ->
                    Row(Modifier.fillMaxWidth().clickable { openUrl(act, "${net.explorer}/tx/${h.hash}") },
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text((if (h.out) "↑ Wysłano" else "↓ Odebrano") + (if (!h.ok) " (błąd)" else ""), fontSize = 13.sp)
                            Text("${h.peer.take(8)}…${h.peer.takeLast(4)} · ${h.ts.take(16).replace('T', ' ')}", color = Muted, fontSize = 11.sp)
                        }
                        Text("${if (h.out) "-" else "+"}${h.amount} ${h.sym}", fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                            color = if (h.out) Color.White else Accent)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { openUrl(act, "${net.explorer}/address/$addr") }) { Text("Eksplorator") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Zamknij") } })
}

@Composable
fun SweepDialog(act: FragmentActivity, net: Net, addrs: List<String>, tokens: List<Token>,
                native: Map<String, BigInteger>, tb: Map<String, Map<String, BigInteger>>,
                onClose: () -> Unit, say: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var sel by remember { mutableIntStateOf(0) }
    var dest by remember { mutableStateOf(addrs[0]) }
    var items by remember { mutableStateOf<List<SweepItem>?>(null) }
    var gp by remember { mutableStateOf<Gas?>(null) }
    var log by remember { mutableStateOf<List<String>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val tok = if (sel == 0) null else tokens[sel - 1]
    val sym = tok?.symbol ?: net.sym
    val dec = tok?.decimals ?: 18
    val ok = items?.filter { it.note == null } ?: emptyList()
    AlertDialog(onDismissRequest = { if (!running) onClose() },
        title = { Text("Zbierz środki · ${net.name}") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!net.testnet) Text("MAINNET - prawdziwe środki", color = Color(0xFFF87171), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf(net.sym) + tokens.map { it.symbol }).forEachIndexed { i, s ->
                        FilterChip(selected = sel == i, onClick = { if (items == null) sel = i }, label = { Text(s) })
                    }
                }
                OutlinedTextField(dest, { dest = it }, enabled = items == null, label = { Text("Adres docelowy 0x…") },
                    keyboardOptions = KeyboardOptions(autoCorrect = false))
                items?.let { list ->
                    if (list.isEmpty()) Text("Brak adresów z saldem tego aktywa.", color = Muted, fontSize = 12.sp)
                    list.forEach {
                        Text(if (it.note == null) "#${it.idx + 1} → ${fmtTok(it.amount, dec)} $sym" else "#${it.idx + 1} pominięty: ${it.note}",
                            fontSize = 12.sp, color = if (it.note == null) Color.White else Muted)
                    }
                    if (ok.isNotEmpty()) Text("Razem: ${fmtTok(ok.fold(BigInteger.ZERO) { a, b -> a + b.amount }, dec)} $sym z ${ok.size} adresów\nDo: $dest",
                        color = Warn, fontSize = 12.sp)
                }
                log.forEach { Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
            }
        },
        confirmButton = {
            Button(enabled = !busy && !running, onClick = {
                val list = items
                if (list == null) {
                    if (!WalletUtils.isValidAddress(dest.trim())) { say("Nieprawidłowy adres docelowy"); return@Button }
                    scope.launch {
                        busy = true
                        try {
                            val (l, g) = withContext(Dispatchers.IO) { Chain.plan(net, addrs, native, tb, tok, dest.trim()) }
                            items = l; gp = g
                        } catch (e: Exception) { say("RPC: ${e.message}") }
                        busy = false
                    }
                } else if (log.isEmpty() && ok.isNotEmpty()) {
                    Vault.load(act, Session.pw ?: CharArray(0), { mn, pass ->
                        scope.launch {
                            running = true
                            val g = gp!!
                            for (item in ok) {
                                val r = try {
                                    withContext(Dispatchers.IO) {
                                        val kp = Wallet.keyPair(mn, pass, item.idx)
                                        Chain.send(net, kp, dest.trim(), item.amount, Fee(g.tip, g.maxFee, item.gas), tok)
                                    }
                                    "✓ #${item.idx + 1}"
                                } catch (e: Exception) { "✗ #${item.idx + 1}: ${e.message}" }
                                log = log + r
                            }
                            running = false
                        }
                    }, say)
                } else onClose()
            }) { Text(when { items == null -> "Dalej"; log.isEmpty() && ok.isNotEmpty() -> "Wykonaj"; else -> "Zamknij" }) }
        },
        dismissButton = { TextButton(onClick = { if (!running) onClose() }) { Text("Anuluj") } })
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
                        "Maks. opłata: ${fmtTok(it.maxFee.multiply(it.gas), 18)} ${net.sym}\nSieć: ${net.name}" + (if (!net.testnet) "  (MAINNET!)" else ""),
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
                    Vault.load(act, Session.pw ?: CharArray(0), { mn, pass ->
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
