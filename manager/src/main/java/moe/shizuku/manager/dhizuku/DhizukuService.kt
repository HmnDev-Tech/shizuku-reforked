package moe.shizuku.manager.dhizuku

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class DhizukuService : Service() {
    private val binder = object : IDhizukuService.Stub() {
        override fun runCommand(command: String?) {
            command?.let {
                try {
                    Log.d("DhizukuService", "Executing command: $it")
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", it))
                } catch (e: Exception) {
                    Log.e("DhizukuService", "Failed to execute command", e)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
}
