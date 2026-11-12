package com.cloudplay.bridge.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

data class Emulator(val name: String, val system: String, val folder: String, val format: String, val packages: List<String>)

val EMULATORS = listOf(
    Emulator("DuckStation", "بلايستيشن 1", "Games/PS1", "CHD", listOf("com.github.stenzek.duckstation")),
    Emulator("NetherSX2", "بلايستيشن 2", "Games/PS2", "CHD", listOf("xyz.aethersx2.android")),
    Emulator("PPSSPP", "PSP", "Games/PSP", "CSO", listOf("org.ppsspp.ppsspp", "org.ppsspp.ppssppgold")),
    Emulator("Dolphin", "جيم كيوب ووي", "Games/GC-Wii", "RVZ", listOf("org.dolphinemu.dolphinemu")),
    Emulator("Citra", "3DS", "Games/3DS", ".3ds / .cci", listOf("org.citra.citra_emu", "io.github.lime3ds.android")),
    Emulator("Yuzu", "سويتش", "Games/Switch", "XCI / NSP", listOf("org.yuzu.yuzu_emu", "dev.eden.eden_emulator")),
    Emulator("M64Plus FZ", "نينتندو 64", "Games/N64", ".z64", listOf("org.mupen64plusae.v3.fzurita", "org.mupen64plusae.v3.fzurita.pro")),
)

@Composable
fun EmulatorsScreen() {
    val context = LocalContext.current
    val pm = context.packageManager
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("المحاكيات", style = MaterialTheme.typography.headlineSmall)
            Text("في كل محاكي اختر مجلد الألعاب من CloudPlay، وحط الألعاب في وندرايف بالمجلد المكتوب تحت كل جهاز.",
                style = MaterialTheme.typography.bodySmall)
        }
        items(EMULATORS) { emu ->
            val launch: Intent? = remember(emu) { emu.packages.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) } }
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(emu.name, style = MaterialTheme.typography.titleMedium)
                        Text("${emu.system} · ${emu.folder}", style = MaterialTheme.typography.bodySmall)
                        Text("الصيغة الأفضل: ${emu.format}", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.width(8.dp))
                    if (launch != null) {
                        Button(onClick = { context.startActivity(launch) }) { Text("فتح") }
                    } else {
                        Text("غير مثبت", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
