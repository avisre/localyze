package com.localyze.tools

import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolRegistry @Inject constructor(
    private val calendarTool: CalendarTool,
    private val contactsTool: ContactsTool,
    private val alarmTool: AlarmTool,
    private val clipboardTool: ClipboardTool,
    private val systemInfoTool: SystemInfoTool,
    private val webSearchTool: WebSearchTool,
    private val fileReaderTool: FileReaderTool,
    private val memoryTool: MemoryTool,
    private val taskTool: TaskTool,
    private val emailDraftTool: EmailDraftTool,
    private val smsDraftTool: SmsDraftTool
) {
    private val tools: MutableMap<String, Tool> = mutableMapOf()

    init {
        register(calendarTool)
        register(contactsTool)
        register(alarmTool)
        register(clipboardTool)
        register(systemInfoTool)
        register(webSearchTool)
        register(fileReaderTool)
        register(memoryTool)
        register(taskTool)
        register(emailDraftTool)
        register(smsDraftTool)
    }

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun unregister(name: String) {
        tools.remove(name)
    }

    fun getTool(name: String): Tool? = tools[name]

    fun getAllTools(): List<Tool> = tools.values.toList()

    fun getToolDescriptions(): String =
        tools.values.joinToString("\n") { "${it.name}: ${it.description}" }

    fun getToolSchemas(): List<JsonObject> = tools.values.map { it.getParameterSchema() }

    fun hasTool(name: String): Boolean = tools.containsKey(name)
}
