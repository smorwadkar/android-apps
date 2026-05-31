package com.mobildroid.cloudshelf.app.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import com.mobildroid.cloudshelf.app.R

/**
 * Channel + foreground notifications for transfer workers.
 *
 * The channel is created lazily the first time we need to post one — Android
 * is fine with creating it on every call (it's idempotent past the first).
 */
object TransferNotifications {

    const val CHANNEL_ID = "cloudshelf_transfers"

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_transfers),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_transfers_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * Build a foreground-service notification for a single transfer.
     *
     * @param notificationId Unique per transfer — must be stable across updates so
     *   WorkManager updates the same notification rather than stacking duplicates.
     * @param percent 0..100, or null for indeterminate.
     */
    fun buildForegroundInfo(
        context: Context,
        notificationId: Int,
        title: String,
        text: String,
        percent: Int?,
        workId: java.util.UUID
    ): ForegroundInfo {
        ensureChannel(context)

        val cancelIntent = WorkManager.getInstance(context).createCancelPendingIntent(workId)

        // Tap action opens MainActivity (will land on Transfers via standard nav).
        val tapIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }
        val tapPending = PendingIntent.getActivity(
            context,
            0,
            tapIntent ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tapPending)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.action_cancel),
                cancelIntent
            )

        if (percent != null) {
            builder.setProgress(100, percent.coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(notificationId, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, builder.build())
        }
    }
}
