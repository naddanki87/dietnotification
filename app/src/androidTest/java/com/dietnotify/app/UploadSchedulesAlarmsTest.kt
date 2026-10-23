package com.dietnotify.app

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.dietnotify.app.alarm.AlarmReceiver
import com.dietnotify.app.alarm.AlarmScheduler
import com.dietnotify.app.alarm.AlertKind
import com.dietnotify.app.data.DietPlan
import com.dietnotify.app.data.DietRepository
import com.dietnotify.app.parser.DietChartParser
import com.dietnotify.app.ui.UploadState
import com.dietnotify.app.ui.UploadViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime

/**
 * End-to-end check on a real device / emulator: uploading a diet chart PDF runs OCR,
 * parses the meals and registers a reminder and an alarm for every meal with Android.
 */
@RunWith(AndroidJUnit4::class)
class UploadSchedulesAlarmsTest {

    @get:Rule
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val repository = DietRepository(context)

    private val sunday = listOf(
        Triple("Early Morning", "06:00 AM", "Jeera Water (1 glass), Soaked Almond (4 almond)"),
        Triple("Breakfast", "09:00 AM", "Poha (1 bowl) with vegetables"),
        Triple("Lunch", "02:00 PM", "Oats Roti (2 roti), Moong Dal (1 cup)"),
        Triple("Dinner", "07:30 PM", "Daliya with Dal and Vegetable (1 bowl)"),
    )
    private val monday = listOf(
        Triple("Breakfast", "08:30 AM", "Rawa Idli (2 idli), Coconut chutney"),
        Triple("Mid Meal", "12:00 PM", "Papaya (1 cup), Sesame seeds (1 serve)"),
        Triple("Lunch", "01:30 PM", "Jeera rice (1 katori), Paneer Curry (1 katori)"),
        Triple("Dinner", "08:00 PM", "Oats Vegetable Khichdi (1 bowl)"),
    )

    @Before
    fun setUp() {
        repository.loadPlan()?.let { AlarmScheduler.cancelAll(context, it) }
        repository.deletePlan()
    }

    @After
    fun tearDown() = setUp()

    @Test
    fun uploadSchedulesReminderAndAlarmForEveryMeal() = runBlocking {
        assertTrue("exact alarms must be allowed", AlarmScheduler.canScheduleExact(context))

        val plan = upload(dietChart("week.pdf", listOf("Sunday" to sunday, "Monday" to monday)))
        Log.i(TAG, "Parsed plan: ${plan.meals}")

        // Every meal of the chart was read with the right day, time and recipe.
        assertEquals(8, plan.meals.size)
        val breakfast = plan.meals.single { it.dayOfWeek == 7 && it.name == "Breakfast" }
        assertEquals(9 to 0, breakfast.hour to breakfast.minute)
        assertTrue(breakfast.recipe, breakfast.recipe.contains("Poha"))
        val mondayLunch = plan.meals.single { it.dayOfWeek == 1 && it.name == "Lunch" }
        assertEquals(13 to 30, mondayLunch.hour to mondayLunch.minute)

        // A reminder and an alarm are queued for every meal.
        for (meal in plan.meals) for (kind in AlertKind.entries) {
            assertTrue("$kind for ${meal.name} day ${meal.dayOfWeek}", AlarmScheduler.isScheduled(context, plan.id, meal.index, kind))
        }

        // Android reports our next meal alarm as the device's upcoming alarm clock.
        val now = ZonedDateTime.now()
        val expected = plan.meals.minOf { AlarmScheduler.nextTrigger(it, AlertKind.ALARM, now).toInstant() }
        val alarmClock = context.getSystemService(AlarmManager::class.java).nextAlarmClock
        assertNotNull("no alarm clock registered", alarmClock)
        assertEquals(expected, Instant.ofEpochMilli(alarmClock!!.triggerTime))
        assertEquals(context.packageName, alarmClock.showIntent.creatorPackage)

        val dump = shell("dumpsys alarm")
        val ours = dump.lines().count { it.contains("com.dietnotify.app") && it.contains("RTC_WAKEUP") }
        Log.i(TAG, "AlarmManager holds $ours DietNotify alarms (expected ${plan.meals.size * 2})")
        assertTrue("dumpsys alarm shows $ours DietNotify alarms", ours >= plan.meals.size * 2)
    }

