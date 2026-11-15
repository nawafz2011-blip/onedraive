package com.cloudplay.bridge.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import com.cloudplay.bridge.BuildConfig
import com.cloudplay.bridge.core.Instance
import com.cloudplay.bridge.core.Stats
import com.cloudplay.bridge.core.Updater
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
        UpdateCard()
        if (signedIn) AccountCard(core) else SignInCard(core)
        ActivityCard()
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

private sealed interface UpdateState {
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val version: Int) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data object Installing : UpdateState
    data class Failed(val message: String) : UpdateState
}

@Composable
private fun UpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Checking) }

    fun check() {
        state = UpdateState.Checking
        scope.launch {
            state = try {
                val latest = withContext(Dispatchers.IO) { Updater.latestVersion() }
                if (latest > BuildConfig.VERSION_CODE) UpdateState.Available(latest) else UpdateState.UpToDate
            } catch (e: Exception) {
                UpdateState.Failed("ما قدرت أتحقق من التحديثات. تأكد من النت.")
            }
        }
    }

    fun update() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            state = UpdateState.Failed("فعّل «السماح من هذا المصدر» لـ CloudPlay، وارجع اضغط تحديث مرة ثانية.")
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            )
            return
        }
        state = UpdateState.Downloading(0f)
        scope.launch {
            state = try {
                val apk = withContext(Dispatchers.IO) {
                    Updater.download(context) { p -> state = UpdateState.Downloading(p) }
                }
                withContext(Dispatchers.IO) { Updater.install(context, apk) }
                UpdateState.Installing
            } catch (e: Exception) {
                UpdateState.Failed("فشل تنزيل التحديث. تأكد من النت وجرب مرة ثانية.")
            }
        }
    }

    LaunchedEffect(Unit) { check() }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("التحديثات · نسختك ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
            when (val s = state) {
                UpdateState.Checking -> Text("جاري التحقق...", style = MaterialTheme.typography.bodySmall)
                UpdateState.UpToDate -> {
                    Text("عندك آخر نسخة.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { check() }) { Text("تحقق مرة ثانية") }
                }
                is UpdateState.Available -> {
                    Text("فيه تحديث جديد! بياناتك والـ Client ID بتبقى مثل ما هي.",
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { update() }, modifier = Modifier.fillMaxWidth()) { Text("تحديث") }
                }
                is UpdateState.Downloading -> {
                    Text("جاري التنزيل...", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                }
                UpdateState.Installing ->
                    Text("اضغط «تحديث» أو «تثبيت» في الرسالة اللي بتطلع لك.", style = MaterialTheme.typography.bodySmall)
                is UpdateState.Failed -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { check() }) { Text("حاول مرة ثانية") }
                }
            }
        }
    }
}

@Composable
private fun ActivityCard() {
    var speed by remember { mutableLongStateOf(0L) }
    var total by remember { mutableLongStateOf(Stats.bytes.get()) }
    var errors by remember { mutableIntStateOf(Stats.errors.get()) }
    var lastError by remember { mutableStateOf(Stats.lastError) }
    LaunchedEffect(Unit) {
        var last = Stats.bytes.get()
        while (true) {
            delay(1000)
            val now = Stats.bytes.get()
            speed = now - last
            last = now
            total = now
            errors = Stats.errors.get()
            lastError = Stats.lastError
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("النشاط", style = MaterialTheme.typography.titleMedium)
            Text("السرعة الحين: ${formatBytes(speed)}/ث · المحمّل: ${formatBytes(total)}", style = MaterialTheme.typography.bodySmall)
            if (errors > 0) {
                Text("أخطاء: $errors", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            val context = LocalContext.current
            OutlinedButton(onClick = { shareDiagnostics(context) }) { Text("إرسال سجل التشخيص") }
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

private fun shareDiagnostics(context: android.content.Context) {
    val dir = java.io.File(context.cacheDir, "diag").apply { mkdirs() }
    val file = java.io.File(dir, "cloudplay-log.txt")
    file.writeText(com.cloudplay.bridge.core.Diag.dump(BuildConfig.VERSION_NAME))
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "com.cloudplay.bridge.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "إرسال سجل التشخيص"))
}
