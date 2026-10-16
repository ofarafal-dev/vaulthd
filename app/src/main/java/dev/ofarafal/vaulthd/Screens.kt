package dev.ofarafal.vaulthd

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Stan współdzielony między zakładkami (Portfel -> Aktywność / Ustawienia). */
object Shared {
    var net by mutableStateOf<Net?>(null)
    var held by mutableStateOf<List<Int>>(emptyList())
    var openAdd by mutableStateOf(false)
}

@Composable
fun Ico(kind: String, color: Color, dp: Dp = 22.dp, rot: Float = 0f) {
    Canvas(Modifier.size(dp).rotate(rot)) {
        val w = size.width
        val sw = w * 0.09f
        val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            "wallet" -> {
                drawRoundRect(color, Offset(w * .12f, w * .26f), Size(w * .76f, w * .52f), CornerRadius(w * .1f), style = st)
                drawLine(color, Offset(w * .12f, w * .4f), Offset(w * .88f, w * .4f), sw, StrokeCap.Round)
                drawCircle(color, w * .045f, Offset(w * .72f, w * .6f))
            }
            "clock" -> {
                drawCircle(color, w * .38f, Offset(w / 2, w / 2), style = st)
                drawLine(color, Offset(w / 2, w / 2), Offset(w / 2, w * .3f), sw, StrokeCap.Round)
                drawLine(color, Offset(w / 2, w / 2), Offset(w * .66f, w * .56f), sw, StrokeCap.Round)
            }
            "sliders" -> {
                listOf(.28f to .65f, .5f to .35f, .72f to .55f).forEach { (y, x) ->
                    drawLine(color, Offset(w * .14f, w * y), Offset(w * .86f, w * y), sw, StrokeCap.Round)
                    drawCircle(color, w * .09f, Offset(w * x, w * y))
                }
            }
            "chev" -> {
                drawLine(color, Offset(w * .28f, w * .4f), Offset(w * .5f, w * .62f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * .5f, w * .62f), Offset(w * .72f, w * .4f), sw, StrokeCap.Round)
            }
        }
    }
}

@Composable
fun BottomNav(tab: Int, onTab: (Int) -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Muted.copy(alpha = 0.25f)))
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            listOf("wallet" to "Portfel", "clock" to "Aktywność", "sliders" to "Ustawienia").forEachIndexed { i, (ic, lb) ->
                val c = if (tab == i) Accent else Muted
                Column(Modifier.weight(1f).clickable { onTab(i) }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Ico(ic, c)
                    Text(lb, color = c, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun ActivityScreen(act: FragmentActivity, addrs: List<String>) {
    val net = Shared.net
    val held = Shared.held
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Pair<Int, Hist>>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    fun load(all: Boolean) {
        val n = net ?: return
        scope.launch {
            loading = true; status = ""
            val idxs = if (all) addrs.indices.toList() else held.take(25)
            val out = ArrayList<Pair<Int, Hist>>()
            withContext(Dispatchers.IO) {
                for ((k, i) in idxs.withIndex()) {
                    runCatching { Api.history(n, addrs[i]) }.onSuccess { l -> l.forEach { out.add(i to it) } }
                    status = "Wczytuję ${k + 1}/${idxs.size}"
                    Thread.sleep(120)
                }
            }
            items = out.sortedByDescending { it.second.ts }.take(100)
            loading = false; loaded = true; status = ""
        }
    }
    Column(Modifier.fillMaxSize()) {
        Text("Aktywność", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(if (net == null) "Najpierw otwórz zakładkę Portfel" else "${net.name} · adresy z saldem: ${held.size}",
            color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        if (net != null && net.bs == null) {
            Text("Historia niedostępna w tej sieci (brak indeksera). Otwórz adres w eksploratorze.", color = Warn, fontSize = 13.sp)
        } else if (net != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { load(false) }, enabled = !loading) { Text(if (loading) "Ładuję…" else "Wczytaj (adresy z saldem)") }
                OutlinedButton(onClick = { load(true) }, enabled = !loading) { Text("Wszystkie 50") }
            }
            if (status.isNotEmpty()) Text(status, color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            if (loaded && items.isEmpty()) Text("Brak transakcji.", color = Muted, fontSize = 13.sp)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items) { (i, h) ->
                    Card(Modifier.fillMaxWidth().clickable { openUrl(act, "${net.explorer}/tx/${h.hash}") },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBg)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text((if (h.out) "↑ Wysłano" else "↓ Odebrano") + (if (!h.ok) " (błąd)" else "") + "  ·  #${i + 1}", fontSize = 13.sp)
                                Text("${h.peer.take(8)}…${h.peer.takeLast(4)} · ${h.ts.take(16).replace('T', ' ')}", color = Muted, fontSize = 11.sp)
                            }
                            Text("${if (h.out) "-" else "+"}${h.amount} ${h.sym}", fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                color = if (h.out) Tx else Accent2)
                        }
                    }
                }
            }
        }
    }
}
