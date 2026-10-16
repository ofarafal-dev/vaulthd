package dev.ofarafal.vaulthd

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun Section(title: String) =
    Text(title, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp))

@Composable
fun Group(content: @Composable ColumnScope.() -> Unit) =
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Column(Modifier.padding(horizontal = 14.dp), content = content)
    }

@Composable
fun SRow(title: String, value: String? = null, danger: Boolean = false, chevron: Boolean = false, last: Boolean = false,
         trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable(enabled = onClick != null) { onClick?.invoke() }.padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 14.sp, color = if (danger) Warn else Tx)
        value?.let { Text(it, color = Muted, fontSize = 13.sp) }
        trailing?.invoke()
        if (chevron) Text("  ›", color = Muted, fontSize = 18.sp)
    }
    if (!last) Box(Modifier.fillMaxWidth().height(0.5.dp).background(Muted.copy(alpha = 0.2f)))
}

@Composable
fun ChoiceDialog(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit, onClose: () -> Unit,
                 swatches: List<Pal>? = null) {
    AlertDialog(onDismissRequest = onClose, title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEachIndexed { i, o ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(i); onClose() }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = i == selected, onClick = { onPick(i); onClose() })
                        swatches?.getOrNull(i)?.let { p ->
                            Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                listOf(p.bg, p.ac, p.a2).forEach { c ->
                                    Box(Modifier.size(14.dp).background(Color(c), CircleShape).border(0.5.dp, Muted, CircleShape))
                                }
                            }
                        }
                        Text(o)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Zamknij") } })
}

