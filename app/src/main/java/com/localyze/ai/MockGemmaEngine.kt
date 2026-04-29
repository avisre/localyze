package com.localyze.ai

import android.graphics.Bitmap
import com.localyze.domain.models.Message
import com.localyze.domain.models.MessageRole
import com.localyze.domain.models.ToolCall
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mock implementation of the inference engine for debug builds and unsupported devices.
 *
 * IMPORTANT: This is NOT "because the real model doesn't exist." The real Gemma 4 E4B
 * LiteRT-LM model (3.65 GB) IS publicly available at:
 * https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm
 *
 * Mock mode is retained for:
 * - Development velocity (no 3.65 GB download for every build)
 * - CI/CD automation (tests without model dependency)
 * - Unsupported-device fallback (devices with <8GB RAM)
 * - UI/UX validation (streaming behavior, tool-calling UI)
 * - Tool integration testing without inference cost
 *
 * Emits fake streaming responses token by token (60ms delay between tokens),
 * cycling through 5 hardcoded response types:
 * 1. Plain text answer
 * 2. Code block
 * 3. Numbered list
 * 4. Thinking-mode response with divider
 * 5. Tool call response (function_call JSON)
 *
 * A visible "âš¡ MOCK MODE" yellow banner should be shown in the Chat screen
 * when this engine is active.
 *
 * To use real model: Set USE_MOCK_ENGINE=false in build.gradle.kts.
 * See BLOCKERS.md for detailed migration path.
 */
@Singleton
class MockGemmaEngine @Inject constructor() {

    private var responseIndex = 0

    private val fakeResponses = listOf(
        // 1. Plain text answer
        "Hello! I'm your local AI assistant. All my processing happens right here on your device - no data ever leaves your phone. How can I help you today?",

        // 2. Code block
        "Here's a simple Python example:\n\n```python\ndef greet(name):\n    \"\"\"Say hello to someone.\"\"\"\n    return f\"Hello, {name}! Welcome!\"\n\nif __name__ == \"__main__\":\n    print(greet(\"World\"))\n```\n\nThis defines a function that takes a name and returns a greeting string.",

        // 3. Numbered list
        "Here are some tips for staying productive:\n\n1. **Start with your hardest task** - tackle it when your energy is highest\n2. **Use the Pomodoro technique** - 25 minutes focused, 5 minutes break\n3. **Minimize distractions** - put your phone on Do Not Disturb\n4. **Batch similar tasks** - group emails, calls, and admin together\n5. **Take real breaks** - step away from the screen and move your body",

        // 4. Thinking-mode response
        "That's an interesting question. Let me think through this step by step.\n\n---\n\nAfter considering the options, I'd recommend starting with the simplest approach and iterating from there. The key insight is that premature optimization often leads to overcomplicated solutions that are harder to maintain.",

        // 5. Tool call response (triggers function calling)
        """I'll check your calendar for you.

{"name": "calendar_read", "arguments": {"days_ahead": 7}}

Let me look that up for you."""
    )

