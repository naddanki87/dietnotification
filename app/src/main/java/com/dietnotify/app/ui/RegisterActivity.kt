package com.dietnotify.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import com.dietnotify.app.R
import com.dietnotify.app.data.DietRepository
import com.dietnotify.app.databinding.ActivityRegisterBinding

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.registerButton.setOnClickListener { register() }
        binding.nameInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) register()
            false
        }
    }

    private fun register() {
        val name = binding.nameInput.text?.toString()?.trim().orEmpty()
        if (name.length < 2) {
            binding.nameLayout.error = getString(R.string.name_error)
            return
        }
        binding.nameLayout.error = null
        DietRepository(this).userName = name
        startActivity(Intent(this, UploadActivity::class.java))
        finish()
    }
}
