package com.localyze.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.localyze.MainActivity
import com.localyze.R

/**
 * Foreground service that protects the model process from aggressive OEM
 * memory managers (OnePlus Athena, Xiaomi MIUI, Samsung battery optimization, etc.).
 *
 * Critical for Snapdragon devices: When the Hexagon HTP is running, the model
 * process must stay alive. Without foreground priority, OnePlus's OOM killer
 * will terminate a process using >2GB RAM even if it's the foreground app.
 *
 * This service runs for the ENTIRE lifetime of the model (not just during loading).
 * It starts when the model initializes and stops when the model is released.
 */
class ModelLoadingService : Service() {

    companion object {
        private const val TAG = "ModelLoadingService"
        private const val CHANNEL_ID = "model_loading_channel"
        private const val NOTIFICATION_ID = 2001
        private const val ACTION_UPDATE = "com.localyze.ACTION_UPDATE_NOTIFICATION"
        private const val EXTRA_TEXT = "notification_text"

        private var isRunning = false

        /** Whether this service is currently active */
        fun isActive(): Boolean = isRunning

        /**
         * Start the foreground service to protect the model process.
         * Safe to call multiple times â€” no-op if already running.
         */
        fun start(context: Context) {
            if (isRunning) {
                Log.d(TAG, "Service already running, skip")
                return
            }
            Log.d(TAG, "Starting foreground service â€” protecting model process from OOM kill")
            val intent = Intent(context, ModelLoadingService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Foreground service start was blocked; model can still run without protection", e)
            }
        }

        /**
         * Stop the foreground service when the model is released.
         */
        fun stop(context: Context) {
            Log.d(TAG, "Stopping foreground service â€” model released")
            val intent = Intent(context, ModelLoadingService::class.java)
            context.stopService(intent)
        }

        /**
         * Update the notification text (e.g., "Generating on npu..." or
         * "Running on npu_htp").
         */
        fun updateNotification(context: Context, text: String) {
            val intent = Intent(context, ModelLoadingService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TEXT, text)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Notification update was blocked; continuing without foreground update", e)
            }
        }
    }

    private var notificationText = "Loading Gemma 4 E4B..."

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.d(TAG, "Service onCreate â€” promoting process to foreground priority")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(notificationText))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true

        // Handle notification text updates
        if (intent?.action == ACTION_UPDATE) {
            notificationText = intent.getStringExtra(EXTRA_TEXT) ?: notificationText
        }

        Log.d(TAG, "Service onStartCommand â€” $notificationText")

        // Must call startForeground on every onStartCommand for Android 12+
        val notification = buildNotification(notificationText)
        startForeground(NOTIFICATION_ID, notification)

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        Log.d(TAG, "Service onDestroy â€” foreground protection removed")
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Localyze")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI Model Active",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when the AI model is loaded and running"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