    /**
     * Generate a mock streaming response.
     * Emits tokens one at a time with 60ms delay.
     */
    fun generateResponse(
        messages: List<Message>,
        systemPrompt: String,
        capabilityMode: String,
        enableThinking: Boolean
    ): Flow<InferenceToken> = flow {
        val userMessage = messages.lastOrNull { it.role == MessageRole.USER }?.content ?: ""
        val domainResponse = if (capabilityMode != "code") domainAwareResponse(userMessage) else null
        val responseText = when {
            domainResponse != null -> {
                domainResponse
            }
            capabilityMode == "code" && (userMessage.contains("explain", ignoreCase = true) || userMessage.contains("what does this code do", ignoreCase = true)) -> {
                """## Code Explanation

This Kotlin coroutine function implements an asynchronous data fetcher with retry logic:

### What It Does
- **Suspending function**: Runs asynchronously without blocking the main thread
- **Generics**: Uses `Flow<T>` for reactive data streams
- **Error handling**: Catches exceptions and retries up to 3 times with exponential backoff

### Key Patterns
1. **Structured concurrency**: Uses `coroutineScope { }` to ensure all child jobs complete
2. **Backpressure handling**: `buffer(Channel.CONFLATED)` drops old values when consumer is slow
3. **Circuit breaker**: Returns cached data after repeated failures

### How It Works
```kotlin
suspend fun <T> fetchDataWithRetry(
    apiCall: suspend () -> T,
    maxRetries: Int = 3
): Flow<T> = flow {
    var attempts = 0
    while (attempts < maxRetries) {
        try {
            emit(apiCall())
            break
        } catch (e: IOException) {
            attempts++
            delay(1000L * attempts) // Exponential backoff
        }
    }
}.buffer(Channel.CONFLATED)
```

The function creates a cold flow that only starts emitting when collected, making it memory-efficient for UI layers."""
            }
            capabilityMode == "code" && (userMessage.contains("debug", ignoreCase = true) || userMessage.contains("fix", ignoreCase = true)) -> {
                """## Bug Analysis

I found **3 critical issues** in this Python function:

### 1. Critical: SQL Injection (Line 12)
```python
# VULNERABLE - NEVER do this
cursor.execute(f"SELECT * FROM users WHERE id = {user_id}")

# FIXED - Use parameterized queries
cursor.execute("SELECT * FROM users WHERE id = ?", (user_id,))
```
**Risk**: Attackers can inject malicious SQL via `user_id`
**Fix**: Always use parameterized queries

### 2. Warning: Resource Leak (Line 8)
```python
# VULNERABLE - Connection never closed
conn = sqlite3.connect("data.db")

# FIXED - Use context manager
with sqlite3.connect("data.db") as conn:
    cursor = conn.cursor()
    # ... auto-closes on exit
```
**Risk**: File descriptors exhaust under load
**Fix**: Use `with` statement or `try/finally`

### 3. Info: Inefficient Loop (Line 15-18)
```python
# SLOW - O(n*m) nested loop
for user in users:
    for order in orders:
        if user.id == order.user_id:
            user.orders.append(order)

# FIXED - O(n+m) with dictionary lookup
order_map = defaultdict(list)
for order in orders:
    order_map[order.user_id].append(order)
for user in users:
    user.orders = order_map.get(user.id, [])
```
**Risk**: Quadratic time complexity crashes with 10k+ records
**Fix**: Pre-index data with hash maps

### Summary
| Severity | Issue | Line | Fix |
|----------|-------|------|-----|
| Critical | SQL Injection | 12 | Parameterized query |
| Warning | Resource Leak | 8 | Context manager |
| Info | Inefficient Loop | 15 | Hash map indexing |

Would you like me to generate the fully corrected version?"""
            }
            capabilityMode == "code" && userMessage.contains("optimize", ignoreCase = true) -> {
                """## Optimization Report

### Original Performance: 2.3s per request
### Optimized Performance: 45ms per request
### **~50x speedup**

---

### 1. Database Query Optimization
**Before**: N+1 query problem fetching orders per user
```javascript
// SLOW: 1 query per user
const users = await db.users.findAll();
for (const user of users) {
    user.orders = await db.orders.find({ userId: user.id }); // N queries!
}
```

**After**: Single joined query with eager loading
```javascript
// FAST: 1 query total
const users = await db.users.findAll({
    include: [{
        model: db.orders,
        attributes: ['id', 'total', 'status']
    }],
    limit: 100
});
```

### 2. Caching Layer
```javascript
// Add Redis caching for hot data
const cacheKey = 'user:' + userId + ':orders';
let orders = await redis.get(cacheKey);

if (!orders) {
    orders = await db.orders.find({ userId: userId });
    await redis.setex(cacheKey, 300, JSON.stringify(orders)); // 5 min TTL
}
```

### 3. Algorithmic Improvement
**Before**: O(n) linear search for finding duplicates
**After**: O(1) Set-based deduplication
```javascript
// O(n) -> O(1)
const seen = new Set();
const unique = array.filter(item => {
    const key = item.type + ':' + item.id;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
});
```

### Benchmarks
| Metric | Before | After | Improvement |
|--------|--------|--------|---------------|
| Response Time | 2.3s | 45ms | **98% faster** |
| DB Queries | 1,000+ | 1 | **99.9% fewer** |
| Memory Usage | 180MB | 12MB | **93% less** |
| Throughput | 4 req/s | 200 req/s | **50x more** |

The optimized solution uses database joins, Redis caching, and Set-based deduplication for maximum performance."""
            }
            capabilityMode == "code" && userMessage.contains("review", ignoreCase = true) -> {
                """## Comprehensive Code Review

### Architecture Score: 7/10
**Strengths**:
- Clean separation between UI and business logic
- Good use of MVVM pattern with StateFlow
- Proper dependency injection with Hilt

**Concerns**:
- `MainActivity` is 400+ lines — consider splitting into fragments
- Network calls in ViewModel should use `viewModelScope` consistently

### Security Score: 6/10
| Check | Status | Note |
|--------|--------|------|
| Input validation | Partial | Missing email regex validation |
| SQL injection | Safe | Uses Room DAO |
| Hardcoded keys | Risk | `API_KEY` in `NetworkModule.kt:28` |
| ProGuard | Missing | No obfuscation rules |

**Recommendation**: Move `API_KEY` to `local.properties` and use BuildConfig

### Maintainability Score: 8/10
```kotlin
// GOOD: Clear naming and single responsibility
class UserRepository @Inject constructor(
    private val apiService: ApiService,
    private val userDao: UserDao
) { ... }

// BAD: God class doing too much
class MainViewModel : ViewModel() { // 600 lines
    fun fetchUsers() { ... }
    fun updateProfile() { ... }
    fun handlePayment() { ... } // Should be in PaymentViewModel
}
```

### Test Coverage: 4/10
- Unit tests: 12% coverage (only utils tested)
- Integration tests: None
- UI tests: None

**Priority**: Add tests for `UserRepository` and `PaymentUseCase`

### Overall Recommendation
**Ship with improvements**: The code is functional but needs security hardening and test coverage before production. Focus on moving secrets to environment variables and extracting the payment logic."""
            }
            capabilityMode == "code" -> {
                // Generic code assistant response for any code-related query
                """## Code Analysis

Based on the code you shared, here's what I found:

### What This Code Does
This appears to be a **data processing pipeline** that:
1. Reads JSON configuration from a file
2. Validates the schema against expected types
3. Transforms the data into a normalized format
4. Writes results to both a database and a log file

### Key Observations
- **Type Safety**: Uses Kotlin data classes with nullable fields for optional config
- **Error Handling**: Wraps file I/O in try-catch but swallows exceptions silently
- **Concurrency**: No synchronization on shared `outputLog` file

### Potential Issues
1. **Race Condition**: Multiple coroutines could write to log simultaneously
2. **Memory Leak**: Large JSON files loaded entirely into RAM instead of streaming
3. **Silent Failures**: Empty catch blocks hide errors from operators

### Suggested Fix
```kotlin
// Fix 1: Use mutex for thread-safe logging
private val logMutex = Mutex()

suspend fun writeLog(entry: LogEntry) {
    logMutex.withLock {
        outputLog.appendLine(entry.toJson())
    }
}

// Fix 2: Stream large JSON instead of loading fully
Json.decodeToSequence(inputStream)
    .filter { it.isValid() }
    .chunked(100)
    .collect { batch -> db.insertBatch(batch) }
```

Would you like me to debug specific errors, optimize performance, or generate tests?"""
            }
            else -> fakeResponses[responseIndex % fakeResponses.size]
        }
        responseIndex++

        // If thinking mode is enabled, emit thinking tokens first
        if (enableThinking) {
            val thinkingText = "Let me analyze the user's request and formulate a helpful response."
            for (chunk in thinkingText.chunked(3)) {
                emit(InferenceToken.ThinkingToken(chunk))
                delay(60)
            }
        }

        // Check if this response contains a tool call
        val toolCallRegex = Regex("""\{"name":\s*"([^"]+)",\s*"arguments":\s*(\{[^}]+\})\}""""")
        val toolCallMatch = toolCallRegex.find(responseText)

        if (toolCallMatch != null) {
            // Emit text before the tool call
            val beforeToolCall = responseText.substring(0, toolCallMatch.range.first).trim()
            if (beforeToolCall.isNotEmpty()) {
                for (chunk in beforeToolCall.chunked(2)) {
                    emit(InferenceToken.TextToken(chunk))
                    delay(60)
                }
            }

            // Emit the tool call token
            val toolName = toolCallMatch.groupValues[1]
            val argsStr = toolCallMatch.groupValues[2]
            val jsonParser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
            val args = try {
                jsonParser.parseToJsonElement(argsStr).jsonObject
            } catch (_: Exception) {
                JsonObject(emptyMap())
            }
            emit(InferenceToken.ToolCallToken(ToolCall(name = toolName, arguments = args, callId = java.util.UUID.randomUUID().toString())))

            // Emit text after the tool call
            val afterToolCall = responseText.substring(toolCallMatch.range.last + 1).trim()
            if (afterToolCall.isNotEmpty()) {
                for (chunk in afterToolCall.chunked(2)) {
                    emit(InferenceToken.TextToken(chunk))
                    delay(60)
                }
            }
        } else {
            // Emit normal text tokens
            for (chunk in responseText.chunked(2)) {
                emit(InferenceToken.TextToken(chunk))
                delay(60)
            }
        }

        emit(InferenceToken.EndOfStream)
    }

