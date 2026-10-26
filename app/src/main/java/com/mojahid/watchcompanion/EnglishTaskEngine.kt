package com.mojahid.watchcompanion

import android.content.Context
import java.util.Locale

data class TaskReply(val message: String, val tasks: List<TaskItem>, val understood: Boolean = true)
data class AiIntent(val action: String, val taskTitle: String, val answer: String)

class EnglishTaskEngine(context: Context) {
    private val store = TaskStore(context)

    fun tasks(): List<TaskItem> = store.load()

    private fun clean(value: String): String = value.trim()
        .trim(' ', '.', ',', '!', '?', '"', '\'', ':')
        .replace(Regex("""\s+"""), " ")
        .take(180)

    private fun actionReply(message: String): TaskReply = TaskReply(message, tasks())

    private fun pending(): TaskReply {
        val pending = tasks().filterNot { it.done }
        return actionReply(
            if (pending.isEmpty()) "You're all caught up. No pending tasks."
            else "You have " + pending.size + " tasks left: " +
                pending.take(12).joinToString("; ") { it.title } +
                if (pending.size > 12) "; and " + (pending.size - 12) + " more." else "."
        )
    }

    private fun add(titleValue: String, reminderRequested: Boolean = false): TaskReply {
        val title = clean(titleValue)
        if (title.length < 2) return actionReply("What task would you like me to add?")
        val updated = tasks() + TaskItem(System.currentTimeMillis(), title)
        if (!store.save(updated)) return actionReply("Sorry, I couldn't save that task. Please try again.")
        val note = if (reminderRequested) " It's saved as a task, but timed reminders aren't supported yet." else ""
        return TaskReply("Done. I've added: " + title + "." + note, updated)
    }

    private fun updateTask(titleValue: String, done: Boolean): TaskReply {
        val title = clean(titleValue).lowercase(Locale.ROOT)
        if (title.isBlank()) return actionReply("Which task do you mean?")
        val current = tasks()
        val exact = current.filter { it.title.lowercase(Locale.ROOT) == title }
        val matched = if (exact.isNotEmpty()) exact
            else current.filter { it.title.lowercase(Locale.ROOT).contains(title) ||
                title.contains(it.title.lowercase(Locale.ROOT)) }
        if (matched.isEmpty()) return TaskReply("I couldn't find that task. Please say its name again.", current)
        if (matched.size > 1) {
            return TaskReply("I found more than one matching task. Please use a more specific name.", current)
        }
        val chosen = matched.single()
        if (chosen.done == done) {
            return TaskReply(chosen.title + if (done) " is already done." else " is already pending.", current)
        }
        val updated = current.map { if (it.id == chosen.id) it.copy(done = done) else it }
        if (!store.save(updated)) return TaskReply("I couldn't save that change. Please try again.", current)
        return TaskReply(
            if (done) "Great! I've marked " + chosen.title + " as done."
            else "I've moved " + chosen.title + " back to pending.",
            updated
        )
    }

    fun tryLocal(raw: String): TaskReply? {
        val text = clean(raw)
        val lower = text.lowercase(Locale.ROOT)
        if (text.isBlank()) return actionReply("I didn't hear anything. Try speaking again.")

        if (lower.matches(Regex(""".*(what|which|show|list|tell|how many).*(tasks?|to.do|pending|left|unfinished|still need).*""")) ||
            lower in listOf("my tasks", "pending tasks", "tasks", "what is left", "what's left", "anything pending")) {
            return pending()
        }

        val reopenPatterns = listOf(
            Regex("""^(?:please\s+)?(?:reopen|restore)\s+(?:the\s+task\s+)?(.+)$""", RegexOption.IGNORE_CASE),
            Regex("""^(?:please\s+)?(?:mark|move)\s+(.+?)\s+(?:as|back to)\s+pending$""", RegexOption.IGNORE_CASE)
        )
        reopenPatterns.forEach { pattern ->
            pattern.find(text)?.let { return updateTask(it.groupValues[1], false) }
        }

        val donePatterns = listOf(
            Regex("""^(?:please\s+)?mark\s+(.+?)\s+(?:as\s+)?(?:done|complete|completed)$""", RegexOption.IGNORE_CASE),
            Regex("""^(?:i\s+)?(?:just\s+)?(?:finished|completed)\s+(.+)$""", RegexOption.IGNORE_CASE),
            Regex("""^(.+?)\s+(?:is\s+)?(?:done|finished|completed)$""", RegexOption.IGNORE_CASE),
            Regex("""^(?:please\s+)?(?:finish|complete)\s+(?:the\s+task\s+)?(.+)$""", RegexOption.IGNORE_CASE)
        )
        donePatterns.forEach { pattern ->
            pattern.find(text)?.let { return updateTask(it.groupValues[1], true) }
        }

        val addPatterns = listOf(
            Regex("""^(?:please\s+)?(?:add|create|save)\s+(?:a\s+)?(?:new\s+)?task(?:\s+to|\s+for|:)?\s+(.+)$""", RegexOption.IGNORE_CASE),
            Regex("""^(?:please\s+)?(?:add|put|save)\s+(.+?)\s+(?:to|in|on)\s+(?:my\s+)?(?:tasks?|to.do\s+list|task\s+list)$""", RegexOption.IGNORE_CASE),
            Regex("""^(?:please\s+)?(?:remember|remind\s+me)\s+to\s+(.+)$""", RegexOption.IGNORE_CASE)
        )
        addPatterns.forEachIndexed { index, pattern ->
            pattern.find(text)?.let { return add(it.groupValues[1], reminderRequested = index == 2) }
        }

        return null
    }

    fun applyAi(intent: AiIntent): TaskReply {
        return when (intent.action.lowercase(Locale.ROOT).trim()) {
            "add_task" -> add(intent.taskTitle)
            "complete_task" -> updateTask(intent.taskTitle, true)
            "reopen_task" -> updateTask(intent.taskTitle, false)
            "list_tasks" -> pending()
            "chat" -> TaskReply(
                clean(intent.answer).ifBlank { "I'm here! Ask me anything, or tell me about a task." }.take(400),
                tasks()
            )
            else -> TaskReply("I'm not sure what you meant. Could you rephrase that?", tasks(), false)
        }
    }

    fun noAiReply(): TaskReply = TaskReply(
        "I didn't understand that command. You can say: Add a task to finish the logo, " +
            "What tasks are left, or Mark the logo task as done. You can also enable free AI in Settings.",
        tasks(), false
    )
}
