package moe.shizuku.manager.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.ktx.logd
import moe.shizuku.manager.ktx.logi
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.starter.Starter
import rikka.shizuku.Shizuku

object WatchdogManager {

    @Volatile
    var expectingDeath = false

    private const val CHANNEL_ID = "service_watchdog"
    private const val NOTIFICATION_ID = 1001
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        logi("Initializing service watchdog")

        Shizuku.addBinderDeadListener {
            onServiceDied(context)
        }
    }

    private fun onServiceDied(context: Context) {
        logd("Service died detected by watchdog")

        if (expectingDeath) {
            logi("Service death was expected (user stopped it). Resetting flag.")
            expectingDeath = false
            return
        }

        if (ModuleSettings.isNotifyOnServiceDeath()) {
            showDeathNotification(context)
        }

        if (ModuleSettings.isAutoRestartOnCrash() || ModuleSettings.isKeepAlive()) {
            attemptRestart(context)
        }
    }

    private fun showDeathNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_watchdog),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server_error_24dp)
            .setContentTitle(context.getString(R.string.notification_watchdog_title))
            .setContentText(context.getString(R.string.notification_watchdog_text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun attemptRestart(context: Context) {
        val lastMode = ShizukuSettings.getLastLaunchMode()
        logi("Attempting to restart service (Last mode: $lastMode)")

        when (lastMode) {
            LaunchMethod.ROOT -> {
                CoroutineScope(Dispatchers.IO).launch {
                    if (!Shell.getShell().isRoot) {
                        Shell.getCachedShell()?.close()
                    }
                    if (Shell.getShell().isRoot) {
                        Shell.cmd(Starter.internalCommand).exec()
                    }
                }
            }
            LaunchMethod.ADB -> {
                // If TCP mode was enabled, we can try localhost:5555
                if (ShizukuSettings.isTcpMode()) {
                    restartTcp(context)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    restartWirelessAdb(context)
                }
            }
        }
    }

    private fun restartTcp(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val host = "127.0.0.1"
                val port = 5555
                val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
                val client = AdbClient(host, port, key)
                client.connect()
                client.shellCommand(Starter.internalCommand) { _ -> }
                client.close()
                logi("Restart via TCP sent")
            } catch (e: Exception) {
                logd("Restart via TCP failed: ${e.message}")
            }
        }
    }

    private fun restartWirelessAdb(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        CoroutineScope(Dispatchers.IO).launch {
            val latch = java.util.concurrent.CountDownLatch(1)
            val adbMdns = moe.shizuku.manager.adb.AdbMdns(context, moe.shizuku.manager.adb.AdbMdns.TLS_CONNECT) { port ->
                if (port <= 0) return@AdbMdns
                try {
                    val keystore = PreferenceAdbKeyStore(ShizukuSettings.getPreferences())
                    val key = AdbKey(keystore, "shizuku")
                    val client = AdbClient("127.0.0.1", port, key)
                    client.connect()
                    client.shellCommand(Starter.internalCommand) { _ -> }
                    client.close()
                    logi("Restart via Wireless ADB successful on port $port")
                } catch (e: Exception) {
                    logd("Restart via Wireless ADB failed on port $port: ${e.message}")
                }
                latch.countDown()
            }
            adbMdns.start()
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            adbMdns.stop()
        }
    }
}