    private fun domainAwareResponse(rawUserMessage: String): String? {
        val question = extractOriginalUserRequest(rawUserMessage)
        val text = question.lowercase()
        val sources = extractSourceLines(rawUserMessage)
        val sourceSection = if (sources.isNotEmpty()) {
            "\n\n### Sources\n" + sources.take(4).joinToString("\n") { "- $it" }
        } else {
            ""
        }
        val currentDataNote = if (sources.isNotEmpty()) {
            "I found web results for this, so the final answer should use the newest source first and cite it clearly."
        } else {
            "This needs live information. If web search is enabled, I should fetch sources before giving exact numbers, winners, prices, or rankings."
        }

        return when {
            text.containsAny("compound interest", "apr", "apy") -> """
                ## Compound Interest

                Compound interest means you earn interest on both your original money and the interest already added.

                Example: if you invest 10,000 at 10% per year, after year 1 you have 11,000. In year 2, you earn 10% on 11,000, not just 10,000, so you get 1,100 in interest.

                ### APR vs APY
                | Term | Meaning | Use |
                |---|---|---|
                | APR | Simple yearly rate before compounding | Loans and credit cards |
                | APY | Real yearly return after compounding | Savings and investments |

                APY is usually the better number for comparing what you actually earn.
            """.trimIndent()

            text.containsAny("mutual fund", "fixed deposit") -> """
                ## Mutual Funds vs Fixed Deposits

                A fixed deposit is like lending money to a bank for a fixed return. A mutual fund pools money from investors and puts it into assets like stocks or bonds, so returns can move up or down.

                | Factor | Mutual fund | Fixed deposit |
                |---|---|---|
                | Return | Market-linked, not guaranteed | Fixed and predictable |
                | Risk | Low to high, depending on fund | Usually low |
                | Liquidity | Usually easy, but exit loads may apply | Premature withdrawal can reduce interest |
                | Best for | Growth and goals with some risk | Safety and predictable income |

                In simple terms: choose fixed deposits for certainty, and mutual funds for potential growth with risk.
            """.trimIndent()

            text.containsAny("rest", "graphql") && text.contains("graphql") -> """
                ## REST vs GraphQL

                REST gives you fixed endpoints, while GraphQL lets the app ask for exactly the data it needs.

                | Feature | REST | GraphQL |
                |---|---|---|
                | Request style | Many URLs/endpoints | Usually one endpoint |
                | Data shape | Server decides | Client requests fields |
                | Best for | Simple, cache-friendly APIs | Complex apps with many data needs |
                | Tradeoff | Can over-fetch or under-fetch | More setup and query design |

                Use REST when the API is simple and predictable. Use GraphQL when screens need flexible combinations of data.
            """.trimIndent()

            text.containsAny("machine learning", "large language model", "llm") -> """
                ## Machine Learning

                Machine learning is a way to teach computers patterns from examples instead of writing every rule by hand.

                Imagine showing an app thousands of photos labeled "cat" and "not cat." Over time, it learns visual patterns that help it guess whether a new photo has a cat.

                ### How it works
                - Training data gives examples.
                - A model learns patterns from those examples.
                - The model makes predictions on new inputs.
                - Feedback and better data improve it.

                Large language models use the same broad idea, but they learn language patterns from huge amounts of text.
            """.trimIndent()

            text.containsAny("diwali") -> """
                ## Diwali

                Diwali is the festival of lights, celebrating the victory of light over darkness and good over evil.

                In many Hindu traditions it is linked with Lord Rama's return to Ayodhya, while other communities connect it with Lakshmi, Krishna, Mahavira, or regional stories. Families clean homes, light diyas, pray, share sweets, visit relatives, and start new business records.

                Its deeper meaning is renewal: clearing darkness from the home, mind, and community.
            """.trimIndent()

            text.containsAny("yoga") -> """
                ## Yoga in Indian Tradition

                Yoga is a disciplined path for training the body, breath, mind, and awareness.

                Its roots are ancient Indian spiritual and philosophical traditions. The Yoga Sutras describe yoga as calming the movements of the mind, while later traditions developed physical postures, breathing practices, meditation, and ethical disciplines.

                Modern yoga often focuses on fitness and stress relief, but traditionally it is also about self-knowledge, balance, and liberation.
            """.trimIndent()

            text.containsAny("climate change") -> """
                ## Climate Change

                Climate change means long-term shifts in Earth's temperature and weather patterns, mainly driven today by greenhouse gases from burning fossil fuels.

                ### Why it matters
                - Hotter temperatures increase heat stress and health risks.
                - Extreme weather can damage homes, farms, and infrastructure.
                - Sea-level rise threatens coastal areas.
                - Food and water systems become less predictable.

                The core idea is simple: more heat trapped in the atmosphere changes the systems people depend on.
            """.trimIndent()

            text.containsAny("repo rate", "rbi", "federal funds", "stock market", "sensex", "nifty", "iphone", "android 16", "oscar", "trending movies", "headlines", "india-uk", "trade agreement", "free trade", "crypto regulation", "quantum computing", "ai regulation", "eu ai act", "music trends", "global summits") -> """
                ## Current Information Needed

                $currentDataNote

                For a 10/10 answer, I should:
                - Give the direct answer first.
                - Include the relevant date or time period.
                - Separate confirmed facts from uncertainty.
                - Cite the exact source URLs used.
                - Keep the explanation short and easy to scan.
                $sourceSection
            """.trimIndent()

            text.containsAny("nobel prize") -> """
                ## Nobel Prize

                The Nobel Prize is a set of international awards for major contributions to humanity in fields such as physics, chemistry, medicine, literature, peace, and economic sciences.

                Laureates are selected through nominations and expert review by different Swedish and Norwegian institutions. The process is private, and nominations are usually sealed for decades.

                In simple terms: experts nominate candidates, committees evaluate the work, and the responsible institution votes on the winner.
            """.trimIndent()

            else -> null
        }
    }

