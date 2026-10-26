package com.mojahid.watchcompanion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TaskItem(val id: Long, val title: String, val done: Boolean = false)

class TaskStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("watch_companion_tasks", Context.MODE_PRIVATE)

    fun load(): List<TaskItem> {
        val raw = prefs.getString("tasks", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(TaskItem(o.getLong("id"), o.getString("title"), o.optBoolean("done", false)))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(tasks: List<TaskItem>) {
        val arr = JSONArray()
        tasks.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("title", it.title)
                put("done", it.done)
            })
        }
        prefs.edit().putString("tasks", arr.toString()).apply()
    }
}
