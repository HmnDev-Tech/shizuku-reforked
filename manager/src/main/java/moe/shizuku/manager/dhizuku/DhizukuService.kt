package moe.shizuku.manager.dhizuku

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.SystemProperties
import android.provider.Settings
import android.util.Log

class DhizukuService(private val context: Context) : IDhizukuService.Stub() {

    companion object {
        private const val TAG = "DhizukuService"
    }

    override fun runCommand(command: String?) {
        command?.let {
            try {
                Log.d(TAG, "Executing command: $it")
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", it))
                Thread {
                    try {
                        process.inputStream.use { stream ->
                            val buffer = ByteArray(1024)
                            while (stream.read(buffer) != -1) { }
                        }
                    } catch (_: Exception) {}
                }.start()
                Thread {
                    try {
                        process.errorStream.use { stream ->
                            val buffer = ByteArray(1024)
                            while (stream.read(buffer) != -1) { }
                        }
                    } catch (_: Exception) {}
                }.start()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute command", e)
            }
        }
    }

    override fun enableAdb(): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admins = dpm.activeAdmins
            if (admins.isNullOrEmpty()) {
                Log.e(TAG, "No active device admins found")
                return false
            }

            // Find the device owner admin component
            val ownerAdmin = admins.firstOrNull { admin ->
                try {
                    dpm.isDeviceOwnerApp(admin.packageName)
                } catch (e: Exception) {
                    false
                }
            }

            if (ownerAdmin == null) {
                Log.e(TAG, "No device owner admin found among active admins: ${admins.map { it.flattenToString() }}")
                return false
            }

            Log.d(TAG, "Device owner admin: ${ownerAdmin.flattenToString()}")

            // Enable ADB
            dpm.setGlobalSetting(ownerAdmin, Settings.Global.ADB_ENABLED, "1")
            Log.d(TAG, "ADB enabled via DPM")

            // On Android 11+, enable wireless debugging
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    dpm.setGlobalSetting(ownerAdmin, "adb_wifi_enabled", "1")
                    Log.d(TAG, "Wireless ADB enabled via DPM")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to enable wireless ADB: ${e.message}")
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "enableAdb failed", e)
            false
        }
    }

    override fun getAdbPort(): Int {
        return try {
            var port = SystemProperties.getInt("service.adb.tcp.port", -1)
            if (port == -1) port = SystemProperties.getInt("persist.adb.tcp.port", -1)
            // Also check wireless debugging port on Android 11+
            if (port == -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val wifiPort = Settings.Global.getInt(context.contentResolver, "adb_wifi_port", -1)
                    if (wifiPort > 0) port = wifiPort
                } catch (_: Exception) {}
            }
            port
        } catch (e: Exception) {
            Log.e(TAG, "getAdbPort failed", e)
            -1
        }
    }
}
