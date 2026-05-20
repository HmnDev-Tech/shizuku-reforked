package moe.shizuku.manager.module

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.BufferedInputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

object AdbModuleManager {

    private const val MODULES_DIR = "adb_modules"
    private const val DISABLE_FILE = "disable"
    private const val MAX_ENTRY_COUNT = 2048
    private const val MAX_EXTRACTED_BYTES = 200L * 1024L * 1024L
    private const val MAX_SCRIPT_SECONDS = 120L
    private const val MAX_OUTPUT_CHARS = 64 * 1024
    private var servicesStartedForBinder = false
    private val idRegex = Regex("[A-Za-z][A-Za-z0-9._-]{1,63}")
    private val installMutexes = ConcurrentHashMap<String, Mutex>()

    // Live output for streaming module actions
    private val _liveOutput = MutableStateFlow<LiveModuleOutput?>(null)
    val liveOutput: StateFlow<LiveModuleOutput?> = _liveOutput.asStateFlow()

    data class LiveModuleOutput(
        val moduleId: String,
        val moduleName: String,
        val text: String,
        val running: Boolean
    )

    fun modulesRoot(context: Context): File {
        return File(context.filesDir, MODULES_DIR).apply { mkdirs() }
    }

    suspend fun listModules(context: Context): List<AdbModule> = withContext(Dispatchers.IO) {
        modulesRoot(context)
            .listFiles { file -> file.isDirectory }
            ?.mapNotNull(::readModule)
            ?.sortedWith(compareBy<AdbModule> { !it.enabled }.thenBy { it.name.lowercase(Locale.ROOT) })
            .orEmpty()
    }

    suspend fun install(context: Context, uri: Uri): AdbModule = withContext(Dispatchers.IO) {
        val temp = File.createTempFile("module-", ".zip", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open module ZIP." }
                temp.outputStream().use { output -> input.copyTo(output) }
            }

            ZipFile(temp).use { zip ->
                val propEntry = zip.entries().asSequence()
                    .firstOrNull { !it.isDirectory && it.name.trim('/') == "module.prop" }
                    ?: error("module.prop is missing.")
                val props = zip.getInputStream(propEntry).use { parseModuleProp(it.bufferedReader().readText()) }
                val id = props["id"]?.trim().orEmpty()
                require(idRegex.matches(id)) { "Invalid module id: $id" }

                installMutexes.getOrPut(id) { Mutex() }.withLock {
                    val target = File(modulesRoot(context), id)
                    val staging = File(modulesRoot(context), ".$id.installing")
                    staging.deleteRecursively()
                    staging.mkdirs()

                    var entryCount = 0
                    var extractedBytes = 0L
                    zip.entries().asSequence().forEach { entry ->
                        entryCount++
                        require(entryCount <= MAX_ENTRY_COUNT) { "Module ZIP has too many files." }
                        val cleanName = cleanZipName(entry.name)
                        val outFile = File(staging, cleanName)
                        ensureInside(staging, outFile)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            BufferedInputStream(zip.getInputStream(entry)).use { input ->
                                outFile.outputStream().use { output ->
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read <= 0) break
                                        extractedBytes += read.toLong()
                                        require(extractedBytes <= MAX_EXTRACTED_BYTES) { "Module ZIP is too large." }
                                        output.write(buffer, 0, read)
                                    }
                                }
                            }
                        }
                    }

