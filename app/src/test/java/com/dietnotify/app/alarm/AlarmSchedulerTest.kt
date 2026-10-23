package com.dietnotify.app.alarm

import com.dietnotify.app.data.Meal
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmSchedulerTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val sundayBreakfast = Meal(0, 7, "Breakfast", 8, 30, "Poha")

    @Test
    fun reminderIsOneHourBeforeAlarm() {
        val now = ZonedDateTime.of(2026, 10, 23, 12, 0, 0, 0, zone) // Friday
        assertEquals(
            ZonedDateTime.of(2026, 10, 25, 7, 30, 0, 0, zone),
            AlarmScheduler.nextTrigger(sundayBreakfast, AlertKind.REMINDER, now),
        )
        assertEquals(
            ZonedDateTime.of(2026, 10, 25, 8, 30, 0, 0, zone),
            AlarmScheduler.nextTrigger(sundayBreakfast, AlertKind.ALARM, now),
        )
    }

    @Test
    fun repeatsNextWeekOnceTodaysTimeHasPassed() {
        val now = ZonedDateTime.of(2026, 10, 25, 8, 0, 0, 0, zone) // Sunday, after the reminder
        assertEquals(
            ZonedDateTime.of(2026, 11, 1, 7, 30, 0, 0, zone),
            AlarmScheduler.nextTrigger(sundayBreakfast, AlertKind.REMINDER, now),
        )
        assertEquals(
            ZonedDateTime.of(2026, 10, 25, 8, 30, 0, 0, zone),
            AlarmScheduler.nextTrigger(sundayBreakfast, AlertKind.ALARM, now),
        )
    }
}
