package cz.vyvrnc.transcribronpu.file

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground while [FileTranscription] works, so a long recording
 * keeps going when the user leaves the screen (a cached process is frozen or killed).
 * The work itself stays in [FileTranscription]; this only shows its progress.
 */
class FileTranscriptionService : Service() {
    companion object {
        private const val CHANNEL = "file_transcription"
        private const val NOTIFICATION_ID = 4201

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FileTranscriptionService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Přepis nahrávek", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() must always be answered with startForeground().
        startForeground(NOTIFICATION_ID, notification(FileTranscription.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        watcher?.cancel()
        watcher = scope.launch {
            FileTranscription.state.collect { s ->
                if (s is FileTranscription.State.Decoding || s is FileTranscription.State.Transcribing) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(s))
                } else {
                    // Done, failed or cancelled: only the file cleanup is left, which needs no service.
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    // Only this start: a newer one (next file) keeps the service.
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 limits dataSync services; stop the work instead of being killed for it. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        FileTranscription.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(s: FileTranscription.State): Notification {
        val (text, progress) = when (s) {
            is FileTranscription.State.Decoding -> "Načítám ${s.name}" to s.progress
            is FileTranscription.State.Transcribing -> "Přepisuji ${s.name} – ${(s.progress * 100).toInt()} %" to s.progress
            else -> "Přepis nahrávky" to 0f
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, FileTranscriptionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Transcribro NPU")
            .setContentText(text)
            .setProgress(1000, (progress * 1000).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }
}
