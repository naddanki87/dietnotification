package com.dietnotify.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.dietnotify.app.data.DietPlan
import com.dietnotify.app.data.Meal
import com.dietnotify.app.ui.HomeActivity
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

enum class AlertKind(val code: Int) {
    /** Heads-up notification [AlarmScheduler.REMINDER_LEAD_MINUTES] before the meal. */
    REMINDER(0),

    /** Ringing alarm at the meal time. */
    ALARM(1);

    companion object {
        fun of(code: Int) = entries.first { it.code == code }
    }
}

/** Schedules a weekly repeating reminder and alarm for every meal of the plan. */
object AlarmScheduler {

    const val REMINDER_LEAD_MINUTES = 60L
    const val ACTION_FIRE = "com.dietnotify.app.action.FIRE"
    const val ACTION_SILENCE = "com.dietnotify.app.action.SILENCE"
    const val EXTRA_PLAN_ID = "plan_id"
    const val EXTRA_MEAL_INDEX = "meal_index"
    const val EXTRA_KIND = "kind"

    /** How long an alarm keeps ringing before it goes quiet (the notification stays). */
    private const val RING_DURATION_MILLIS = 5 * 60 * 1000L

    fun scheduleAll(context: Context, plan: DietPlan) {
        plan.meals.forEach { meal ->
            AlertKind.entries.forEach { kind -> schedule(context, plan.id, meal, kind) }
        }
    }

    fun cancelAll(context: Context, plan: DietPlan) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        plan.meals.forEach { meal ->
            AlertKind.entries.forEach { kind ->
                pendingIntent(context, ACTION_FIRE, plan.id, meal.index, kind, PendingIntent.FLAG_NO_CREATE)
                    ?.let { alarmManager.cancel(it); it.cancel() }
            }
            pendingIntent(context, ACTION_SILENCE, plan.id, meal.index, AlertKind.ALARM, PendingIntent.FLAG_NO_CREATE)
                ?.let { alarmManager.cancel(it); it.cancel() }
        }
        Notifications.cancelAll(context)
    }

    fun schedule(context: Context, planId: Long, meal: Meal, kind: AlertKind) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val triggerAt = nextTrigger(meal, kind, ZonedDateTime.now()).toInstant().toEpochMilli()
        val operation = pendingIntent(context, ACTION_FIRE, planId, meal.index, kind, 0)!!
        if (canScheduleExact(context)) {
            if (kind == AlertKind.ALARM) {
                val show = PendingIntent.getActivity(
                    context, 0, Intent(context, HomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                )
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), operation)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            }
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        }
    }

    /** Stops the ringing of an alarm after a while if the user has not dismissed it yet. */
    fun scheduleSilence(context: Context, planId: Long, mealIndex: Int) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val operation = pendingIntent(context, ACTION_SILENCE, planId, mealIndex, AlertKind.ALARM, 0)!!
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + RING_DURATION_MILLIS, operation,
        )
    }

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Next weekly occurrence of the meal's reminder or alarm strictly after [now]. */
    fun nextTrigger(meal: Meal, kind: AlertKind, now: ZonedDateTime): ZonedDateTime {
        val lead = if (kind == AlertKind.REMINDER) REMINDER_LEAD_MINUTES else 0L
        var mealTime = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(meal.dayOfWeek)))
            .withHour(meal.hour).withMinute(meal.minute).withSecond(0).withNano(0)
        while (!mealTime.minusMinutes(lead).isAfter(now)) mealTime = mealTime.plusWeeks(1)
        return mealTime.minusMinutes(lead)
    }

    private fun pendingIntent(
        context: Context,
        action: String,
        planId: Long,
        mealIndex: Int,
        kind: AlertKind,
        flags: Int,
    ): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("dietnotify://meal/$mealIndex/${kind.code}"))
            .putExtra(EXTRA_PLAN_ID, planId)
            .putExtra(EXTRA_MEAL_INDEX, mealIndex)
            .putExtra(EXTRA_KIND, kind.code)
        return PendingIntent.getBroadcast(
            context,
            mealIndex * 2 + kind.code,
            intent,
            flags or PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