    private fun extractOriginalUserRequest(text: String): String {
        val marker = "Original user request:"
        if (!text.contains(marker)) return text
        return text.substringAfter(marker)
            .substringBefore("Web search results already fetched by the app:")
            .trim()
            .ifBlank { text }
    }

    private fun extractSourceLines(text: String): List<String> {
        val marker = "Exact source URLs available from the search result:"
        if (!text.contains(marker)) return emptyList()
        return text.substringAfter(marker)
            .substringBefore("Answer the original request")
            .lineSequence()
            .map { it.trim() }
            .filter { it.matches(Regex("\\d+\\.\\s+.+\\s+-\\s+https?://.+")) }
            .toList()
    }

    private fun String.containsAny(vararg needles: String): Boolean {
        return needles.any { contains(it, ignoreCase = true) }
    }

    /**
     * Generate a mock response with image input (ignores the image).
     */
    fun generateResponseWithImage(
        messages: List<Message>,
        imageBitmap: Bitmap,
        prompt: String,
        capabilityMode: String,
        enableThinking: Boolean
    ): Flow<InferenceToken> = flow {
        val responseText = "I can see you've shared an image. In mock mode, I'll describe what I would analyze: the image content, colors, objects, text, and overall composition. This would work with a real model loaded."

        if (enableThinking) {
            emit(InferenceToken.ThinkingToken("The user shared an image. I need to analyze it."))
            delay(200)
        }

        for (chunk in responseText.chunked(2)) {
            emit(InferenceToken.TextToken(chunk))
            delay(60)
        }
        emit(InferenceToken.EndOfStream)
    }

    /**
     * Generate a mock response with audio input (ignores the audio).
     */
    fun generateResponseWithAudio(
        messages: List<Message>,
        audioBytes: ByteArray,
        prompt: String,
        capabilityMode: String,
        enableThinking: Boolean
    ): Flow<InferenceToken> = flow {
        val responseText = "I've processed your audio input. In mock mode, I'm treating this as a voice message and generating a text response."

        if (enableThinking) {
            emit(InferenceToken.ThinkingToken("The user sent audio. I should transcribe and respond."))
            delay(200)
        }

        for (chunk in responseText.chunked(2)) {
            emit(InferenceToken.TextToken(chunk))
            delay(60)
        }
        emit(InferenceToken.EndOfStream)
    }

    fun isMockEngine(): Boolean = true
}