                    markScriptsExecutable(staging)
                    target.deleteRecursively()
                    check(staging.renameTo(target)) { "Unable to move module into storage." }
                    readModule(target) ?: error("Installed module is unreadable.")
                }
            }
        } finally {
            temp.delete()
        }
    }

    suspend fun setEnabled(module: AdbModule, enabled: Boolean) = withContext(Dispatchers.IO) {
        val marker = module.directory.resolve(DISABLE_FILE)
        if (enabled) {
            marker.delete()
        } else {
            marker.writeText("disabled\n")
        }
    }

    suspend fun delete(module: AdbModule) = withContext(Dispatchers.IO) {
        module.directory.deleteRecursively()
    }

    suspend fun runAction(module: AdbModule): ModuleActionResult = withContext(Dispatchers.IO) {
        check(ModuleSettings.canRunAction(module)) { "action.sh is blocked by module access policy." }
        val script = module.actionScript?.takeIf { it.isFile } ?: error("This module has no action.sh.")
        runModuleScript(module, script, module.lastActionLog)
    }

    suspend fun runActionStreaming(module: AdbModule): ModuleActionResult = withContext(Dispatchers.IO) {
        check(ModuleSettings.canRunAction(module)) { "action.sh is blocked by module access policy." }
        val script = module.actionScript?.takeIf { it.isFile } ?: error("This module has no action.sh.")
        runModuleScriptStreaming(module, script, module.lastActionLog)
    }

    suspend fun runService(module: AdbModule): ModuleActionResult = withContext(Dispatchers.IO) {
        check(ModuleSettings.canRunService(module)) { "service.sh is blocked by module access policy." }
        check(ModuleSettings.canRunBackground(module)) { "Background actions are disabled." }
        val script = module.serviceScript?.takeIf { it.isFile } ?: error("This module has no service.sh.")
        runModuleScript(module, script, module.lastServiceLog)
    }

    suspend fun runEnabledServicesIfAllowed(context: Context): List<Pair<AdbModule, ModuleActionResult>> =
        withContext(Dispatchers.IO) {
            if (servicesStartedForBinder) return@withContext emptyList()
            if (!Shizuku.pingBinder()) return@withContext emptyList()

            servicesStartedForBinder = true
            listModules(context)
                .filter { it.enabled && it.hasService && ModuleSettings.canRunService(it) && ModuleSettings.canRunBackground(it) }
                .map { module -> module to runService(module) }
        }

    fun resetServiceRunGuard() {
        servicesStartedForBinder = false
    }

    private fun runModuleScript(module: AdbModule, script: File, logFile: File): ModuleActionResult {
        check(module.enabled) { "Module is disabled." }
        script.setExecutable(true, false)
        module.logsDir.mkdirs()

        val binder = Shizuku.getBinder() ?: error("Shizuku service is not running.")
        val service = IShizukuService.Stub.asInterface(binder)
        val env = arrayOf(
            "MODDIR=${module.directory.absolutePath}",
            "ASH_STANDALONE=1",
            "SHIZUKU_MODULE_ID=${module.id}",
            "SHIZUKU_MODULE_MODE=${ModuleSettings.getAccessMode().value}",
            "SHIZUKU_MODULE_TRUSTED=${if (ModuleSettings.isModuleTrusted(module.id)) "1" else "0"}",
            "SHIZUKU_MODULE_BACKGROUND=${if (ModuleSettings.canRunBackground(module)) "1" else "0"}"
        )
        val remote = service.newProcess(
            arrayOf("sh", script.absolutePath),
            env,
            module.directory.absolutePath
        )

        ParcelFileDescriptor.AutoCloseOutputStream(remote.getOutputStream()).close()
        var stdout = ""
        var stderr = ""
        val stdoutThread = Thread {
            try {
                stdout = readStreamTail(ParcelFileDescriptor.AutoCloseInputStream(remote.getInputStream()))
            } catch (ignore: Exception) { }
        }
        val stderrThread = Thread {
            try {
                stderr = readStreamTail(ParcelFileDescriptor.AutoCloseInputStream(remote.getErrorStream()))
            } catch (ignore: Exception) { }
        }
        stdoutThread.start()
        stderrThread.start()
        val finished = remote.waitForTimeout(MAX_SCRIPT_SECONDS, TimeUnit.SECONDS.name)
        val exitCode = if (finished) {
            remote.exitValue()
        } else {
            remote.destroy()
            124
        }
        stdoutThread.join(1000)
        stderrThread.join(1000)
        val result = ModuleActionResult(
            exitCode = exitCode,
            stdout = stdout.takeLast(MAX_OUTPUT_CHARS),
            stderr = stderr.takeLast(MAX_OUTPUT_CHARS)
        )
        writeLastLog(logFile, module, script, result, finished)
        return result
    }

    private fun runModuleScriptStreaming(module: AdbModule, script: File, logFile: File): ModuleActionResult {
        check(module.enabled) { "Module is disabled." }
        script.setExecutable(true, false)
        module.logsDir.mkdirs()

        _liveOutput.value = LiveModuleOutput(
            moduleId = module.id,
            moduleName = module.name,
            text = "Starting ${script.name}...\n",
            running = true
        )

        val binder = Shizuku.getBinder() ?: error("Shizuku service is not running.")
        val service = IShizukuService.Stub.asInterface(binder)
        val env = arrayOf(
            "MODDIR=${module.directory.absolutePath}",
            "ASH_STANDALONE=1",
            "SHIZUKU_MODULE_ID=${module.id}",
            "SHIZUKU_MODULE_MODE=${ModuleSettings.getAccessMode().value}",
            "SHIZUKU_MODULE_TRUSTED=${if (ModuleSettings.isModuleTrusted(module.id)) "1" else "0"}",
            "SHIZUKU_MODULE_BACKGROUND=${if (ModuleSettings.canRunBackground(module)) "1" else "0"}"
        )
        val remote = service.newProcess(
            arrayOf("sh", script.absolutePath),
            env,
            module.directory.absolutePath
        )

        ParcelFileDescriptor.AutoCloseOutputStream(remote.getOutputStream()).close()
        val streamingBuffer = StringBuilder()
        var stdout = ""
        var stderr = ""
        val stdoutThread = Thread {
            try {
                val reader = ParcelFileDescriptor.AutoCloseInputStream(remote.getInputStream()).bufferedReader(Charsets.UTF_8)
                reader.use { r ->
                    val buffer = CharArray(1024)
                    while (true) {
                        val read = r.read(buffer)
                        if (read <= 0) break
                        val chunk = String(buffer, 0, read)
                        synchronized(streamingBuffer) {
                            streamingBuffer.append(chunk)
                            stdout = streamingBuffer.toString()
                        }
                        _liveOutput.value = LiveModuleOutput(
                            moduleId = module.id,
                            moduleName = module.name,
                            text = streamingBuffer.toString().takeLast(MAX_OUTPUT_CHARS),
                            running = true
                        )
                    }
                }
            } catch (ignore: Exception) { }
        }
        val stderrThread = Thread {
            try {
                stderr = readStreamTail(ParcelFileDescriptor.AutoCloseInputStream(remote.getErrorStream()))
            } catch (ignore: Exception) { }
        }

        val logcatRemote = try {
            service.newProcess(arrayOf("logcat", "-v", "time", "-T", "1"), null, null)
        } catch (e: Exception) { null }
        val logcatThread = Thread {
            logcatRemote?.let { lr ->
                try {
                    val reader = ParcelFileDescriptor.AutoCloseInputStream(lr.getInputStream()).bufferedReader(Charsets.UTF_8)
                    reader.use { r ->
                        val buffer = CharArray(1024)
                        while (true) {
                            val read = r.read(buffer)
                            if (read <= 0) break
                            val chunk = String(buffer, 0, read)
                            synchronized(streamingBuffer) {
                                streamingBuffer.append(chunk)
                                stdout = streamingBuffer.toString()
                            }
                            _liveOutput.value = LiveModuleOutput(
                                moduleId = module.id,
                                moduleName = module.name,
                                text = streamingBuffer.toString().takeLast(MAX_OUTPUT_CHARS),
                                running = true
                            )
                        }
                    }
                } catch (ignore: Exception) {}
            }
        }

        stdoutThread.start()
        stderrThread.start()
        logcatThread.start()
        val finished = remote.waitForTimeout(MAX_SCRIPT_SECONDS, TimeUnit.SECONDS.name)
        val exitCode = if (finished) {
            remote.exitValue()
        } else {
            remote.destroy()
            124
        }
        logcatRemote?.destroy()
        stdoutThread.join(1000)
        stderrThread.join(1000)
        logcatThread.join(1000)

        val finalOutput = synchronized(streamingBuffer) { streamingBuffer.toString() }
        _liveOutput.value = LiveModuleOutput(
            moduleId = module.id,
            moduleName = module.name,
            text = finalOutput.takeLast(MAX_OUTPUT_CHARS) + "\n\n--- Exit code: $exitCode ---",
            running = false
        )

        val result = ModuleActionResult(
            exitCode = exitCode,
            stdout = finalOutput.takeLast(MAX_OUTPUT_CHARS),
            stderr = stderr.takeLast(MAX_OUTPUT_CHARS)
        )
        writeLastLog(logFile, module, script, result, finished)
        return result
    }

    fun clearLiveOutput() {
        _liveOutput.value = null
    }

    fun readModule(directory: File): AdbModule? {
        val propsFile = directory.resolve("module.prop")
        if (!propsFile.isFile) return null
        val props = parseModuleProp(propsFile.readText())
        val id = props["id"]?.takeIf { idRegex.matches(it) } ?: return null
        val name = props["name"]?.takeIf { it.isNotBlank() } ?: id
        return AdbModule(
            id = id,
            name = name,
            version = props["version"]?.takeIf { it.isNotBlank() },
            versionCode = props["versionCode"]?.toLongOrNull(),
            author = props["author"]?.takeIf { it.isNotBlank() },
            description = props["description"]?.takeIf { it.isNotBlank() },
            directory = directory,
            banner = findFirstExisting(
                directory,
                props["banner"],
                "banner.png",
                "banner.jpg",
                "banner.jpeg",
                "banner.webp"
            ),
            webRoot = findFirstExisting(
                directory,
                props["webui"],
                "webroot",
                "webui",
                "web"
            )?.takeIf { it.isDirectory },
            declaresShellBridge = props["usesShellBridge"]?.toBooleanStrictOrNull()
                ?: props["shellBridge"]?.toBooleanStrictOrNull()
                ?: false,
            actionScript = findFirstExisting(directory, props["action"], "action.sh"),
            serviceScript = findFirstExisting(directory, "service.sh"),
            logsDir = directory.resolve("logs"),
            sizeBytes = directory.walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() },
            enabled = !directory.resolve(DISABLE_FILE).exists()
        )
    }

    private fun parseModuleProp(raw: String): Map<String, String> {
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
            .associate {
                val index = it.indexOf('=')
                it.substring(0, index).trim() to it.substring(index + 1).trim()
            }
    }

    private fun cleanZipName(name: String): String {
        val clean = name.replace('\\', '/').trim('/')
        require(clean.isNotBlank()) { "Invalid ZIP entry." }
        require(!clean.startsWith("/") && !clean.contains("../") && clean != "..") {
            "Unsafe ZIP entry: $name"
        }
        return clean
    }

    private fun ensureInside(root: File, child: File) {
        val rootPath = root.canonicalPath
        val childPath = child.canonicalPath
        require(childPath == rootPath || childPath.startsWith("$rootPath/")) {
            "Unsafe module path: ${child.name}"
        }
    }

    private fun markScriptsExecutable(directory: File) {
        directory.walkTopDown()
            .filter { it.isFile && it.extension == "sh" }
            .forEach { it.setExecutable(true, false) }
    }

    private fun writeLastLog(
        logFile: File,
        module: AdbModule,
        script: File,
        result: ModuleActionResult,
        finished: Boolean
    ) {
        logFile.parentFile?.mkdirs()
        logFile.writeText(
            buildString {
                appendLine("module=${module.id}")
                appendLine("script=${script.name}")
                appendLine("finished=$finished")
                appendLine("exitCode=${result.exitCode}")
                appendLine("mode=${ModuleSettings.getAccessMode().value}")
                appendLine("trusted=${ModuleSettings.isModuleTrusted(module.id)}")
                appendLine()
                appendLine("[stdout]")
                appendLine(result.stdout.trim())
                appendLine()
                appendLine("[stderr]")
                appendLine(result.stderr.trim())
            }
        )
    }

    private fun findFirstExisting(directory: File, vararg paths: String?): File? {
        return paths.asSequence()
            .filterNotNull()
            .map { it.trim().trim('/') }
            .filter { it.isNotBlank() }
            .map { directory.resolve(it) }
            .firstOrNull { it.exists() }
    }

    private fun readStreamTail(inputStream: java.io.InputStream): String {
        return inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(8192)
            val tail = StringBuilder()
            while (true) {
                val read = reader.read(buffer)
                if (read <= 0) break
                tail.append(buffer, 0, read)
                if (tail.length > MAX_OUTPUT_CHARS * 2) {
                    tail.delete(0, tail.length - MAX_OUTPUT_CHARS)
                }
            }
            if (tail.length > MAX_OUTPUT_CHARS) {
                tail.substring(tail.length - MAX_OUTPUT_CHARS)
            } else {
                tail.toString()
            }
        }
    }
}
