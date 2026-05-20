package moe.shizuku.manager.module

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.ShizukuScaffold
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ModuleWebViewActivity : AppActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val moduleId = intent.getStringExtra(EXTRA_MODULE_ID).orEmpty()
        val module = AdbModuleManager.readModule(AdbModuleManager.modulesRoot(this).resolve(moduleId))
        val index = module?.webRoot?.resolve("index.html")
        if (module == null || index?.isFile != true) {
            finish()
            return
        }

        setContent {
            val trusted = ModuleSettings.isModuleTrusted(module.id)
            val webNetworkAllowed = ModuleSettings.canUseWebNetwork(module)
            val exposeBridge = module.enabled &&
                ModuleSettings.canExposeWebBridge(module) &&
                (module.declaresShellBridge || trusted) &&
                (!webNetworkAllowed || trusted)

            ShizukuExpressiveTheme {
                ShizukuScaffold(
                    title = module.name,
                    onNavigateUp = { finish() }
                ) { padding ->
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = true
                                settings.allowContentAccess = false
                                settings.allowFileAccessFromFileURLs = trusted
                                settings.allowUniversalAccessFromFileURLs = trusted
                                settings.blockNetworkLoads = !webNetworkAllowed
                                settings.cacheMode = WebSettings.LOAD_DEFAULT
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                                webViewClient = LocalModuleWebViewClient(module, webNetworkAllowed)
                                if (exposeBridge) {
                                    addJavascriptInterface(
                                        ModuleJsBridge(
                                            module,
                                            commandReviewer = { request ->
                                                confirmCommandOnUiThread(request)
                                            }
                                        ),
                                        "Shizuku"
                                    )
                                }
                                loadUrl(index.toURI().toString())
                            }
                        },
                        modifier = androidx.compose.ui.Modifier
                            .padding(padding)
                    )
                }
            }
        }
    }

    private fun confirmCommandOnUiThread(
        request: ModuleCommandRequest
    ): Boolean {
        val latch = CountDownLatch(1)
        val approved = AtomicBoolean(false)
        runOnUiThread {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(moe.shizuku.manager.R.string.modules_recommand_title)
                .setMessage(
                    getString(moe.shizuku.manager.R.string.modules_recommand_source, request.module.name, "WebUI") +
                    "\n\n" + request.command
                )
                .setPositiveButton(moe.shizuku.manager.R.string.modules_recommand_execute) { _, _ ->
                    approved.set(true)
                    latch.countDown()
                }
                .setNegativeButton(moe.shizuku.manager.R.string.modules_recommand_close) { _, _ ->
                    approved.set(false)
                    latch.countDown()
                }
                .setOnCancelListener {
                    approved.set(false)
                    latch.countDown()
                }
                .show()
        }
        try {
            latch.await(5, TimeUnit.MINUTES)
        } catch (e: InterruptedException) {
            approved.set(false)
        }
        return approved.get()
    }

    private class LocalModuleWebViewClient(
        private val module: AdbModule,
        private val webNetworkAllowed: Boolean
    ) : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            return when (uri.scheme?.lowercase()) {
                "file" -> !isInsideWebRoot(uri.path.orEmpty())
                "https" -> !webNetworkAllowed
                "http" -> true
                else -> true
            }
        }

        private fun isInsideWebRoot(path: String): Boolean {
            val root = module.webRoot ?: return false
            return runCatching {
                val rootFile = root.canonicalFile.toPath()
                val target = File(path).canonicalFile.toPath()
                target.startsWith(rootFile)
            }.getOrDefault(false)
        }
    }

    companion object {
        const val EXTRA_MODULE_ID = "module_id"
    }
}
