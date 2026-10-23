package com.mobileailocal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mobileailocal.data.localizedString

/**
 * 生成中だけ動かすフォアグラウンドサービス。通知を出してプロセスを生かし、
 * 画面を閉じても生成が途切れないようにする(推論そのものは ChatViewModel が行う)。
 */
class InferenceService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService で起動された後は、5秒以内に必ず startForeground を呼ぶ(停止の指示でも)
        val nm = getSystemService(NotificationManager::class.java)
        // 通知は目立たせない(通知の許可は宣言しないので、Android 13 以降は表示されない)。
        // 既存のチャンネルは重要度を変えられないため、新しい id を使い、旧チャンネルは消す
        nm.deleteNotificationChannel(OLD_CHANNEL)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, localizedString(R.string.notif_channel), NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) },
        )
        val open = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_nav_chat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(localizedString(R.string.notif_generating))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        ServiceCompat.startForeground(this, ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId) // 続けて届いた次の START があれば止めない
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "generation_quiet"
        private const val OLD_CHANNEL = "generation"
        private const val ID = 1
        private const val ACTION_STOP = "com.mobileailocal.action.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, InferenceService::class.java))
        }

        fun stop(context: Context) {
            // stopService だと、開始直後の停止でフォアグラウンド化の前に止まって落ちることがあるため、
            // 停止の指示も startForegroundService で送り、サービス側で startForeground してから止める
            val stop = Intent(context, InferenceService::class.java).setAction(ACTION_STOP)
            try {
                ContextCompat.startForegroundService(context, stop)
            } catch (e: Exception) {
                // 背景から起動できない場合は、従来どおり直接止める
                context.stopService(Intent(context, InferenceService::class.java))
            }
        }
    }
}
