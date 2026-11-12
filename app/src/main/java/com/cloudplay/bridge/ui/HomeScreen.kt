package com.cloudplay.bridge.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cloudplay.bridge.core.ChunkCache.Companion.GB
import com.cloudplay.bridge.core.Core
import com.cloudplay.bridge.core.Instance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val CLIENT_ID_FORMAT =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

@Composable
fun HomeScreen(core: Instance) {
    val signedIn by core.auth.signedIn.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ألعابي السحابية", style = MaterialTheme.typography.headlineSmall)
        if (signedIn) AccountCard(core) else SignInCard(core)
        CacheCard(core)
        HowToCard()
    }
}

@Composable
private fun SignInCard(core: Instance) {
    val context = LocalContext.current
    var clientId by remember { mutableStateOf(core.settings.clientId) }
    var error by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ربط وندرايف", style = MaterialTheme.typography.titleMedium)
            Text("الصق رقم التطبيق (Client ID) اللي سجلته في Azure. الخطوات في ملف README.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it.trim(); error = null },
                label = { Text("Client ID") },
                singleLine = true,
                isError = error != null,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(
                onClick = {
                    if (!CLIENT_ID_FORMAT.matches(clientId)) {
                        error = "الرقم غير صحيح. شكله لازم يكون: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                    } else {
                        core.settings.clientId = clientId
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, core.auth.buildSignInUri()))
                        } catch (e: ActivityNotFoundException) {
                            error = "ما لقيت متصفح على الجوال."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("تسجيل الدخول بحساب مايكروسوفت") }
        }
    }
}

@Composable
private fun AccountCard(core: Instance) {
    val context = LocalContext.current
    var quota by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var quotaError by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try { quota = withContext(Dispatchers.IO) { core.graph.quota() } } catch (e: Exception) { quotaError = true }
    }
    Card(Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("وندرايف متصل", style = MaterialTheme.typography.titleMedium)
                    Text("قراءة فقط · بيانات الدخول مشفّرة", style = MaterialTheme.typography.bodySmall)
                }
            }
            quota?.let { (used, total) -> Text("المستخدم في وندرايف: ${formatBytes(used)} من ${formatBytes(total)}") }
            if (quotaError) Text("ما قدرت أجيب مساحة وندرايف. تأكد من النت.")
            OutlinedButton(onClick = { core.auth.signOut(); Core.notifyRootsChanged(context) }) { Text("تسجيل الخروج") }
        }
    }
}

@Composable
private fun CacheCard(core: Instance) {
    val scope = rememberCoroutineScope()
    var used by remember { mutableLongStateOf(0L) }
    var maxGb by remember { mutableFloatStateOf(core.settings.cacheMaxGb.toFloat()) }
    LaunchedEffect(Unit) {
        while (true) {
            used = withContext(Dispatchers.IO) { core.cache.usedBytes() }
            delay(3000)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("الكاش", style = MaterialTheme.typography.titleMedium)
            val max = maxGb.toLong() * GB
            LinearProgressIndicator(progress = { (used.toFloat() / max).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text("${formatBytes(used)} من ${maxGb.toInt()} جيقا · يمسح القديم تلقائيًا", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = maxGb,
                onValueChange = { maxGb = it },
                onValueChangeFinished = {
                    core.settings.cacheMaxGb = maxGb.toInt()
                    scope.launch(Dispatchers.IO) { core.cache.trim() }
                },
                valueRange = 1f..32f,
                steps = 30,
            )
            Text("للسويتش والبلايستيشن 2 خله 6 جيقا أو أكثر.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                scope.launch(Dispatchers.IO) { core.cache.clear(); used = 0L }
            }) { Text("مسح الكاش") }
        }
    }
}

@Composable
private fun HowToCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("طريقة الربط", style = MaterialTheme.typography.titleMedium)
            Text("1. سجّل دخولك بحساب مايكروسوفت.")
            Text("2. افتح المحاكي، وروح لإعداد مجلد الألعاب.")
            Text("3. من قائمة الملفات اختر CloudPlay.")
            Text("4. اختر مجلد الجهاز (مثلًا Games/PS1) واضغط استخدام هذا المجلد.")
        }
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= GB -> String.format(Locale.US, "%.1f جيقا", bytes.toDouble() / GB)
    else -> String.format(Locale.US, "%d ميقا", bytes / (1024 * 1024))
}
