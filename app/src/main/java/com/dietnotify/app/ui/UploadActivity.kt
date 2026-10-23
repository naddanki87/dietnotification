package com.dietnotify.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dietnotify.app.R
import com.dietnotify.app.data.DietRepository
import com.dietnotify.app.databinding.ActivityUploadBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class UploadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUploadBinding
    private val viewModel: UploadViewModel by viewModels()
    private var shownError: UploadState.Error? = null

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.process(uri)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUploadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val name = DietRepository(this).userName.orEmpty()
        binding.greeting.text = getString(R.string.hello_name, name)
        binding.uploadButton.setOnClickListener { pickPdf.launch(arrayOf("application/pdf")) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && savedInstanceState == null) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(state: UploadState) {
        val reading = state is UploadState.Reading
        binding.uploadButton.isEnabled = !reading
        binding.progressGroup.visibility = if (reading) View.VISIBLE else View.GONE
        binding.errorText.visibility = View.GONE
        when (state) {
            is UploadState.Idle -> Unit
            is UploadState.Reading -> binding.progressText.text =
                if (state.total == 0) getString(R.string.opening_pdf)
                else getString(R.string.reading_page, state.page, state.total)
            is UploadState.Error -> {
                binding.errorText.text = state.message
                binding.errorText.visibility = View.VISIBLE
                if (shownError !== state) {
                    shownError = state
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.invalid_title)
                        .setMessage(R.string.invalid_message)
                        .setPositiveButton(R.string.ok, null)
                        .show()
                }
            }
            is UploadState.Done -> {
                Toast.makeText(
                    this, getString(R.string.plan_ready, state.plan.meals.size), Toast.LENGTH_LONG,
                ).show()
                startActivity(Intent(this, HomeActivity::class.java))
                finish()
            }
        }
    }
}