@Composable
fun RpcDialog(onClose: () -> Unit, say: (String) -> Unit) {
    val vals = remember { mutableStateMapOf<Long, String>().apply { NETS.forEach { put(it.chainId, Prefs.rpcOverride(it.chainId)) } } }
    AlertDialog(onDismissRequest = onClose, title = { Text("Własny serwer RPC") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Puste pole = serwer domyślny. Tylko adresy https://. Serwer widzi Twoje adresy i zapytania.", color = Muted, fontSize = 12.sp)
                NETS.forEach { n ->
                    OutlinedTextField(vals[n.chainId] ?: "", { vals[n.chainId] = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(n.name) }, placeholder = { Text(n.rpc, fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(autoCorrect = false, keyboardType = KeyboardType.Uri))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val bad = vals.entries.firstOrNull { it.value.isNotBlank() && !it.value.trim().startsWith("https://") }
                if (bad != null) say("Adres RPC musi zaczynać się od https://")
                else { vals.forEach { (k, v) -> Prefs.setRpc(k, v) }; say("Zapisano RPC. Odśwież salda."); onClose() }
            }) { Text("Zapisz") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Anuluj") } })
}

@Composable
fun ChangePwDialog(act: FragmentActivity, onBioOff: () -> Unit, onClose: () -> Unit, say: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var old by remember { mutableStateOf("") }
    var n1 by remember { mutableStateOf("") }
    var n2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val kb = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false)
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text("Zmień hasło") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(old, { old = it }, Modifier.fillMaxWidth(), label = { Text("Obecne hasło") },
                    keyboardOptions = kb, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(n1, { n1 = it }, Modifier.fillMaxWidth(), label = { Text("Nowe hasło (min. 8 znaków)") },
                    keyboardOptions = kb, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(n2, { n2 = it }, Modifier.fillMaxWidth(), label = { Text("Powtórz nowe hasło") },
                    keyboardOptions = kb, visualTransformation = PasswordVisualTransformation())
                Text("Seed zostanie zaszyfrowany od nowa (dwa potwierdzenia biometrią). Odblokowanie odciskiem trzeba będzie włączyć ponownie.",
                    color = Muted, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(enabled = !busy && old.isNotEmpty() && n1.length >= 8 && n1 == n2, onClick = {
                busy = true
                val oc = old.toCharArray(); val nc = n1.toCharArray()
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { Vault.verify(act, oc) }
                    if (!ok) { say("Złe obecne hasło"); busy = false; return@launch }
                    Vault.load(act, oc, { mn, pass ->
                        Vault.save(act, mn, pass, Vault.addresses(act), nc, {
                            Session.pw?.fill('\u0000'); Session.pw = nc
                            Vault.bioDisable(act); onBioOff()
                            say("Hasło zmienione. Odblokowanie odciskiem wyłączone - włącz je ponownie w ustawieniach.")
                            busy = false; onClose()
                        }, { say(it); busy = false })
                    }, { say(it); busy = false })
                }
            }) { Text(if (busy) "Pracuję…" else "Zmień") }
        },
        dismissButton = { TextButton(onClick = { if (!busy) onClose() }) { Text("Anuluj") } })
}

@Composable
fun SettingsScreen(act: FragmentActivity, addrs: List<String>, bioOn: Boolean, onBio: (Boolean) -> Unit,
                   onBioOff: () -> Unit, onWipe: () -> Unit, openTokens: () -> Unit, say: (String) -> Unit) {
    var dlg by remember { mutableStateOf("") }
    var disguised by remember { mutableStateOf(isDisguised(act)) }
    val lockOpts = listOf(15, 30, 60, 300); val lockLbl = listOf("15 s", "30 s", "1 min", "5 min")
    val hideOpts = listOf(0, 1, 5, 20); val hideLbl = listOf("Wyłączone", "poniżej \$1", "poniżej \$5", "poniżej \$20")
    val feeLbl = listOf("Wolna", "Normalna", "Szybka")
    val modeLbl = listOf("Auto (jak telefon)", "Ciemny", "Jasny")
    val close = { dlg = "" }
    val ver = remember { runCatching { act.packageManager.getPackageInfo(act.packageName, 0).versionName }.getOrNull() ?: "?" }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
        Text("Ustawienia", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Section("Bezpieczeństwo")
        Group {
            SRow("Odblokowanie odciskiem", trailing = {
                Switch(checked = bioOn, enabled = bioOn || Vault.bioAvailable(act), onCheckedChange = { onBio(it) })
            })
            SRow("Zmień hasło", chevron = true) { dlg = "pw" }
            SRow("Blokuj po", value = lockLbl[lockOpts.indexOf(Prefs.lockSec).coerceAtLeast(0)], chevron = true) { dlg = "lock" }
            SRow("Ukryj ikonę (Notatnik)", last = true, trailing = {
                Switch(checked = disguised, onCheckedChange = { dlg = "disguise" })
            })
        }
        Section("Wygląd")
        Group {
            SRow("Motyw", value = modeLbl[Prefs.themeMode.coerceIn(0, 2)], chevron = true) { dlg = "mode" }
            SRow("Paleta ciemna", value = Theme.dark[Prefs.darkIdx.coerceIn(0, Theme.dark.size - 1)].name, chevron = true) { dlg = "dark" }
            SRow("Paleta jasna", value = Theme.light[Prefs.lightIdx.coerceIn(0, Theme.light.size - 1)].name, chevron = true) { dlg = "light" }
            SRow("Ukryj salda", value = hideLbl[hideOpts.indexOf(Prefs.hideBelow).coerceAtLeast(0)], chevron = true, last = true) { dlg = "hide" }
        }
        Section("Sieci i tokeny")
        Group {
            SRow("Własny serwer RPC", chevron = true) { dlg = "rpc" }
            SRow("Szybkość opłaty", value = feeLbl[Prefs.feeSpeed.coerceIn(0, 2)], chevron = true) { dlg = "fee" }
            SRow("Zarządzaj tokenami", chevron = true, last = true) { openTokens() }
        }
        Section("Dane")
        Group {
            SRow("Eksport adresów", chevron = true) {
                val text = "nr,adres\n" + addrs.mapIndexed { i, a -> "${i + 1},$a" }.joinToString("\n")
                act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                }, "Eksport adresów"))
            }
            SRow("O aplikacji", value = ver, chevron = true, last = true) { dlg = "about" }
        }
        Spacer(Modifier.height(12.dp))
        Group { SRow("Usuń portfel z telefonu", danger = true, last = true) { dlg = "wipe" } }
    }

    when (dlg) {
        "pw" -> ChangePwDialog(act, onBioOff, close, say)
        "lock" -> ChoiceDialog("Blokuj po", lockLbl, lockOpts.indexOf(Prefs.lockSec), { Prefs.lockSec = lockOpts[it]; Prefs.save() }, close)
        "mode" -> ChoiceDialog("Motyw", modeLbl, Prefs.themeMode, { Prefs.themeMode = it; Prefs.save() }, close)
        "dark" -> ChoiceDialog("Paleta ciemna", Theme.dark.map { it.name }, Prefs.darkIdx,
            { Prefs.darkIdx = it; Prefs.save() }, close, Theme.dark)
        "light" -> ChoiceDialog("Paleta jasna", Theme.light.map { it.name }, Prefs.lightIdx,
            { Prefs.lightIdx = it; Prefs.save() }, close, Theme.light)
        "hide" -> ChoiceDialog("Ukryj adresy z saldem", hideLbl, hideOpts.indexOf(Prefs.hideBelow),
            { Prefs.hideBelow = hideOpts[it]; Prefs.save() }, close)
        "fee" -> ChoiceDialog("Szybkość opłaty", feeLbl, Prefs.feeSpeed, { Prefs.feeSpeed = it; Prefs.save() }, close)
        "rpc" -> RpcDialog(close, say)
        "disguise" -> AlertDialog(onDismissRequest = close,
            title = { Text(if (disguised) "Pokazać ikonę VaultHD?" else "Ukryć aplikację?") },
            text = { Text(if (disguised) "W launcherze znów pojawi się ikona VaultHD."
                else "W launcherze aplikacja będzie wyglądać jak „Notatnik” (inna ikona i nazwa). Zmiana może chwilowo zamknąć apkę - uruchom ją ponownie z ikony Notatnika.") },
            confirmButton = { Button(onClick = { disguised = !disguised; setDisguise(act, disguised); close() }) { Text("OK") } },
            dismissButton = { TextButton(onClick = close) { Text("Anuluj") } })
        "wipe" -> AlertDialog(onDismissRequest = close,
            title = { Text("Usunąć portfel z tego telefonu?") },
            text = { Text("Zaszyfrowany seed zostanie skasowany. Bez zapisanego seeda (12 słów) stracisz dostęp do środków. Potem wkleisz własny seed lub wygenerujesz nowy.") },
            confirmButton = { Button(onClick = { close(); onWipe() }) { Text("Usuń") } },
            dismissButton = { TextButton(onClick = close) { Text("Anuluj") } })
        "about" -> AlertDialog(onDismissRequest = close, title = { Text("VaultHD $ver") },
            text = { Text("Portfel EVM z jednego seeda (50 adresów). Klucze nie opuszczają telefonu. Aplikacja nie była audytowana.\n\nDane zewnętrzne: publiczne serwery RPC, Blockscout (historia, tokeny), DefiLlama (ceny), CoinGecko (lista top 200).") },
            confirmButton = { TextButton(onClick = close) { Text("Zamknij") } })
    }
}
