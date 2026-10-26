package com.mojahid.watchcompanion

import android.content.Context

class CommandEngine(context: Context) {
    private val store = TaskStore(context)

    private fun normalize(value: String): String =
        value.lowercase()
            .replace("কাজ", " task ")
            .replace("টাস্ক", " task ")
            .replace("যোগ", " add ")
            .replace("করো", " koro ")
            .replace("কর", " koro ")
            .replace("বাকি", " baki ")
            .replace("শেষ", " done ")
            .replace("হয়ে গেছে", " done ")
            .replace("হয়ে গেছে", " done ")
            .replace("পেন্ডিং", " pending ")
            .replace(Regex("[^a-z0-9\\u0980-\\u09FF ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    data class Result(val matched: Boolean, val reply: String, val tasks: List<TaskItem>)

    fun process(raw: String): Result {
        val text = raw.trim()
        if (text.isBlank()) return Result(false, "Kichu shunte paini.", store.load())
        val lower = normalize(text)
        var tasks = store.load()

        val pendingQuery = lower.contains("ki ki baki") || lower.contains("ki baki") ||
            lower.contains("what is pending") || lower.contains("pending task") ||
            lower == "pending" || lower == "baki" || (lower.contains("baki") && lower.contains("task"))

        if (pendingQuery) {
            val pending = tasks.filterNot { it.done }
            return Result(true, if (pending.isEmpty()) "Kono task baki nei." else "Baki ache: " + pending.joinToString(", ") { it.title }, tasks)
        }

        if (lower.contains("abar pending") || lower.contains("reopen") || lower.contains("back to pending")) {
            val name = text
                .replace(Regex("(?i)abar\\s+pending"), "")
                .replace(Regex("(?i)reopen"), "")
                .replace(Regex("(?i)back\\s+to\\s+pending"), "")
                .trim(' ', '.', ',')
            val match = tasks.lastOrNull { it.title.contains(name, true) || name.contains(it.title, true) }
                ?: return Result(true, "Task ta khuje pai ni.", tasks)
            tasks = tasks.map { if (it.id == match.id) it.copy(done = false) else it }
            store.save(tasks)
            return Result(true, "${match.title} abar pending korechi.", tasks)
        }

        val done = lower.contains(" done") || lower.endsWith("done") || lower.contains("complete") ||
            lower.contains("finished") || lower.endsWith(" dan") || lower.contains(" mark done")
        if (done) {
            val name = text
                .replace(Regex("(?i)\\bdone\\b"), "")
                .replace(Regex("(?i)\\bcomplete(d)?\\b"), "")
                .replace(Regex("(?i)\\bfinished\\b"), "")
                .replace(Regex("(?i)\\bdan\\b"), "")
                .replace(Regex("(?i)mark\\s+done"), "")
                .trim(' ', '.', ',')
            val match = tasks.lastOrNull { it.title.contains(name, true) || name.contains(it.title, true) }
                ?: return Result(true, "Kon task ta done bujhte parini.", tasks)
            tasks = tasks.map { if (it.id == match.id) it.copy(done = true) else it }
            store.save(tasks)
            return Result(true, "${match.title} done mark korechi.", tasks)
        }

        val add = lower.contains("add koro") || lower.contains("ad koro") || lower.contains("task add") ||
            lower.contains("add task") || lower.contains("task dao") || lower.contains("task rakho") ||
            lower.contains("list e add") || lower.contains("liste add") || lower.startsWith("add ")
        if (add) {
            val title = text
                .replace(Regex("(?i)task\\s+add\\s+koro"), "")
                .replace(Regex("(?i)add\\s+task"), "")
                .replace(Regex("(?i)add\\s+koro"), "")
                .replace(Regex("(?i)ad\\s+koro"), "")
                .replace(Regex("(?i)task\\s+dao"), "")
                .replace(Regex("(?i)task\\s+rakho"), "")
                .replace(Regex("(?i)list(e)?\\s+add"), "")
                .replace(Regex("(?i)^add\\s+"), "")
                .replace("যোগ", "").replace("করো", "").replace("কর", "").replace("টাস্ক", "").replace("কাজ", "")
                .trim(' ', '.', ',')
            if (title.isBlank()) return Result(true, "Task-er nam bolo.", tasks)
            tasks = tasks + TaskItem(System.currentTimeMillis(), title)
            store.save(tasks)
            return Result(true, "$title task add korechi.", tasks)
        }

        return Result(false, "Command ta bujhte parini.", tasks)
    }
}
