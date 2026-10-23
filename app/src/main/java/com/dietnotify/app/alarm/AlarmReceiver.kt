package com.dietnotify.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dietnotify.app.data.DietRepository

/** Fires the reminder / alarm of a meal and queues the same alert for next week. */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val plan = DietRepository(context).loadPlan() ?: return
        // Ignore alerts left over from a diet plan that has since been replaced.
        if (intent.getLongExtra(AlarmScheduler.EXTRA_PLAN_ID, -1) != plan.id) return
        val meal = plan.meals.getOrNull(intent.getIntExtra(AlarmScheduler.EXTRA_MEAL_INDEX, -1)) ?: return
        val kind = AlertKind.of(intent.getIntExtra(AlarmScheduler.EXTRA_KIND, AlertKind.REMINDER.code))

        when (intent.action) {
            AlarmScheduler.ACTION_FIRE -> {
                Notifications.show(context, meal, kind)
                if (kind == AlertKind.ALARM) AlarmScheduler.scheduleSilence(context, plan.id, meal.index)
                AlarmScheduler.schedule(context, plan.id, meal, kind)
            }
            AlarmScheduler.ACTION_SILENCE -> Notifications.silenceAlarm(context, meal)
        }
    }
}

/** Restores all alerts after a reboot, app update or clock / time zone change. */
class RescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val plan = DietRepository(context).loadPlan() ?: return
        AlarmScheduler.scheduleAll(context, plan)
    }
}

/** Handles the "Dismiss" button on reminder and alarm notifications. */
class DismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Notifications.dismiss(context, intent.getIntExtra(AlarmScheduler.EXTRA_MEAL_INDEX, -1))
    }
}
