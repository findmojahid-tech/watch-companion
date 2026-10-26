package com.mojahid.watchcompanion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TaskItem(val id: Long, val title: String, val done: Boolean = false)

class TaskStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "watch_companion_tasks", Context.MODE_PRIVATE
    )

    fun load(): List<TaskItem> {
        val json = prefs.getString("tasks", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    add(TaskItem(item.getLong("id"), item.getString("title"), item.optBoolean("done")))
                }
            }
        }.getOrDefault(emptyList())
    }

    // Synchronous persistence: don't tell the user a task was saved until it was.
    fun save(tasks: List<TaskItem>): Boolean {
        val json = JSONArray()
        tasks.forEach { task ->
            json.put(JSONObject().apply {
                put("id", task.id)
                put("title", task.title)
                put("done", task.done)
            })
        }
        return prefs.edit().putString("tasks", json.toString()).commit()
    }
}
