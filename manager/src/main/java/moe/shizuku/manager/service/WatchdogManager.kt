package moe.shizuku.manager.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.topjohnwu.superuser.Shell
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ktx.logd
import moe.shizuku.manager.starter.Starter
import rikka.shizuku.Shizuku

object WatchdogManager {

    private const val CHECK_INTERVAL = 15000L // 15 seconds
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!ShizukuSettings.isWatchdogEnabled()) {
                stop()
                return
            }

            checkAndRepair()
            handler.postDelayed(this, CHECK_INTERVAL)
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        logd("Watchdog", "Watchdog started")
        handler.post(checkRunnable)
    }

    fun stop() {
        isRunning = false
        logd("Watchdog", "Watchdog stopped")
        handler.removeCallbacks(checkRunnable)
    }

    private fun checkAndRepair() {
        if (Shizuku.pingBinder()) {
            return
        }

        val lastMode = ShizukuSettings.getLastLaunchMode()
        if (lastMode == ShizukuSettings.LaunchMethod.ROOT) {
            logd("Watchdog", "Service not running, but last mode was ROOT. Attempting background recovery...")
            Shell.cmd(Starter.internalCommand).submit { result ->
                if (result.isSuccess) {
                    logd("Watchdog", "Recovery script submitted successfully")
                } else {
                    logd("Watchdog", "Recovery script failed: ${result.code}")
                }
            }
        }
    }
}
