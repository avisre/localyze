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
    private val smsDraftTool: SmsDraftTool,
    private val calculatorTool: CalculatorTool,
    private val metarDecoderTool: MetarDecoderTool,
    private val drugInteractionTool: DrugInteractionTool,
    private val foodSafetyTool: FoodSafetyTool
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
        register(calculatorTool)
        register(metarDecoderTool)
        register(drugInteractionTool)
        register(foodSafetyTool)
    }

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun unregister(name: String) {
        tools.remove(name)
    }

    fun getTool(name: String): Tool? = tools[name] ?: fuzzyTool(name)

    /**
     * The on-device model occasionally garbles tool names (e.g.
     * "metar_decode" → "ar_metar_decode" — same Adreno-style first-token
     * mangling we see on numbers in answers). Salvage that case by
     * matching against the registered names if either is a substring of
     * the other AND the lengths are close. Returns null if nothing close
     * enough is found.
     */
    private fun fuzzyTool(name: String): Tool? {
        // Strip everything but lowercase letters and digits, then compare.
        // Catches the model's mid-word underscore insertions and stray
        // prefix/suffix garbles ("met_ar_decode" / "ar_metar_decode" /
        // "metar-decode" all collapse to "metardecode").
        fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val n = normalize(name)
        val exact = tools.values.firstOrNull { normalize(it.name) == n }
        if (exact != null) return exact
        val candidates = tools.values.filter { reg ->
            val r = normalize(reg.name)
            (r.contains(n) || n.contains(r)) && kotlin.math.abs(r.length - n.length) <= 4
        }
        return candidates.singleOrNull()
    }

    fun getAllTools(): List<Tool> = tools.values.toList()

    fun getToolDescriptions(): String =
        tools.values.joinToString("\n") { "${it.name}: ${it.description}" }

    fun getToolSchemas(): List<JsonObject> = tools.values.map { it.getParameterSchema() }

    fun hasTool(name: String): Boolean = tools.containsKey(name)
}