    @Test
    fun firedAlertShowsNotificationAndRepeatsNextWeek() = runBlocking {
        val plan = upload(dietChart("week.pdf", listOf("Sunday" to sunday)))
        val meal = plan.meals.first { it.name == "Breakfast" }

        for (kind in AlertKind.entries) {
            AlarmReceiver().onReceive(
                context,
                Intent(context, AlarmReceiver::class.java)
                    .setAction(AlarmScheduler.ACTION_FIRE)
                    .putExtra(AlarmScheduler.EXTRA_PLAN_ID, plan.id)
                    .putExtra(AlarmScheduler.EXTRA_MEAL_INDEX, meal.index)
                    .putExtra(AlarmScheduler.EXTRA_KIND, kind.code),
            )
            assertTrue(AlarmScheduler.isScheduled(context, plan.id, meal.index, kind))
        }

        val shown = context.getSystemService(NotificationManager::class.java).activeNotifications
        Log.i(TAG, "Notifications: ${shown.map { it.notification.extras.getString("android.title") }}")
        val titles = shown.map { it.notification.extras.getString("android.title").orEmpty() }
        assertTrue(titles.toString(), titles.contains("Upcoming: Breakfast at 09:00 AM"))
        assertTrue(titles.toString(), titles.contains("Time for Breakfast (09:00 AM)"))
        val text = shown.first().notification.extras.getCharSequence("android.text").toString()
        assertTrue(text, text.contains("Poha"))
    }

    @Test
    fun newUploadReplacesOldPlanAndItsAlarms() = runBlocking {
        val first = upload(dietChart("week.pdf", listOf("Sunday" to sunday, "Monday" to monday)))
        assertEquals(8, first.meals.size)

        val second = upload(dietChart("new.pdf", listOf("Monday" to monday)))
        assertEquals(4, second.meals.size)
        assertEquals(second.id, repository.loadPlan()!!.id)
        for (index in 4 until 8) for (kind in AlertKind.entries) {
            assertFalse("old alert $index still queued", AlarmScheduler.isScheduled(context, first.id, index, kind))
        }
    }

    @Test
    fun pdfThatIsNotADietChartIsRejected() = runBlocking {
        val file = pdf("invoice.pdf", listOf(listOf("INVOICE #4411", "Delivered 10:30 AM", "Total amount 4,500")))
        val state = process(Uri.fromFile(file))
        assertEquals(UploadState.Error(DietChartParser.INVALID_MESSAGE), state)
        assertEquals(null, repository.loadPlan())
    }

    private suspend fun upload(file: File): DietPlan {
        val state = process(Uri.fromFile(file))
        assertTrue("upload failed: $state", state is UploadState.Done)
        return (state as UploadState.Done).plan
    }

    private suspend fun process(uri: Uri): UploadState = withContext(Dispatchers.Main) {
        val viewModel = UploadViewModel(context)
        viewModel.process(uri)
        withTimeout(120_000) {
            viewModel.state.first { it is UploadState.Done || it is UploadState.Error }
        }
    }

    /** Draws a diet chart in the usual table layout: meal + time on the left, food on the right. */
    private fun dietChart(name: String, days: List<Pair<String, List<Triple<String, String, String>>>>): File {
        val doc = PdfDocument()
        val bold = Paint().apply { textSize = 16f; isFakeBoldText = true; isAntiAlias = true }
        val normal = Paint().apply { textSize = 13f; isAntiAlias = true }
        days.forEachIndexed { i, (day, meals) ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            val canvas = page.canvas
            canvas.drawText("DAY ${i + 1}", 40f, 50f, bold)
            canvas.drawText(day, 260f, 50f, bold)
            canvas.drawText("Diet Chart", 450f, 50f, bold)
            meals.forEachIndexed { m, (meal, time, food) ->
                val y = 130f + m * 110f
                canvas.drawText(meal, 40f, y, bold)
                canvas.drawText(time, 40f, y + 22f, normal)
                canvas.drawText(food, 200f, y + 10f, normal)
            }
            doc.finishPage(page)
        }
        return writePdf(name, doc)
    }

    private fun pdf(name: String, pages: List<List<String>>): File {
        val doc = PdfDocument()
        val paint = Paint().apply { textSize = 16f; isAntiAlias = true }
        pages.forEachIndexed { i, lines ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            lines.forEachIndexed { l, text -> page.canvas.drawText(text, 40f, 80f + l * 40f, paint) }
            doc.finishPage(page)
        }
        return writePdf(name, doc)
    }

    private fun writePdf(name: String, doc: PdfDocument): File {
        val file = File(context.cacheDir, name)
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    private fun shell(command: String): String {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
    }

    private companion object {
        const val TAG = "DietNotifyTest"
    }
}
