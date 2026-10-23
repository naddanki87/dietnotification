package com.dietnotify.app.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dietnotify.app.alarm.AlarmScheduler
import com.dietnotify.app.data.DietPlan
import com.dietnotify.app.data.DietRepository
import com.dietnotify.app.parser.DietChartParser
import com.dietnotify.app.parser.ParseResult
import com.dietnotify.app.parser.PdfTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class UploadState {
    data object Idle : UploadState()
    data class Reading(val page: Int, val total: Int) : UploadState()
    data class Error(val message: String) : UploadState()
    data class Done(val plan: DietPlan) : UploadState()
}

class UploadViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<UploadState>(UploadState.Idle)
    val state: StateFlow<UploadState> = _state

    fun process(uri: Uri) {
        if (_state.value is UploadState.Reading) return
        _state.value = UploadState.Reading(0, 0)
        viewModelScope.launch {
            _state.value = try {
                readPlan(uri)
            } catch (e: Exception) {
                // Unreadable, encrypted or corrupt files are rejected like any other non-diet PDF.
                UploadState.Error(DietChartParser.INVALID_MESSAGE)
            }
        }
    }

    private suspend fun readPlan(uri: Uri): UploadState {
        val app = getApplication<Application>()
        val type = app.contentResolver.getType(uri)
        if (type != null && type != "application/pdf") return UploadState.Error(DietChartParser.INVALID_MESSAGE)

        val pages = PdfTextExtractor(app).extract(uri) { page, total ->
            _state.value = UploadState.Reading(page, total)
        }
        return when (val result = withContext(Dispatchers.Default) { DietChartParser.parse(pages) }) {
            is ParseResult.Invalid -> UploadState.Error(result.reason)
            is ParseResult.Success -> withContext(Dispatchers.IO) {
                val repository = DietRepository(app)
                // A new chart always replaces the previous plan and every alert it created.
                repository.loadPlan()?.let { AlarmScheduler.cancelAll(app, it) }
                val plan = repository.savePlan(displayName(uri), result.meals)
                AlarmScheduler.scheduleAll(app, plan)
                UploadState.Done(plan)
            }
        }
    }

    private fun displayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: "Diet chart.pdf"
    }
}
