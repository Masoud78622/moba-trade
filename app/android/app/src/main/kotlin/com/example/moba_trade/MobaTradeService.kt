package com.example.moba_trade

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class MobaTradeService : Service() {

    companion object {
        private const val CHANNEL_ID = "MobaTradeEngineChannel"
        private const val CHANNEL_NAME = "MobaTrade Engine Background Service"
        private const val NOTIFICATION_ID = 1001
        
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            startForegroundService()
        } else if (action == ACTION_STOP) {
            stopForegroundService()
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        createNotificationChannel()

        // Create a PendingIntent to open the MainActivity when the notification is clicked
        val notificationIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (notificationIntent != null) {
            PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            null
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🤖 MOBA TRADE ENGINE ACTIVE")
            .setContentText("Live Auto-Scanning & Execution Gates Armed // Connected")
            .setSmallIcon(android.R.drawable.ic_media_play) // Standard system play icon for high compatibility
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Persistent non-dismissible notification
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun stopForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            var channel = manager.getNotificationChannel(CHANNEL_ID)
            if (channel == null) {
                channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps Moba Trade quantitative scanner running in background"
                    enableLights(true)
                    lightColor = Color.GREEN
                    enableVibration(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }
}
