package com.mojahid.watchcompanion

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EnglishTaskEngineTest {
    private lateinit var context: Context
    private lateinit var engine: EnglishTaskEngine

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("watch_companion_tasks", Context.MODE_PRIVATE)
            .edit().clear().commit()
        engine = EnglishTaskEngine(context)
    }

    @After fun tearDown() {
        context.getSharedPreferences("watch_companion_tasks", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun addCommandPersistsAfterCreatingNewEngine() {
        val result = engine.tryLocal("Add a task to buy milk")
        assertNotNull(result)
        assertTrue(result!!.message.contains("buy milk", ignoreCase = true))
        val fresh = EnglishTaskEngine(context)
        assertEquals(listOf("buy milk"), fresh.tasks().map { it.title })
        assertFalse(fresh.tasks().single().done)
    }

    @Test fun markDoneAndReopenSurviveRestart() {
        engine.tryLocal("Add a task to design a logo")
        val completed = engine.tryLocal("Mark design a logo as done")
        assertTrue(completed!!.tasks.single().done)
        assertTrue(EnglishTaskEngine(context).tasks().single().done)

        val reopened = engine.tryLocal("Reopen design a logo")
        assertFalse(reopened!!.tasks.single().done)
        assertFalse(EnglishTaskEngine(context).tasks().single().done)
    }

    @Test fun pendingListExcludesCompletedTasks() {
        engine.tryLocal("Add a task to design a logo")
        engine.tryLocal("Add a task to edit the menu")
        engine.tryLocal("Mark design a logo as done")
        val pending = engine.tryLocal("What tasks are left")
        assertNotNull(pending)
        assertTrue(pending!!.message.contains("edit the menu"))
        assertFalse(pending.message.contains("design a logo"))
    }

    @Test fun generalQuestionIsNotIncorrectlyConsumedAsTask() {
        val answer = engine.tryLocal("Why is the sky blue?")
        assertNull(answer)
        assertTrue(engine.tasks().isEmpty())
    }

    @Test fun speechSelectionDoesNotChangeTasks() {
        assertTrue(engine.tryLocalDryRun("add a task to buy milk"))
        assertTrue(engine.tryLocalDryRun("mark buy milk as done"))
        assertFalse(engine.tryLocalDryRun("what is quantum physics"))
        assertTrue(engine.tasks().isEmpty())
    }

    @Test fun vagueCompletedTaskDoesNotModifySeveralMatches() {
        engine.tryLocal("Add a task to design a landing page")
        engine.tryLocal("Add a task to design a restaurant logo")
        val result = engine.tryLocal("Mark design as done")
        assertNotNull(result)
        assertTrue(result!!.message.contains("more than one", ignoreCase = true))
        assertTrue(EnglishTaskEngine(context).tasks().none { it.done })
    }

    @Test fun reminderWordNeverClaimsAlarmWasSet() {
        val result = engine.tryLocal("Remind me to call the client")
        assertNotNull(result)
        assertTrue(result!!.message.contains("timed reminders aren't supported"))
        assertEquals("call the client", engine.tasks().single().title)
    }

    @Test fun repeatedSaveKeepsTasksInCorrectOrder() {
        val store = TaskStore(context)
        val value = listOf(TaskItem(101, "Design packaging"), TaskItem(102, "Print samples", true))
        assertTrue(store.save(value))
        assertEquals(value, TaskStore(context).load())
    }
}
