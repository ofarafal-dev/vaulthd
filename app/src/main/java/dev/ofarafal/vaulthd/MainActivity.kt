package dev.ofarafal.vaulthd

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.web3j.crypto.MnemonicUtils
import org.web3j.crypto.WalletUtils
import org.web3j.utils.Convert
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

class MainActivity : FragmentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { App(this) } } }
    }
}

fun fmt(w: BigInteger): String =
    Convert.fromWei(BigDecimal(w), Convert.Unit.ETHER).setScale(6, RoundingMode.DOWN).toPlainString()

@Composable
fun App(act: FragmentActivity) {
    var has by remember { mutableStateOf(Vault.exists(act)) }
    var addrs by remember { mutableStateOf(Vault.addresses(act)) }
    var msg by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("VaultHD", style = MaterialTheme.typography.headlineSmall)
        if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.tertiary, fontSize = 12.sp)
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { mn = Wallet.newMnemonic(); gen = true; w3 = ""; w7 = "" }) { Text("Generuj nowy seed (12 słów)") }
        OutlinedTextField(mn, { mn = it; gen = false }, Modifier.fillMaxWidth(),
            label = { Text("Seed BIP39 (lub wpisz, by zaimportować)") }, keyboardOptions = kb)
        OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(),
            label = { Text("Passphrase (opcjonalnie)") }, keyboardOptions = kb,
            visualTransformation = PasswordVisualTransformation())
        if (gen) {
            Text("Zapisz seed OFFLINE na papierze. Potwierdź słowa:")
            OutlinedTextField(w3, { w3 = it }, label = { Text("Słowo #3") }, keyboardOptions = kb)
            OutlinedTextField(w7, { w7 = it }, label = { Text("Słowo #7") }, keyboardOptions = kb)
        }
        Button(enabled = valid && quizOk, onClick = {
            val m = mn.trim()
            Vault.save(act, m, pass, Wallet.addresses(m, pass), { mn = ""; w3 = ""; w7 = ""; onDone() }, say)
        }) { Text("Zapisz w sejfie (biometria)") }
    }
}

@Composable
fun Main(act: FragmentActivity, addrs: List<String>, say: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var ni by remember { mutableIntStateOf(0) }
    var bal by remember { mutableStateOf<Map<String, BigInteger>>(emptyMap()) }
    var from by remember { mutableStateOf<Int?>(null) }
    val net = NETS[ni]
    fun refresh() { scope.launch {
        say("Pobieram salda…")
        try { bal = withContext(Dispatchers.IO) { Chain.balances(net, addrs) }; say("") }
        catch (e: Exception) { say("Błąd RPC: ${e.message}") }
    } }
    LaunchedEffect(ni) { bal = emptyMap(); refresh() }

    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NETS.forEachIndexed { i, n -> Button(enabled = ni != i, onClick = { ni = i }) { Text(n.name) } }
    }
    TextButton(onClick = { refresh() }) { Text("Odśwież salda") }
    LazyColumn {
        itemsIndexed(addrs) { i, a ->
            Row(Modifier.fillMaxWidth().clickable { from = i }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("#${i + 1} ${a.take(8)}…${a.takeLast(6)}", Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text(bal[a]?.let { "${fmt(it)} ${net.sym}" } ?: "…", fontSize = 13.sp)
                TextButton(onClick = {
                    act.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("addr", a))
                    say("Skopiowano #${i + 1}")
                }) { Text("Kopiuj") }
            }
        }
    }
    from?.let { SendDialog(act, it, addrs, net, { from = null; refresh() }, say) }
}

@Composable
fun SendDialog(act: FragmentActivity, idx: Int, addrs: List<String>, net: Net, onClose: () -> Unit, say: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var to by remember { mutableStateOf("") }
    var amt by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf<Fee?>(null) }
    var wei by remember { mutableStateOf(BigInteger.ZERO) }
    AlertDialog(onDismissRequest = onClose,
        title = { Text("Wyślij z #${idx + 1} (${net.name})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(addrs[idx], fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                OutlinedTextField(to, { to = it }, enabled = fee == null, label = { Text("Adres odbiorcy 0x…") },
                    keyboardOptions = KeyboardOptions(autoCorrect = false))
                OutlinedTextField(amt, { amt = it }, enabled = fee == null, label = { Text("Kwota (${net.sym})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                fee?.let {
                    Text("SPRAWDŹ:\nDo: ${to.trim()}\nKwota: $amt ${net.sym}\n" +
                        "Maks. opłata: ${fmt(it.maxFee.multiply(BigInteger.valueOf(GAS_NATIVE)))} ${net.sym}\nSieć: ${net.name}",
                        fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (fee == null) {
                    if (!WalletUtils.isValidAddress(to.trim())) { say("Nieprawidłowy adres"); return@Button }
                    wei = try { Convert.toWei(amt.trim(), Convert.Unit.ETHER).toBigInteger() }
                          catch (e: Exception) { say("Nieprawidłowa kwota"); return@Button }
                    scope.launch {
                        try { fee = withContext(Dispatchers.IO) { Chain.fee(net) } }
                        catch (e: Exception) { say("RPC: ${e.message}") }
                    }
                } else {
                    Vault.load(act, { mn, pass ->
                        scope.launch {
                            try {
                                val kp = Wallet.keyPair(mn, pass, idx)
                                val h = withContext(Dispatchers.IO) { Chain.send(net, kp, to.trim(), wei, fee!!) }
                                say("Wysłano: $h"); onClose()
                            } catch (e: Exception) { say("Błąd: ${e.message}") }
                        }
                    }, say)
                }
            }) { Text(if (fee == null) "Dalej" else "Podpisz") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Anuluj") } })
}
