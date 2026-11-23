package cz.vyvrnc.transcribronpu.calls

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * When [CallAuto] looks for new recordings: a periodic check (every 15 min, the shortest
 * Android allows; only while charging in that mode), and at once when a new audio file shows
 * up in the media store while the floating-button service keeps the process running.
 */
object CallTriggers {
    private const val WORK = "call-transcription"

    /** Call after any change of the settings. */
    fun update(context: Context) {
        val app = context.applicationContext
        val wm = WorkManager.getInstance(app)
        if (!CallSettings.enabled(app)) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiresCharging(CallSettings.onlyCharging(app))
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<Worker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        CallAuto.kick(app)
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            // The pass itself runs in CallAuto's own scope (a long call outlives a worker's
            // 10 minutes); the transcription keeps the process alive with its foreground service.
            CallAuto.kick(applicationContext)
            return Result.success()
        }
    }

    /** Media store changes, a few seconds apart, start a pass. Register while a service runs. */
    class MediaObserver(private val context: Context) : ContentObserver(Handler(Looper.getMainLooper())) {
        private val handler = Handler(Looper.getMainLooper())
        private val pass = Runnable { CallAuto.kick(context) }

        fun register() {
            context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, this)
        }

        fun unregister() {
            handler.removeCallbacks(pass)
            context.contentResolver.unregisterContentObserver(this)
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (!CallSettings.enabled(context)) return
            handler.removeCallbacks(pass)
            handler.postDelayed(pass, 5_000)
        }
    }
}
