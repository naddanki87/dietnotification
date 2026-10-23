package com.dietnotify.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dietnotify.app.R
import com.dietnotify.app.alarm.AlarmScheduler
import com.dietnotify.app.alarm.AlertKind
import com.dietnotify.app.alarm.Notifications
import com.dietnotify.app.data.DietPlan
import com.dietnotify.app.data.DietRepository
import com.dietnotify.app.databinding.ActivityHomeBinding
import com.dietnotify.app.databinding.ItemDayBinding
import com.dietnotify.app.databinding.ItemMealBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.LocalDate

/** Shows the active weekly plan and lets the user replace it with a new chart. */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var repository: DietRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = DietRepository(this)

        val plan = repository.loadPlan()
        if (plan == null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        binding.greeting.text = getString(R.string.hello_name, repository.userName.orEmpty())
        binding.planSource.text = getString(
            R.string.plan_source, plan.fileName, plan.meals.size, AlarmScheduler.REMINDER_LEAD_MINUTES,
        )
        binding.uploadNewButton.setOnClickListener { confirmReplace(plan) }
        binding.testAlarmButton.setOnClickListener { testAlarm(plan) }
        renderPlan(plan)
    }

    override fun onResume() {
        super.onResume()
        renderWarnings()
    }

    private fun renderPlan(plan: DietPlan) {
        val inflater = LayoutInflater.from(this)
        val today = LocalDate.now().dayOfWeek.value
        // Start the week with today so the next meals are on top.
        val days = plan.meals.map { it.dayOfWeek }.distinct().sortedBy { (it - today + 7) % 7 }
        binding.dayList.removeAllViews()
        for (day in days) {
            val dayView = ItemDayBinding.inflate(inflater, binding.dayList, false)
            dayView.dayTitle.text = if (day == today) getString(R.string.today_label, dayName(day)) else dayName(day)
            plan.meals.filter { it.dayOfWeek == day }.sortedBy { it.hour * 60 + it.minute }.forEach { meal ->
                val row = ItemMealBinding.inflate(inflater, dayView.mealList, false)
                row.mealName.text = meal.name
                row.mealTime.text = formatTime(meal.hour, meal.minute)
                row.mealRecipe.text = meal.recipe.ifBlank { getString(R.string.no_recipe) }
                dayView.mealList.addView(row.root)
            }
            binding.dayList.addView(dayView.root)
        }
    }

    private fun renderWarnings() {
        val notificationsOff = !Notifications.canNotify(this)
        val exactOff = !AlarmScheduler.canScheduleExact(this)
        val fullScreenOff = !Notifications.canUseFullScreen(this)
        binding.warningCard.visibility = if (notificationsOff || exactOff || fullScreenOff) View.VISIBLE else View.GONE
        binding.warningText.text = getString(
            when {
                notificationsOff -> R.string.warning_notifications
                exactOff -> R.string.warning_exact
                else -> R.string.warning_full_screen
            }
        )
        binding.warningButton.setOnClickListener {
            val intent = when {
                notificationsOff -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                exactOff && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
                Build.VERSION.SDK_INT >= 34 ->
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
                else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            }
            runCatching { startActivity(intent) }
            // Alerts scheduled while exact alarms were not allowed are inexact; refresh them.
            repository.loadPlan()?.let { AlarmScheduler.scheduleAll(this, it) }
        }
    }

    private fun confirmReplace(plan: DietPlan) {
        val name = repository.userName.orEmpty()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.replace_title, name))
            .setMessage(getString(R.string.replace_message, name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.replace_confirm) { _, _ ->
                AlarmScheduler.cancelAll(this, plan)
                repository.deletePlan()
                Toast.makeText(this, R.string.plan_deleted, Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, UploadActivity::class.java))
                finish()
            }
            .show()
    }

    private fun testAlarm(plan: DietPlan) {
        val today = LocalDate.now().dayOfWeek.value
        val meal = plan.meals.firstOrNull { it.dayOfWeek == today } ?: plan.meals.first()
        Notifications.show(this, meal, AlertKind.REMINDER)
        Notifications.show(this, meal, AlertKind.ALARM)
        AlarmScheduler.scheduleSilence(this, plan.id, meal.index)
    }
}
