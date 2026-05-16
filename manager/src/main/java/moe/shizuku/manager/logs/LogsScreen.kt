@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package moe.shizuku.manager.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.module.AdbModule
import moe.shizuku.manager.module.AdbModuleManager
import moe.shizuku.manager.ui.compose.MonospaceLog
import moe.shizuku.manager.ui.compose.SettingsRow
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold

@Composable
fun LogsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var modules by remember { mutableStateOf<List<AdbModule>>(emptyList()) }
    var selectedLog by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Reload modules every time this composable enters the composition
    LaunchedEffect(Unit) {
        modules = AdbModuleManager.listModules(context)
    }

    ShizukuLazyScaffold(
        title = "Module Logs",
        onNavigateUp = null
    ) {
        if (modules.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 1.dp
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "No logs available",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Install and run ADB Modules to see their execution logs here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        items(modules, key = { it.id }) { module ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        text = module.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                    )

                    SettingsRow(
                        icon = R.drawable.ic_outline_play_arrow_24,
                        title = "Last Action Log",
                        summary = "View the output of the last action.sh run",
                        onClick = {
                            scope.launch {
                                val content = withContext(Dispatchers.IO) {
                                    readLogSafe(module, "action")
                                }
                                selectedLog = "Action Log: ${module.name}" to content
                            }
                        }
                    )

                    SettingsRow(
                        icon = R.drawable.ic_terminal_24,
                        title = "Last Service Log",
                        summary = "View the output of the last service.sh run",
                        onClick = {
                            scope.launch {
                                val content = withContext(Dispatchers.IO) {
                                    readLogSafe(module, "service")
                                }
                                selectedLog = "Service Log: ${module.name}" to content
                            }
                        }
                    )
                }
            }
        }
    }

    selectedLog?.let { (title, content) ->
        AlertDialog(
            onDismissRequest = { selectedLog = null },
            title = { Text(title) },
            text = { MonospaceLog(content) },
            confirmButton = {
                TextButton(onClick = { selectedLog = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge
        )
    }
}

/**
 * Reads the log file safely on an IO thread.
 * Checks both the canonical log property and the direct filesystem path.
 */
private fun readLogSafe(module: AdbModule, type: String): String {
    return try {
        val logFile = when (type) {
            "action" -> module.lastActionLog
            "service" -> module.lastServiceLog
            else -> return "Unknown log type."
        }
        // Primary check: use the log file from the data class property
        if (logFile.exists() && logFile.isFile) {
            return readFileTail(logFile)
        }
        // Fallback: check the logs directory directly for any matching file
        val logsDir = module.logsDir
        if (logsDir.isDirectory) {
            val fallback = logsDir.resolve("$type-last.log")
            if (fallback.exists() && fallback.isFile) {
                return readFileTail(fallback)
            }
            // Also check if the module directory itself has a logs file
            val altLogFile = module.directory.resolve("$type-last.log")
            if (altLogFile.exists() && altLogFile.isFile) {
                return readFileTail(altLogFile)
            }
        }
        // Check if the logs directory even exists
        if (!logsDir.exists()) {
            "No logs yet — run the ${type}.sh script first.\n\nExpected path: ${logFile.absolutePath}"
        } else {
            val files = logsDir.listFiles()?.map { it.name } ?: emptyList()
            if (files.isEmpty()) {
                "Logs directory is empty — run the ${type}.sh script first.\n\nExpected path: ${logFile.absolutePath}"
            } else {
                "Log file not found.\nExpected: ${logFile.name}\nFiles in logs/: ${files.joinToString(", ")}\n\nPath: ${logFile.absolutePath}"
            }
        }
    } catch (e: Exception) {
        "Error reading log: ${e.message}\n${e.stackTraceToString().take(2000)}"
    }
}

private fun readFileTail(file: java.io.File): String {
    val limit = 64 * 1024L
    return try {
        if (file.length() <= limit) {
            file.readText().ifBlank { "Log file is empty." }
        } else {
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(file.length() - limit)
                // Skip past any UTF-8 continuation bytes (10xxxxxx) to avoid
                // landing in the middle of a multi-byte character sequence.
                var skipped = 0
                while (skipped < 4) {
                    val b = raf.read()
                    if (b == -1) break
                    skipped++
                    // A valid UTF-8 start byte is either 0xxxxxxx or 11xxxxxx.
                    // Continuation bytes match 10xxxxxx (0x80..0xBF).
                    if (b and 0xC0 != 0x80) {
                        // This byte is a valid start — seek back so it's included.
                        raf.seek(raf.filePointer - 1)
                        break
                    }
                }
                val remaining = (file.length() - raf.filePointer).toInt()
                val bytes = ByteArray(remaining)
                raf.readFully(bytes)
                "[Truncated...]\n" + String(bytes, Charsets.UTF_8).ifBlank { "Log file is empty." }
            }
        }
    } catch (e: Exception) {
        "Error reading log: ${e.message}"
    }
}
