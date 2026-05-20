package moe.shizuku.manager.module

import androidx.annotation.StringRes
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings

object ModuleSettings {

    private const val KEY_ACCESS_MODE = "adb_modules_access_mode"
    private const val KEY_ALLOW_BACKGROUND_ACTIONS = "adb_modules_allow_background_actions"
    private const val KEY_CUSTOM_ACTION = "adb_modules_custom_action"
    private const val KEY_CUSTOM_SERVICE = "adb_modules_custom_service"
    private const val KEY_CUSTOM_WEB_BRIDGE = "adb_modules_custom_web_bridge"
    private const val KEY_CUSTOM_WEB_NETWORK = "adb_modules_custom_web_network"
    private const val KEY_CUSTOM_WEB_DOWNLOAD = "adb_modules_custom_web_download"
    private const val KEY_RECOMMAND_WEBUI = "adb_modules_recommand_webui"
    private const val KEY_RECOMMAND_ACTION = "adb_modules_recommand_action"
    private const val KEY_TRUSTED_MODULES = "adb_modules_trusted_modules"
    private const val KEY_CONNECTOR_ENABLED = "shizuku_connector_enabled"
    private const val KEY_DHIZUKU_ENABLED = "shizuku_dhizuku_enabled"
    private const val KEY_KEEP_ALIVE = "shizuku_keep_alive"
    private const val KEY_VERBOSE_LOGGING = "shizuku_verbose_logging"
    private const val KEY_AUTO_RESTART = "shizuku_auto_restart_on_crash"
    private const val KEY_NOTIFY_DEATH = "shizuku_notify_service_death"


    enum class AccessMode(
        val value: String,
        @param:StringRes val labelRes: Int,
        @param:StringRes val summaryRes: Int
    ) {
        SAFE(
            "safe",
            R.string.modules_access_mode_safe,
            R.string.modules_access_mode_safe_summary
        ),
        CUSTOM(
            "custom",
            R.string.modules_access_mode_custom,
            R.string.modules_access_mode_custom_summary
        ),
        FULL(
            "full",
            R.string.modules_access_mode_full,
            R.string.modules_access_mode_full_summary
        );

        companion object {
            fun fromValue(value: String?): AccessMode {
                return entries.firstOrNull { it.value == value } ?: SAFE
            }
        }
    }

    data class CustomPermissions(
        val action: Boolean,
        val service: Boolean,
        val webBridge: Boolean,
        val webNetwork: Boolean,
        val webDownload: Boolean
    )

    fun getAccessMode(): AccessMode {
        return AccessMode.fromValue(
            ShizukuSettings.getPreferences().getString(KEY_ACCESS_MODE, AccessMode.SAFE.value)
        )
    }

    fun setAccessMode(mode: AccessMode) {
        ShizukuSettings.getPreferences().edit().putString(KEY_ACCESS_MODE, mode.value).apply()
    }

