@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package moe.shizuku.manager.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold
import moe.shizuku.server.IShizukuService
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun ComputScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var command by remember { mutableStateOf("pm list packages -3") }
    var outputLog by remember { mutableStateOf("Console initialized. Privileged Shevery service connected.\nReady for commands.") }
    var isRunning by remember { mutableStateOf(false) }
    var isAdbMode by remember { mutableStateOf(false) }
    var isExplaining by remember { mutableStateOf(false) }
    var aiExplanation by remember { mutableStateOf("") }
    var showReCommandPrompt by remember { mutableStateOf(false) }

    val errorColor = Color(0xFFEF5350)
    val warningColor = Color(0xFFFFB300)
    val normalColor = MaterialTheme.colorScheme.onSurfaceVariant

    val annotatedOutput = remember(outputLog) {
        buildAnnotatedLog(outputLog, errorColor, warningColor, normalColor)
    }

    fun runShellCommand(cmd: String) {
        if (cmd.isBlank()) return
        scope.launch {
            isRunning = true
            aiExplanation = ""
            
            val finalCmd = if (isAdbMode) {
                var trimmed = cmd.trim()
                if (trimmed.startsWith("adb ")) {
                    trimmed = trimmed.substring(4).trim()
                }
                if (trimmed.startsWith("shell ")) {
                    trimmed = trimmed.substring(6).trim()
                }
                if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length >= 2) {
                    trimmed = trimmed.substring(1, trimmed.length - 1).trim()
                } else if (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length >= 2) {
                    trimmed = trimmed.substring(1, trimmed.length - 1).trim()
                }
                trimmed
            } else {
                cmd.trim()
            }

            if (finalCmd.isBlank()) {
                outputLog = "[E] Error: Command translates to empty string."
                isRunning = false
                return@launch
            }

            if (isAdbMode) {
                outputLog = "ADB Command Translation Mode Active\nOriginal: $cmd\nTranslated: $finalCmd\nExecuting...\n"
            } else {
                outputLog = "Executing: $finalCmd ...\n"
            }
            
            val result = withContext(Dispatchers.IO) {
                if (!Shizuku.pingBinder()) {
                    return@withContext "Error: Privileged Shevery Service is not running. Please start the service first."
                }
                try {
                    val binder = Shizuku.getBinder() ?: return@withContext "Error: Unable to retrieve Shevery binder."
                    val service = IShizukuService.Stub.asInterface(binder)
                    val remote = service.newProcess(
                        arrayOf("sh", "-c", finalCmd),
                        null,
                        null
                    )
                    
                    val stdoutText = ParcelFileDescriptor.AutoCloseInputStream(remote.getInputStream()).bufferedReader().use { it.readText() }
                    val stderrText = ParcelFileDescriptor.AutoCloseInputStream(remote.getErrorStream()).bufferedReader().use { it.readText() }
                    
                    buildString {
                        if (stdoutText.isNotBlank()) append(stdoutText.trim())
                        if (stderrText.isNotBlank()) {
                            if (isNotEmpty()) append("\n")
                            append("[E] ")
                            append(stderrText.trim())
                        }
                        if (isEmpty()) append("Command completed with no output.")
                    }
                } catch (e: Exception) {
                    "[E] Shell execution failed: ${e.message}"
                }
            }
            outputLog = result
            isRunning = false
        }
    }

    fun requestRun() {
        if (ModuleSettings.isComputRecommandEnabled()) {
            showReCommandPrompt = true
        } else {
            runShellCommand(command)
        }
    }

    ShizukuLazyScaffold(
        title = "Comput",
        onNavigateUp = null
    ) {
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "🚀 ADB Comput Console",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = RoundedCornerShape(24.dp)
                            )
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val shModeColor = if (!isAdbMode) MaterialTheme.colorScheme.primary else Color.Transparent
                        val shTextColor = if (!isAdbMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        val adbModeColor = if (isAdbMode) MaterialTheme.colorScheme.primary else Color.Transparent
                        val adbTextColor = if (isAdbMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(20.dp))
                                .background(shModeColor)
                                .clickable { isAdbMode = false }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "sh Mode",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = shTextColor
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(20.dp))
                                .background(adbModeColor)
                                .clickable { isAdbMode = true }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "adb Mode",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = adbTextColor
                            )
                        }
                    }
                    
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp
                        ),
                        label = { Text("Command") },
                        placeholder = { Text("e.g. pm list packages") },
                        maxLines = 4,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { requestRun() },
                            enabled = !isRunning && command.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.large
                        ) {
                            if (isRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = "Run",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.size(6.dp))
                                Text("Run")
                            }
                        }
                        
                        TextButton(
                            onClick = {
                                command = ""
                                outputLog = "Console cleared."
                                aiExplanation = ""
                            },
                            enabled = !isRunning,
                            shape = MaterialTheme.shapes.large
                        ) {
                            Icon(Icons.Rounded.Clear, contentDescription = "Clear")
                            Spacer(Modifier.size(6.dp))
                            Text("Clear")
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Console Output",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("ADB Output", outputLog))
                                Toast.makeText(context, "Output copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = "Copy Output",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 320.dp)
                            .background(Color.Black.copy(alpha = 0.05f))
                            .verticalScroll(rememberScrollState()),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        SelectionContainer {
                            Text(
                                text = annotatedOutput,
                                modifier = Modifier.padding(12.dp),
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                            )
                        }
                    }
                }
            }
        }

        if (outputLog.isNotBlank() && !isRunning) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = "AI Explanation",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    text = "Gemini Explain",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            
                            Button(
                                onClick = {
                                    scope.launch {
                                        isExplaining = true
                                        val apiKey = ModuleSettings.getComputApiKey()
                                        aiExplanation = explainCommandWithGemini(command, outputLog, apiKey)
                                        isExplaining = false
                                    }
                                },
                                enabled = !isExplaining,
                                shape = MaterialTheme.shapes.large,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                if (isExplaining) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("Ask Gemini")
                                }
                            }
                        }
                        
                        if (aiExplanation.isNotBlank()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
                            SelectionContainer {
                                Text(
                                    text = aiExplanation,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showReCommandPrompt) {
        AlertDialog(
            onDismissRequest = { showReCommandPrompt = false },
            title = { Text("ReCommand confirmation") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Are you sure you want to execute this ADB command in Comput?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            text = command,
                            modifier = Modifier.padding(10.dp),
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showReCommandPrompt = false
                        runShellCommand(command)
                    }
                ) {
                    Text("Execute")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReCommandPrompt = false }) {
                    Text("Cancel")
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge
        )
    }
}

