@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package moe.shizuku.manager.module

import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.compose.MonospaceLog
import moe.shizuku.manager.ui.compose.ShizukuIcon
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold
import moe.shizuku.manager.ui.compose.ExpressiveSwitch

private val MODULE_MIME_TYPES = arrayOf(
    "application/zip",
    "application/octet-stream",
    "application/x-zip-compressed"
)

@Composable
fun ModulesScreen(onOpenWebUi: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var modules by remember { mutableStateOf<List<AdbModule>>(emptyList(), neverEqualPolicy()) }
    var output by remember { mutableStateOf<Pair<String, String>?>(null) }
    var deleteTarget by remember { mutableStateOf<AdbModule?>(null) }
    var pendingCommand by remember { mutableStateOf<ModuleCommandRequest?>(null) }
    var runningModuleId by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            modules = AdbModuleManager.listModules(context)
        }
    }

    fun runModuleAction(module: AdbModule) {
        scope.launch {
            runningModuleId = module.id
            output = runCatching {
                val result = AdbModuleManager.runActionStreaming(module)
                context.getString(R.string.modules_action_result, result.exitCode) to result.combinedOutput
            }.getOrElse {
                context.getString(R.string.modules_action_failed) to (it.message ?: it.javaClass.simpleName)
            }
            runningModuleId = null
        }
    }

    val zipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                AdbModuleManager.install(context, uri)
            }.onSuccess { module ->
                Toast.makeText(
                    context,
                    context.getString(R.string.modules_install_success, module.name),
                    Toast.LENGTH_SHORT
                ).show()
                reload()
            }.onFailure {
                Toast.makeText(
                    context,
                    context.getString(R.string.modules_install_failed, it.message ?: it.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        modules = AdbModuleManager.listModules(context)
    }

    ShizukuLazyScaffold(
        title = stringResource(R.string.modules_title),
        onNavigateUp = null,
        actions = {
            FilledTonalButton(
                modifier = Modifier.height(36.dp),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                onClick = {
                    zipLauncher.launch(MODULE_MIME_TYPES)
                }
            ) {
                ShizukuIcon(
                    R.drawable.ic_outline_arrow_upward_24,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(16.dp)
                )
                Text(stringResource(R.string.modules_install_zip))
            }
        }
    ) {
        item {
            AnimatedContent(targetState = modules.isEmpty(), label = "module-empty-state") { empty ->
                if (empty) {
                    EmptyModulesCard(onInstall = { zipLauncher.launch(MODULE_MIME_TYPES) })
                }
            }
        }
        items(modules, key = { it.id }) { module ->
            ModuleCard(
                module = module,
                busy = runningModuleId == module.id,
                trusted = ModuleSettings.isModuleTrusted(module.id),
                modifier = Modifier.animateItem(),
                onToggle = {
                    scope.launch {
                        AdbModuleManager.setEnabled(module, !module.enabled)
                        reload()
                    }
                },
                onRunAction = {
                    if (!ModuleSettings.isModuleTrusted(module.id) && ModuleSettings.recommandForAction()) {
                        pendingCommand = ModuleCommandRequest(
                            module = module,
                            source = ModuleCommandSource.ACTION,
                            command = module.actionCommandPreview()
                        )
                    } else {
                        runModuleAction(module)
                    }
                },
                onRunService = {
                    scope.launch {
                        runningModuleId = module.id
                        output = runCatching {
                            val result = AdbModuleManager.runService(module)
                            context.getString(R.string.modules_service_result, result.exitCode) to result.combinedOutput
                        }.getOrElse {
                            context.getString(R.string.modules_service_failed) to (it.message ?: it.javaClass.simpleName)
                        }
                        runningModuleId = null
                    }
                },
                onOpenWebUi = {
                    onOpenWebUi(module.id)
                },
                onDelete = { deleteTarget = module },
                onTrustChange = { trusted ->
                    scope.launch {
                        ModuleSettings.setModuleTrusted(module.id, trusted)
                        AdbModuleManager.setEnabled(module, trusted)
                        reload()
                    }
                }
            )
        }
    }

    output?.let { (title, text) ->
        AlertDialog(
            onDismissRequest = { output = null },
            title = { Text(title) },
            text = { MonospaceLog(text) },
            confirmButton = {
                TextButton(onClick = { output = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge
        )
    }

    deleteTarget?.let { module ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.modules_delete_title)) },
            text = { Text(stringResource(R.string.modules_delete_message, module.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            AdbModuleManager.delete(module)
                            deleteTarget = null
                            reload()
                        }
                    }
                ) {
                    Text(stringResource(R.string.modules_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge
        )
    }

    pendingCommand?.let { request ->
        ReCommandDialog(
            request = request,
            busy = runningModuleId == request.module.id,
            onDismiss = { pendingCommand = null },
            onReject = { pendingCommand = null },
            onApprove = {
                pendingCommand = null
                runModuleAction(request.module)
            }
        )
    }
}

private fun AdbModule.actionCommandPreview(): String {
    val script = actionScript ?: return "sh action.sh"
    val content = runCatching { script.readText().trim() }.getOrDefault("")
    return buildString {
        append("sh ")
        append(script.absolutePath)
        if (content.isNotBlank()) {
            appendLine()
            appendLine()
            append(content)
        }
    }.take(MAX_RECOMMAND_COMMAND_CHARS)
}

private const val MAX_RECOMMAND_COMMAND_CHARS = 64 * 1024

@Composable
private fun EmptyModulesCard(onInstall: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.modules_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.modules_empty_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(onClick = onInstall) {
                ShizukuIcon(
                    R.drawable.ic_outline_arrow_upward_24,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(18.dp)
                )
                Text(stringResource(R.string.modules_install_zip))
            }
        }
    }
}

@Composable
private fun ModuleCard(
    module: AdbModule,
    busy: Boolean,
    trusted: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onRunAction: () -> Unit,
    onRunService: () -> Unit,
    onOpenWebUi: () -> Unit,
    onDelete: () -> Unit,
    onTrustChange: (Boolean) -> Unit
) {
    var expanded by remember(module.id) { mutableStateOf(true) }
    var showTrustAction by remember(module.id, trusted) { mutableStateOf(false) }
    val containerColor by animateColorAsState(
        targetValue = if (trusted) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else if (module.enabled) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLowest
        },
        animationSpec = tween(260),
        label = "module-container"
    )
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .combinedClickable(
                onClick = { expanded = !expanded },
                onLongClick = {
                    expanded = true
                    showTrustAction = !showTrustAction
                }
            )
            .animateContentSize(animationSpec = tween(280)),
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor,
        tonalElevation = 1.dp
    ) {
        Column {
            ModuleBanner(module)
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = module.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        ModuleInfoLine(label = R.string.modules_version_label, value = module.version ?: "-")
                        ModuleInfoLine(label = R.string.modules_author_label, value = module.author ?: "-")
                    }
                    ExpressiveSwitch(
                        checked = module.enabled,
                        onCheckedChange = { onToggle() }
                    )
                }
                module.description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) 4 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                ModuleChips(module, trusted)
                AnimatedVisibility(
                    visible = busy,
                    enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(120))
                ) {
                    RunningRow()
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(160))
                ) {
                    ModuleActions(
                        module = module,
                        busy = busy,
                        onRunAction = onRunAction,
                        onRunService = onRunService,
                        onOpenWebUi = onOpenWebUi,
                        onDelete = onDelete,
                        trusted = trusted,
                        showTrustAction = showTrustAction,
                        onTrustChange = {
                            showTrustAction = false
                            onTrustChange(it)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModuleChips(module: AdbModule, trusted: Boolean) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AssistChip(
            onClick = {},
            label = { Text(module.id) }
        )
        AssistChip(onClick = {}, label = { Text(module.formattedSize) })
        if (trusted) {
            AssistChip(onClick = {}, label = { Text(stringResource(R.string.modules_full_trust)) })
        }
    }
}

@Composable
private fun ModuleInfoLine(@StringRes label: Int, value: String) {
    Text(
        text = stringResource(label, value),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun RunningRow() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LoadingIndicator(Modifier.size(28.dp))
        Text(
            text = stringResource(R.string.modules_running),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ModuleActions(
    module: AdbModule,
    busy: Boolean,
    trusted: Boolean,
    showTrustAction: Boolean,
    onRunAction: () -> Unit,
    onRunService: () -> Unit,
    onOpenWebUi: () -> Unit,
    onDelete: () -> Unit,
    onTrustChange: (Boolean) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ModuleButton(
            label = R.string.modules_run_action,
            icon = R.drawable.ic_outline_play_arrow_24,
            primary = true,
            enabled = !busy && module.enabled && module.hasAction && ModuleSettings.canRunAction(module),
            onClick = onRunAction
        )
        ModuleButton(
            label = R.string.modules_open_webui,
            icon = R.drawable.ic_outline_open_in_new_24,
            enabled = !busy && module.hasWebUi,
            onClick = onOpenWebUi
        )
        ModuleButton(
            label = R.string.modules_run_service,
            icon = R.drawable.ic_terminal_24,
            enabled = !busy &&
                module.enabled &&
                module.hasService &&
                ModuleSettings.canRunService(module) &&
                ModuleSettings.canRunBackground(module),
            onClick = onRunService
        )
        if (showTrustAction) {
            ModuleButton(
                label = if (trusted) R.string.modules_untrust else R.string.modules_trust,
                icon = R.drawable.ic_warning_24,
                enabled = !busy,
                onClick = { onTrustChange(!trusted) }
            )
        }
        ModuleButton(
            label = R.string.modules_delete,
            icon = R.drawable.ic_close_24,
            enabled = !busy,
            danger = true,
            onClick = onDelete
        )
    }
}

@Composable
private fun ModuleButton(
    @StringRes label: Int,
    @DrawableRes icon: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    primary: Boolean = false,
    danger: Boolean = false
) {
    val modifier = Modifier.widthIn(min = 116.dp)
    val content: @Composable RowScope.() -> Unit = {
        ShizukuIcon(
            icon,
            modifier = Modifier
                .padding(end = 8.dp)
                .size(18.dp)
        )
        Text(stringResource(label))
    }
    when {
        primary -> Button(
            modifier = modifier,
            enabled = enabled,
            onClick = onClick,
            content = content
        )
        danger -> OutlinedButton(
            modifier = modifier,
            enabled = enabled,
            onClick = onClick,
            content = content
        )
        else -> FilledTonalButton(
            modifier = modifier,
            enabled = enabled,
            onClick = onClick,
            content = content
        )
    }
}

@Composable
private fun ModuleBanner(module: AdbModule) {
    val banner = module.banner ?: return
    val bitmap = remember(banner.absolutePath, banner.lastModified()) {
        runCatching { BitmapFactory.decodeFile(banner.absolutePath)?.asImageBitmap() }.getOrNull()
    } ?: return

    val overlayAlpha by animateColorAsState(
        targetValue = if (module.enabled) {
            androidx.compose.ui.graphics.Color.Transparent
        } else {
            androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.48f)
        },
        animationSpec = tween(260),
        label = "module-banner-disabled-overlay"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(116.dp)
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(overlayAlpha)
        )
    }
    Spacer(Modifier.height(2.dp))
}
