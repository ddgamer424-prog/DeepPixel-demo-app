package com.deeppixel.app
// Copyright (c) 2026 DDgamer. All rights reserved.

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

/** Keeps the app (and the server processes it owns) alive while the screen is off. */
class ServerService : Service() {
    private var lock: PowerManager.WakeLock? = null
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("srv", "Running servers", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, "srv").setContentTitle("Minecraft server is running")
            .setSmallIcon(android.R.drawable.stat_sys_download_done).build())
        if (lock == null) lock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "deeppixel:srv").also { it.acquire() }
        return START_STICKY
    }
    override fun onDestroy() { lock?.release(); super.onDestroy() }
    companion object {
        fun start(c: Context) = c.startForegroundService(Intent(c, ServerService::class.java))
        fun stop(c: Context) { c.stopService(Intent(c, ServerService::class.java)) }
    }
}
