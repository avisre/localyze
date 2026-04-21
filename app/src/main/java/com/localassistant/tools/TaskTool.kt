package com.localassistant.tools

import com.localassistant.data.repository.TaskRepository
import com.localassistant.domain.models.Task
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

class TaskTool @Inject constructor(
    private val taskRepository: TaskRepository
) : Tool {

    override val name = "task"
    override val description = "Create, list, or complete tasks on the user's to-do list"

    // ── Schema ─────────────────────────────────────────────────────────────

    override fun getParameterSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", "string")
                put("description", "Action to perform: 'create' a new task, 'list' existing tasks, or 'complete' a task")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("create")); add(JsonPrimitive("list")); add(JsonPrimitive("complete"))
                })
            })
            put("title", buildJsonObject {
                put("type", "string")
                put("description", "Task title (required for create action)")
            })
            put("description_task", buildJsonObject {
                put("type", "string")
                put("description", "Task description (optional, for create action)")
            })
            put("due_date", buildJsonObject {
                put("type", "string")
                put("description", "Due date in ISO format, e.g. '2025-01-15' (optional, for create action)")
            })
            put("filter", buildJsonObject {
                put("type", "string")
                put("description", "Filter for listing tasks: 'pending', 'completed', or 'all' (default 'pending', for list action)")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("pending")); add(JsonPrimitive("completed")); add(JsonPrimitive("all"))
                })
            })
            put("task_id", buildJsonObject {
                put("type", "integer")
                put("description", "ID of the task to mark as completed (required for complete action)")
            })
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("action"))
        })
    }

    /**
     * Task create/complete modifies the task list, so it requires confirmation.
     */
    override fun requiresConfirmation(): Boolean = true

    // ── Execute ────────────────────────────────────────────────────────────

    override suspend fun execute(args: JsonObject): String {
        val action = args["action"]?.let { (it as JsonPrimitive).content }
            ?: return errorResult("Missing required parameter: action")

        return when (action) {
            "create" -> createTask(args)
            "list" -> listTasks(args)
            "complete" -> completeTask(args)
            else -> errorResult("Unknown action: $action. Use 'create', 'list', or 'complete'.")
        }
    }

    // ── Create ─────────────────────────────────────────────────────────────

    private suspend fun createTask(args: JsonObject): String {
        val title = args["title"]?.let { (it as JsonPrimitive).content }
            ?: return errorResult("Missing required parameter: title for create action")
        val description = args["description_task"]?.let { (it as JsonPrimitive).content } ?: ""
        val dueDateStr = args["due_date"]?.let { (it as JsonPrimitive).content }
        val dueDate = dueDateStr?.let { parseDateToMillis(it) }

        return try {
            val task = Task(
                title = title,
                description = description,
                isCompleted = false,
                dueDate = dueDate,
                createdAt = System.currentTimeMillis()
            )
            val savedTask = taskRepository.saveTask(task)

            buildJsonObject {
                put("success", true)
                put("id", savedTask.id)
                put("title", title)
                put("message", "Task created: $title")
            }.toString()
        } catch (e: Exception) {
            errorResult("Error creating task: ${e.message}")
        }
    }

    // ── List ───────────────────────────────────────────────────────────────

    private suspend fun listTasks(args: JsonObject): String {
        val filter = args["filter"]?.let { (it as JsonPrimitive).content } ?: "pending"

        return try {
            val tasksFlow = when (filter) {
                "pending" -> taskRepository.getPendingTasks()
                "completed" -> taskRepository.getCompletedTasks()
                "all" -> taskRepository.getAllTasks()
                else -> return errorResult("Unknown filter: $filter. Use 'pending', 'completed', or 'all'.")
            }

            // Collect the first emission from the Flow
            val tasks = tasksFlow.first()

            val taskList = tasks.map { task ->
                buildJsonObject {
                    put("id", task.id)
                    put("title", task.title)
                    put("description", task.description)
                    put("is_completed", task.isCompleted)
                    put("due_date", task.dueDate?.let { formatMillis(it) } ?: "")
                }
            }

            buildJsonObject {
                put("tasks", JsonArray(taskList))
                put("count", taskList.size)
                put("filter", filter)
            }.toString()
        } catch (e: Exception) {
            errorResult("Error listing tasks: ${e.message}")
        }
    }

    // ── Complete ───────────────────────────────────────────────────────────

    private suspend fun completeTask(args: JsonObject): String {
        val taskId = args["task_id"]?.let { (it as JsonPrimitive).content?.toLongOrNull() }
            ?: return errorResult("Missing required parameter: task_id for complete action")

        return try {
            val task = taskRepository.getTaskById(taskId)
                ?: return errorResult("Task not found with ID: $taskId")

            taskRepository.updateCompletionStatus(taskId, true)

            buildJsonObject {
                put("success", true)
                put("id", taskId)
                put("title", task.title)
                put("message", "Task completed: ${task.title}")
            }.toString()
        } catch (e: Exception) {
            errorResult("Error completing task: ${e.message}")
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun parseDateToMillis(dateStr: String): Long? {
        return try {
            val ld = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE)
            ld.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                val ldt = java.time.LocalDateTime.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun formatMillis(millis: Long): String {
        return try {
            java.time.Instant.ofEpochMilli(millis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .format(DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: Exception) {
            millis.toString()
        }
    }

    private fun errorResult(message: String): String = buildJsonObject {
        put("error", message)
    }.toString()
}