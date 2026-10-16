package dev.ofarafal.vaulthd

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

data class Pal(val name: String, val dark: Boolean, val bg: Long, val s1: Long, val s2: Long, val tx: Long,
               val mu: Long, val ac: Long, val a2: Long, val on: Long, val warn: Long)

object Theme {
    val dark = listOf(
        Pal("Pomarańcz + morski", true, 0xFF0B0D10, 0xFF14181D, 0xFF1E242B, 0xFFEDF1F5, 0xFF8F9AA6, 0xFFFF8A3D, 0xFF3CC9B0, 0xFF1A0E05, 0xFFFF6B6B),
        Pal("Rdza na czerni", true, 0xFF0E0B09, 0xFF181310, 0xFF261E19, 0xFFF4EDE6, 0xFFA39385, 0xFFE8672C, 0xFFE8672C, 0xFF1F0C03, 0xFFFF6B6B),
        Pal("Mandarynka", true, 0xFF0A0A0B, 0xFF141416, 0xFF202024, 0xFFF5F5F5, 0xFF9D9DA6, 0xFFFFA23E, 0xFFFFA23E, 0xFF1F1204, 0xFFFF6B6B),
        Pal("Czerń i cyjan", true, 0xFF080B0E, 0xFF10151A, 0xFF19202A, 0xFFE6F1F5, 0xFF7F95A3, 0xFF22D3EE, 0xFF22D3EE, 0xFF02181D, 0xFFFBBF24),
        Pal("Czerń i zieleń", true, 0xFF050705, 0xFF0F130F, 0xFF181E18, 0xFFECF3EC, 0xFF8FA08F, 0xFF2FD27A, 0xFF2FD27A, 0xFF04170C, 0xFFFBBF24),
        Pal("Granat i koral", true, 0xFF0A0E1A, 0xFF111729, 0xFF1B2238, 0xFFE8ECF8, 0xFF8A93B0, 0xFFFF6F61, 0xFFFF6F61, 0xFF260804, 0xFFFBBF24),
        Pal("Grafit i turkus", true, 0xFF0E1116, 0xFF161B22, 0xFF1F252E, 0xFFE8ECF1, 0xFF8B95A3, 0xFF4FD1BE, 0xFF4FD1BE, 0xFF07201C, 0xFFFBBF24),
    )
    val light = listOf(
        Pal("Krem i pomarańcz", false, 0xFFFAF6F0, 0xFFFFFFFF, 0xFFF0E8DC, 0xFF231A12, 0xFF7A6A5B, 0xFFD9480F, 0xFF0F766E, 0xFFFFFFFF, 0xFFB42318),
        Pal("Mięta i grafit", false, 0xFFF2F7F5, 0xFFFFFFFF, 0xFFE3EDE9, 0xFF14201C, 0xFF5E7068, 0xFF11805F, 0xFF11805F, 0xFFFFFFFF, 0xFFB54708),
    )
    var sysDark by mutableStateOf(true)
    val pal: Pal
        get() = when (Prefs.themeMode) {
            1 -> dark[Prefs.darkIdx.coerceIn(0, dark.size - 1)]
            2 -> light[Prefs.lightIdx.coerceIn(0, light.size - 1)]
            else -> if (sysDark) dark[Prefs.darkIdx.coerceIn(0, dark.size - 1)] else light[Prefs.lightIdx.coerceIn(0, light.size - 1)]
        }
}

val Accent: Color get() = Color(Theme.pal.ac)
val Accent2: Color get() = Color(Theme.pal.a2)
val OnAccent: Color get() = Color(Theme.pal.on)
val Bg: Color get() = Color(Theme.pal.bg)
val CardBg: Color get() = Color(Theme.pal.s1)
val Chip: Color get() = Color(Theme.pal.s2)
val Tx: Color get() = Color(Theme.pal.tx)
val Muted: Color get() = Color(Theme.pal.mu)
val Warn: Color get() = Color(Theme.pal.warn)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val p = Theme.pal
    val cs = if (p.dark) darkColorScheme(
        primary = Color(p.ac), onPrimary = Color(p.on), background = Color(p.bg), onBackground = Color(p.tx),
        surface = Color(p.bg), onSurface = Color(p.tx), surfaceVariant = Color(p.s1), onSurfaceVariant = Color(p.mu),
        secondaryContainer = Color(p.ac).copy(alpha = 0.25f), onSecondaryContainer = Color(p.tx),
        tertiary = Color(p.warn), outline = Color(p.mu), outlineVariant = Color(p.s2),
    ) else lightColorScheme(
        primary = Color(p.ac), onPrimary = Color(p.on), background = Color(p.bg), onBackground = Color(p.tx),
        surface = Color(p.bg), onSurface = Color(p.tx), surfaceVariant = Color(p.s1), onSurfaceVariant = Color(p.mu),
        secondaryContainer = Color(p.ac).copy(alpha = 0.18f), onSecondaryContainer = Color(p.tx),
        tertiary = Color(p.warn), outline = Color(p.mu), outlineVariant = Color(p.s2),
    )
    MaterialTheme(colorScheme = cs, content = content)
}

@Composable
fun NetChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, fontSize = 12.sp) },
        colors = FilterChipDefaults.filterChipColors(containerColor = Chip, labelColor = Muted,
            selectedContainerColor = Accent, selectedLabelColor = OnAccent),
        border = null)
}
