package com.localassistant.receivers

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.localassistant.MainActivity
import com.localassistant.R
import com.localassistant.tools.AlarmTool

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmTool.ACTION_ALARM_TRIGGER) return

        val message = intent.getStringExtra(AlarmTool.EXTRA_ALARM_MESSAGE)
            ?: "Local Assistant reminder"
        val alarmId = intent.getIntExtra(AlarmTool.EXTRA_ALARM_ID, 0)
        val repeat = intent.getStringExtra(AlarmTool.EXTRA_REPEAT) ?: "none"
        val triggerTime = intent.getLongExtra(AlarmTool.EXTRA_TRIGGER_TIME, System.currentTimeMillis())

        showNotification(context, alarmId, message)
        rescheduleIfNeeded(context, alarmId, message, repeat, triggerTime)
    }

    private fun showNotification(context: Context, alarmId: Int, message: String) {
        createNotificationChannel(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val launchIntent = PendingIntent.getActivity(
            context,
            alarmId,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Local Assistant reminder")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(launchIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(context).notify(alarmId, notification)
    }

    private fun rescheduleIfNeeded(
        context: Context,
        alarmId: Int,
        message: String,
        repeat: String,
        previousTriggerTime: Long
    ) {
        val interval = when (repeat) {
            "daily" -> AlarmManager.INTERVAL_DAY
            "weekly" -> AlarmManager.INTERVAL_DAY * 7
            else -> return
        }

        val nextTriggerTime = previousTriggerTime + interval
        val nextIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmTool.ACTION_ALARM_TRIGGER
            putExtra(AlarmTool.EXTRA_ALARM_MESSAGE, message)
            putExtra(AlarmTool.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmTool.EXTRA_REPEAT, repeat)
            putExtra(AlarmTool.EXTRA_TRIGGER_TIME, nextTriggerTime)
        }
        val nextPendingIntent = PendingIntent.getBroadcast(
            context,
            AlarmTool.REQUEST_CODE_BASE + alarmId,
            nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTriggerTime, nextPendingIntent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTriggerTime, nextPendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, nextTriggerTime, nextPendingIntent)
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Reminder notifications created by Local Assistant"
        }
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "local_assistant_reminders"
    }
}
