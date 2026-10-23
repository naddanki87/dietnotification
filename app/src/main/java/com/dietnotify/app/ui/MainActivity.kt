package com.dietnotify.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.dietnotify.app.data.DietRepository

/** Entry point: sends the user to registration, PDF upload or the plan overview. */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = DietRepository(this)
        val next = when {
            repository.userName.isNullOrBlank() -> RegisterActivity::class.java
            repository.loadPlan() == null -> UploadActivity::class.java
            else -> HomeActivity::class.java
        }
        startActivity(Intent(this, next))
        finish()
    }
}
