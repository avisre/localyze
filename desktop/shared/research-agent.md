# Deep Research Agent — Spec

Same conceptual loop on all three platforms. Each platform implements it natively under `Research/` (Windows `ReActAgent.cs`, macOS `ReActAgent.swift`, Linux `ReActAgent.cpp`). This doc is the canonical reference for the wire protocol.

## Loop

```
input: user_prompt, web_enabled (bool)
state: messages = [system, user_prompt]

loop (max_steps = 12):
    response = backend.generate(messages, stop=["</tool>", "</final>"])
    if response.contains("<final>"):
        return parse_final(response)
    if response.contains("<tool"):
        call = parse_tool_call(response)
        if call.name not in allowed_tools(web_enabled):
            messages.append({role: "tool_result", content: error("tool not available")})
            continue
        result = dispatch(call.name, call.args)
        messages.append({role: "assistant",   content: response})
        messages.append({role: "tool_result", content: format_result(call.name, result)})
    else:
        return response   # plain answer, no tools called
```

## Tool-call wire format

The model emits tool calls as JSON inside `<tool>` tags:

```xml
<tool name="memory.search">
{"query": "what did the user say about authentication?"}
</tool>
```

After dispatch the runtime appends a `<tool_result>` block before resuming generation:

```xml
<tool_result name="memory.search">
{"results": [
  {"id":"mem-2026-04-12-001","snippet":"...","source":"memory"}
]}
</tool_result>
```

The model finalizes with:

```xml
<final>
Final answer text, with [citations](mem-2026-04-12-001) inline.
</final>
```

## Tools (canonical names + args)

Always available:

| Name | Args | Description |
|---|---|---|
| `memory.search` | `{ "query": str, "limit": int }` | Local conversation memory + saved notes (sqlite-backed) |
| `files.search`  | `{ "query": str, "limit": int }` | Attached files via local embeddings |
| `calc`          | `{ "expr": str }` | Arithmetic + named constants (`pi`, `e`) |
| `run`           | `{ "lang": "python"\|"javascript"\|"shell", "code": str }` | Local sandboxed code execution. Output also renders as a `<viz type="run">` artifact. |
| `system.info`   | `{}` | OS / CPU / RAM / GPU info |

Web-gated:

| Name | Args | Description |
|---|---|---|
| `web.search` | `{ "query": str, "n": int }` | SearXNG HTTP query. Available only when `SettingsStore.webSearchEnabled = true`. |

## System prompt (per platform — identical text)

```
You are Localyze, a private on-device assistant.

You can call tools by emitting:
  <tool name="TOOL_NAME">
  {"arg": "value"}
  </tool>

Available tools:
  memory.search(query, limit=5)
  files.search(query, limit=5)
  calc(expr)
  run(lang, code)
  system.info()
{{#WEB}}  web.search(query, n=5){{/WEB}}

To answer the user, wrap your reply in:
  <final>
  ...answer...
  </final>

You may include <viz type="..."> blocks inside <final> to render charts,
tables, maps, code, or executable code. See viz-schema.md for types.

Rules:
- Always cite tool results in the final answer when you use them.
- Do NOT call web.search unless it is listed in the tool list above.
- Prefer 1-3 tool calls. Synthesize quickly.
```

## UI streaming

The UI shows each step (`plan` / `tool` / `tool_result` / `final`) as it arrives. Plain assistant text streams token-by-token; tool calls and results render as collapsible cards.

## Why ReAct, not a fancier planner

Simplest viable approach (see `feedback_simplest_code`). ReAct is well-understood, small to implement (~300 LOC in any language), and the trail is naturally inspectable. We can add planning (decompose-then-execute), tree search, or reflexion later if the simple loop falls short.
