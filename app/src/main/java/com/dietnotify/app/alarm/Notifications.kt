package com.dietnotify.app.alarm

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dietnotify.app.R
import com.dietnotify.app.data.Meal
import com.dietnotify.app.ui.AlarmActivity
import com.dietnotify.app.ui.MainActivity
import com.dietnotify.app.ui.formatTime

object Notifications {

    private const val CHANNEL_REMINDER = "diet_reminders"
    private const val CHANNEL_ALARM = "diet_alarms"
    private const val REMINDER_ID_BASE = 100_000
    private const val ALARM_ID_BASE = 200_000

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val reminder = NotificationChannel(
            CHANNEL_REMINDER, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_reminders_desc)
            enableVibration(true)
        }
        val alarm = NotificationChannel(
            CHANNEL_ALARM, context.getString(R.string.channel_alarms), NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_alarms_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 600, 800)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        manager.createNotificationChannels(listOf(reminder, alarm))
    }

    fun show(context: Context, meal: Meal, kind: AlertKind, silent: Boolean = false) {
        if (!canNotify(context)) return
        createChannels(context)
        val time = formatTime(meal.hour, meal.minute)
        val builder = when (kind) {
            AlertKind.REMINDER -> NotificationCompat.Builder(context, CHANNEL_REMINDER)
                .setContentTitle(context.getString(R.string.reminder_title, meal.name, time))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
            AlertKind.ALARM -> NotificationCompat.Builder(context, CHANNEL_ALARM)
                .setContentTitle(context.getString(R.string.alarm_title, meal.name, time))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(alarmActivityIntent(context, meal), true)
        }
        val recipe = meal.recipe.ifBlank { context.getString(R.string.no_recipe) }
        builder
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentText(recipe)
            .setStyle(NotificationCompat.BigTextStyle().bigText(recipe))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Stays in the shade until the user dismisses it.
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(silent)
            .setContentIntent(
                if (kind == AlertKind.ALARM) alarmActivityIntent(context, meal) else openAppIntent(context)
            )
            .addAction(0, context.getString(R.string.dismiss), dismissIntent(context, meal.index))

        val notification = builder.build()
        if (kind == AlertKind.ALARM && !silent) {
            // Keep ringing until the user reacts (or the silence timeout kicks in).
            notification.flags = notification.flags or Notification.FLAG_INSISTENT
        }
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(notificationId(meal.index, kind), notification)
    }

    /** Re-posts a still-active alarm without sound, keeping the notification visible. */
    fun silenceAlarm(context: Context, meal: Meal) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val id = notificationId(meal.index, AlertKind.ALARM)
        if (manager.activeNotifications.any { it.id == id }) show(context, meal, AlertKind.ALARM, silent = true)
    }

    fun dismiss(context: Context, mealIndex: Int) {
        val manager = NotificationManagerCompat.from(context)
        AlertKind.entries.forEach { manager.cancel(notificationId(mealIndex, it)) }
    }

    fun cancelAll(context: Context) = NotificationManagerCompat.from(context).cancelAll()

    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun canUseFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    private fun notificationId(mealIndex: Int, kind: AlertKind) =
        (if (kind == AlertKind.REMINDER) REMINDER_ID_BASE else ALARM_ID_BASE) + mealIndex

    private fun alarmActivityIntent(context: Context, meal: Meal): PendingIntent {
        val intent = Intent(context, AlarmActivity::class.java)
            .putExtra(AlarmScheduler.EXTRA_MEAL_INDEX, meal.index)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, meal.index, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun dismissIntent(context: Context, mealIndex: Int): PendingIntent {
        val intent = Intent(context, DismissReceiver::class.java)
            .putExtra(AlarmScheduler.EXTRA_MEAL_INDEX, mealIndex)
        return PendingIntent.getBroadcast(
            context, mealIndex, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
