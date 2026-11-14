package com.cloudplay.bridge.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cloudplay.bridge.core.DriveItem
import com.cloudplay.bridge.core.GraphClient.Companion.ROOT_ID
import com.cloudplay.bridge.core.Instance
import com.cloudplay.bridge.core.Pinned
import com.cloudplay.bridge.core.Stats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Pick a game in OneDrive and download it fully so the emulator plays it at local speed. */
@Composable
fun PrepareScreen(core: Instance) {
    val progress by core.preloader.state.collectAsState()
    var pinned by remember { mutableStateOf(core.settings.pinned) }
    var path by remember { mutableStateOf(listOf(ROOT_ID to "OneDrive")) }
    var children by remember { mutableStateOf<List<DriveItem>?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<DriveItem?>(null) }

    KeepScreenOn(progress?.running == true)
    LaunchedEffect(progress?.finished, progress?.error) { pinned = core.settings.pinned }
    LaunchedEffect(path) {
        children = null
        loadError = false
        try {
            children = withContext(Dispatchers.IO) { core.graph.children(path.last().first) }
                .sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase() }))
        } catch (e: Exception) {
            loadError = true
        }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("تجهيز الألعاب", style = MaterialTheme.typography.headlineSmall)
            Text(
                "ألعاب السويتش والبلايستيشن 2 كبيرة وما تمشي من النت مباشرة. جهّزها هنا مرة وحدة " +
                    "(تتحمّل على الجوال)، وبعدها تشتغل من المحاكي بسرعة كأنها على الجوال. خل التطبيق مفتوح لين يخلص.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        progress?.let { p -> item { ProgressCard(core, p) } }
        if (pinned.isNotEmpty()) {
            item { Text("ألعاب جاهزة", style = MaterialTheme.typography.titleMedium) }
            items(pinned, key = { it.key }) { pin ->
                PinnedRow(core, pin, running = progress?.running == true && progress?.name == pin.name) {
                    core.preloader.remove(pin)
                    pinned = core.settings.pinned
                }
            }
        }
        item {
            Text("اختر اللعبة من وندرايف", style = MaterialTheme.typography.titleMedium)
            Text(path.joinToString(" / ") { it.second }, style = MaterialTheme.typography.bodySmall)
            if (path.size > 1) {
                TextButton(onClick = { path = path.dropLast(1) }) { Text("→ رجوع") }
            }
        }
        when {
            loadError -> item {
                Text("ما قدرت أفتح المجلد. تأكد من النت وإنك مسجل دخول.", color = MaterialTheme.colorScheme.error)
            }
            children == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            else -> items(children!!, key = { it.id }) { child ->
                Card(
                    Modifier.fillMaxWidth().clickable {
                        if (child.isFolder) path = path + (child.id to child.name) else confirm = child
                    }
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (child.isFolder) "📁" else "🎮")
                        Spacer(Modifier.width(12.dp))
                        Text(child.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!child.isFolder) Text(formatBytes(child.size), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    confirm?.let { item ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("تجهيز اللعبة؟") },
            text = { Text("${item.name}\n\nبيتحمّل ${formatBytes(item.size)} على الجوال. خل CloudPlay مفتوح والنت شغال لين يخلص.") },
            confirmButton = {
                Button(onClick = { core.preloader.start(item); confirm = null }) { Text("جهّز") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("إلغاء") } },
        )
    }
}

@Composable
private fun ProgressCard(core: Instance, p: com.cloudplay.bridge.core.Preloader.Progress) {
    var speed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(p.running) {
        var last = Stats.bytes.get()
        while (p.running) {
            delay(1000)
            val now = Stats.bytes.get()
            speed = now - last
            last = now
        }
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val fraction = if (p.total > 0) p.done.toFloat() / p.total else 0f
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            when {
                p.error != null -> Text(p.error, color = MaterialTheme.colorScheme.error)
                p.finished -> Text("جاهزة ✅ شغّلها من المحاكي الحين.")
                else -> {
                    Text("${formatBytes(p.done)} من ${formatBytes(p.total)} · ${(fraction * 100).toInt()}٪ · ${formatBytes(speed)}/ث")
                    OutlinedButton(onClick = { core.preloader.cancel() }) { Text("إيقاف") }
                }
            }
        }
    }
}

@Composable
private fun PinnedRow(core: Instance, pin: Pinned, running: Boolean, onDelete: () -> Unit) {
    var stored by remember(pin.key) { mutableLongStateOf(0L) }
    LaunchedEffect(pin.key, running) {
        do {
            stored = withContext(Dispatchers.IO) { core.cache.bytesFor(pin.key) }
            if (running) delay(2000)
        } while (running)
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pin.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val complete = stored >= pin.size
                Text(
                    if (complete) "جاهزة · ${formatBytes(pin.size)}" else "ناقصة · ${formatBytes(stored)} من ${formatBytes(pin.size)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!running) {
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "حذف") }
            }
        }
    }
}

@Composable
private fun KeepScreenOn(on: Boolean) {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(on) {
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