    fun allowBackgroundActions(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_ALLOW_BACKGROUND_ACTIONS, false)
    }

    fun setAllowBackgroundActions(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_ALLOW_BACKGROUND_ACTIONS, value).apply()
    }

    fun getCustomPermissions(): CustomPermissions {
        val prefs = ShizukuSettings.getPreferences()
        return CustomPermissions(
            action = prefs.getBoolean(KEY_CUSTOM_ACTION, false),
            service = prefs.getBoolean(KEY_CUSTOM_SERVICE, false),
            webBridge = prefs.getBoolean(KEY_CUSTOM_WEB_BRIDGE, false),
            webNetwork = prefs.getBoolean(KEY_CUSTOM_WEB_NETWORK, false),
            webDownload = prefs.getBoolean(KEY_CUSTOM_WEB_DOWNLOAD, false)
        )
    }

    fun setCustomPermissions(value: CustomPermissions) {
        ShizukuSettings.getPreferences().edit()
            .putBoolean(KEY_CUSTOM_ACTION, value.action)
            .putBoolean(KEY_CUSTOM_SERVICE, value.service)
            .putBoolean(KEY_CUSTOM_WEB_BRIDGE, value.webBridge)
            .putBoolean(KEY_CUSTOM_WEB_NETWORK, value.webNetwork)
            .putBoolean(KEY_CUSTOM_WEB_DOWNLOAD, value.webDownload)
            .apply()
    }

    fun canRunAction(): Boolean {
        return when (getAccessMode()) {
            AccessMode.SAFE -> false
            AccessMode.FULL -> true
            AccessMode.CUSTOM -> getCustomPermissions().action
        }
    }

    fun canRunAction(module: AdbModule): Boolean {
        return isModuleTrusted(module.id) || canRunAction()
    }

    fun canRunService(): Boolean {
        return when (getAccessMode()) {
            AccessMode.SAFE -> false
            AccessMode.FULL -> true
            AccessMode.CUSTOM -> getCustomPermissions().service
        }
    }

    fun canRunService(module: AdbModule): Boolean {
        return isModuleTrusted(module.id) || canRunService()
    }

    fun canExposeWebBridge(): Boolean {
        return when (getAccessMode()) {
            AccessMode.SAFE -> false
            AccessMode.FULL -> true
            AccessMode.CUSTOM -> getCustomPermissions().webBridge
        }
    }

    fun canExposeWebBridge(module: AdbModule): Boolean {
        return isModuleTrusted(module.id) || canExposeWebBridge()
    }

    fun canUseWebNetwork(): Boolean {
        return when (getAccessMode()) {
            AccessMode.SAFE -> false
            AccessMode.FULL -> false
            AccessMode.CUSTOM -> getCustomPermissions().webNetwork
        }
    }

    fun canUseWebNetwork(module: AdbModule): Boolean {
        return isModuleTrusted(module.id) || canUseWebNetwork()
    }

    fun canDownloadWebFiles(): Boolean {
        return when (getAccessMode()) {
            AccessMode.SAFE -> false
            AccessMode.FULL -> true
            AccessMode.CUSTOM -> getCustomPermissions().webDownload
        }
    }

    fun canDownloadWebFiles(module: AdbModule): Boolean {
        return isModuleTrusted(module.id) || canDownloadWebFiles()
    }

    fun canRunBackground(module: AdbModule): Boolean {
        return isModuleTrusted(module.id) || allowBackgroundActions()
    }

    fun isModuleTrusted(moduleId: String): Boolean {
        return ShizukuSettings.getPreferences()
            .getStringSet(KEY_TRUSTED_MODULES, emptySet())
            ?.contains(moduleId)
            ?: false
    }

    fun setModuleTrusted(moduleId: String, trusted: Boolean) {
        val prefs = ShizukuSettings.getPreferences()
        val current = prefs.getStringSet(KEY_TRUSTED_MODULES, emptySet()).orEmpty().toMutableSet()
        if (trusted) {
            current += moduleId
        } else {
            current -= moduleId
        }
        prefs.edit().putStringSet(KEY_TRUSTED_MODULES, current).apply()
    }

    fun recommandForWebUi(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_RECOMMAND_WEBUI, true)
    }

    fun setRecommandForWebUi(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_RECOMMAND_WEBUI, value).apply()
    }

    fun recommandForAction(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_RECOMMAND_ACTION, true)
    }

    fun setRecommandForAction(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_RECOMMAND_ACTION, value).apply()
    }

    fun isConnectorEnabled(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_CONNECTOR_ENABLED, false)
    }

    fun setConnectorEnabled(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_CONNECTOR_ENABLED, value).apply()
    }

    fun isDhizukuEnabled(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_DHIZUKU_ENABLED, false)
    }

    fun setDhizukuEnabled(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_DHIZUKU_ENABLED, value).apply()
    }

    fun isKeepAlive(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_KEEP_ALIVE, false)
    }

    fun setKeepAlive(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_KEEP_ALIVE, value).apply()
    }

    fun isVerboseLogging(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_VERBOSE_LOGGING, false)
    }

    fun setVerboseLogging(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_VERBOSE_LOGGING, value).apply()
    }

    fun isAutoRestartOnCrash(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_AUTO_RESTART, false)
    }

    fun setAutoRestartOnCrash(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_AUTO_RESTART, value).apply()
    }

    fun isNotifyOnServiceDeath(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_NOTIFY_DEATH, true)
    }

    fun setNotifyOnServiceDeath(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_NOTIFY_DEATH, value).apply()
    }

    // Comput Settings
    private const val KEY_COMPUT_API_KEY = "comput_api_key"
    private const val KEY_COMPUT_RECOMMAND = "comput_recommand"
    private const val KEY_COMPUT_AI_EXPLAIN = "comput_ai_explain"

    fun getComputApiKey(): String {
        return ShizukuSettings.getPreferences().getString(KEY_COMPUT_API_KEY, "") ?: ""
    }

    fun setComputApiKey(value: String) {
        ShizukuSettings.getPreferences().edit().putString(KEY_COMPUT_API_KEY, value).apply()
    }

    fun isComputRecommandEnabled(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_COMPUT_RECOMMAND, true)
    }

    fun setComputRecommandEnabled(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_COMPUT_RECOMMAND, value).apply()
    }

    fun isComputAiExplainEnabled(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_COMPUT_AI_EXPLAIN, true)
    }

    fun setComputAiExplainEnabled(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_COMPUT_AI_EXPLAIN, value).apply()
    }
}
