package com.example.altitudeapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

class DailyStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            ACTION_DAILY_START -> {
                startService(context)
                scheduleDailyAlarm(context)
            }
            ACTION_WATCHDOG -> {
                startService(context)
                scheduleWatchdog(context)
            }
        }
    }

    private fun startService(context: Context) {
        val serviceIntent = Intent(context, AltitudeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    companion object {
        const val ACTION_DAILY_START = "com.example.altitudeapp.DAILY_START"
        const val ACTION_WATCHDOG = "com.example.altitudeapp.WATCHDOG"
        private const val ALARM_REQUEST_CODE = 9001
        private const val WATCHDOG_REQUEST_CODE = 9002
        private const val WATCHDOG_INTERVAL_MS = 60 * 1000L // 1 minute

        fun scheduleDailyAlarm(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)

            val next9am = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 9)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                Intent(context, DailyStartReceiver::class.java).apply { action = ACTION_DAILY_START },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, next9am.timeInMillis, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    next9am.timeInMillis,
                    pendingIntent
                )
            }
        }

        fun scheduleWatchdog(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WATCHDOG_REQUEST_CODE,
                Intent(context, DailyStartReceiver::class.java).apply { action = ACTION_WATCHDOG },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // Inexact is fine for a watchdog — no need for exact timing
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + WATCHDOG_INTERVAL_MS,
                pendingIntent
            )
        }
    }
}
