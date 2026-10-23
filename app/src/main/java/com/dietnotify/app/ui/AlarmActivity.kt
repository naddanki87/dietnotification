package com.dietnotify.app.ui

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.dietnotify.app.R
import com.dietnotify.app.alarm.AlarmScheduler
import com.dietnotify.app.alarm.Notifications
import com.dietnotify.app.data.DietRepository
import com.dietnotify.app.databinding.ActivityAlarmBinding

/** Full-screen alarm shown over the lock screen at meal time. */
class AlarmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bind()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind()
    }

    private fun bind() {
        val index = intent.getIntExtra(AlarmScheduler.EXTRA_MEAL_INDEX, -1)
        val meal = DietRepository(this).loadPlan()?.meals?.getOrNull(index)
        if (meal == null) {
            finish()
            return
        }
        binding.mealName.text = meal.name
        binding.mealTime.text = getString(R.string.alarm_time_label, formatTime(meal.hour, meal.minute))
        binding.mealRecipe.text = meal.recipe.ifBlank { getString(R.string.no_recipe) }
        binding.dismissButton.setOnClickListener {
            Notifications.dismiss(this, index)
            finish()
        }
        binding.laterButton.setOnClickListener {
            Notifications.silenceAlarm(this, meal)
            finish()
        }
    }
}
