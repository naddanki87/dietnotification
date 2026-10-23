package com.dietnotify.app.data

import android.content.Context
import com.dietnotify.app.parser.ParsedMeal
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Meal(
    val index: Int,
    val dayOfWeek: Int,
    val name: String,
    val hour: Int,
    val minute: Int,
    val recipe: String,
)

data class DietPlan(
    val id: Long,
    val fileName: String,
    val meals: List<Meal>,
)

/** Stores the registered user name and the active diet plan on the device. */
class DietRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("diet_notify", Context.MODE_PRIVATE)
    private val planFile = File(appContext.filesDir, "diet_plan.json")

    var userName: String?
        get() = prefs.getString(KEY_NAME, null)
        set(value) = prefs.edit().putString(KEY_NAME, value).apply()

    fun loadPlan(): DietPlan? {
        if (!planFile.exists()) return null
        return runCatching {
            val json = JSONObject(planFile.readText())
            val array = json.getJSONArray("meals")
            val meals = (0 until array.length()).map { i ->
                val m = array.getJSONObject(i)
                Meal(
                    index = i,
                    dayOfWeek = m.getInt("day"),
                    name = m.getString("name"),
                    hour = m.getInt("hour"),
                    minute = m.getInt("minute"),
                    recipe = m.getString("recipe"),
                )
            }
            DietPlan(json.getLong("id"), json.getString("file"), meals)
        }.getOrNull()
    }

    fun savePlan(fileName: String, parsed: List<ParsedMeal>): DietPlan {
        val array = JSONArray()
        parsed.forEach { m ->
            array.put(
                JSONObject()
                    .put("day", m.dayOfWeek)
                    .put("name", m.name)
                    .put("hour", m.hour)
                    .put("minute", m.minute)
                    .put("recipe", m.recipe)
            )
        }
        val json = JSONObject()
            .put("id", System.currentTimeMillis())
            .put("file", fileName)
            .put("meals", array)
        planFile.writeText(json.toString())
        return loadPlan()!!
    }

    fun deletePlan() {
        planFile.delete()
    }

    companion object {
        private const val KEY_NAME = "user_name"
    }
}