private fun buildAnnotatedLog(
    text: String,
    errorColor: Color,
    warningColor: Color,
    normalColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        val lines = text.split("\n")
        lines.forEachIndexed { index, line ->
            val isError = line.contains("error", ignoreCase = true) ||
                    line.contains("failed", ignoreCase = true) ||
                    line.contains("exception", ignoreCase = true) ||
                    line.contains("[E]", ignoreCase = true) ||
                    line.contains("denied", ignoreCase = true)
            val isWarning = line.contains("warn", ignoreCase = true) ||
                    line.contains("[W]", ignoreCase = true)

            if (isError) {
                withStyle(style = SpanStyle(color = errorColor, fontWeight = FontWeight.Bold)) {
                    append(line)
                }
            } else if (isWarning) {
                withStyle(style = SpanStyle(color = warningColor, fontWeight = FontWeight.SemiBold)) {
                    append(line)
                }
            } else {
                withStyle(style = SpanStyle(color = normalColor)) {
                    append(line)
                }
            }
            if (index < lines.lastIndex) {
                append("\n")
            }
        }
    }
}

private suspend fun explainCommandWithGemini(
    command: String,
    output: String,
    apiKey: String
): String = withContext(Dispatchers.IO) {
    if (apiKey.isBlank()) {
        return@withContext "Google AI Studio API Key is empty! Please configure it in Shevery Settings (Comput Console Settings)."
    }
    try {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")

        val prompt = "Explain the following shell command and its execution output in a clear, concise, and helpful developer-focused way. If there are errors or warnings, explain what caused them and how to resolve them:\n\nCommand: $command\n\nOutput:\n$output"
        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            })
        }

        conn.outputStream.use { os ->
            os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            os.flush()
        }

        val responseCode = conn.responseCode
        if (responseCode == 200) {
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)
            val text = json.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
            text.trim()
        } else {
            val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No details."
            "Gemini API returned error code $responseCode: $errText"
        }
    } catch (e: Exception) {
        "Failed to reach Gemini API: ${e.message ?: "Connection error."}"
    }
}
