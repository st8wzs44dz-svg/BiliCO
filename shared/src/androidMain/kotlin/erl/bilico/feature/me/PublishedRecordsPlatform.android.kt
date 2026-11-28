package erl.bilico.feature.me

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal actual fun rememberJsonFileExchanger(): JsonFileExchanger {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<(suspend () -> String)?>(null) }
    var pendingImport by remember { mutableStateOf<(suspend (String) -> Unit)?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val provider = pendingExport
        pendingExport = null
        if (uri == null || provider == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val json = provider()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入文件")
                }
            }.onSuccess {
                Toast.makeText(context, "发言记录已导出为 JSON", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(context, err.message ?: "导出失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val handler = pendingImport
        pendingImport = null
        if (uri == null || handler == null) return@rememberLauncherForActivityResult
        scope.launch {
            val raw = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }
            }.getOrNull()
            if (raw.isNullOrBlank()) {
                Toast.makeText(context, "读取文件失败或文件为空", Toast.LENGTH_SHORT).show()
                return@launch
            }
            handler(raw)
        }
    }

    return remember(exportLauncher, importLauncher) {
        object : JsonFileExchanger {
            override val supported: Boolean = true
            override fun exportJson(suggestedFileName: String, provideContent: suspend () -> String) {
                pendingExport = provideContent
                exportLauncher.launch(suggestedFileName)
            }

            override fun importJson(onContent: suspend (String) -> Unit) {
                pendingImport = onContent
                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            }
        }
    }
}
