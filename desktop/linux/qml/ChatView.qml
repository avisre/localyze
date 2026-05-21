import QtQuick
import QtQuick.Controls
import QtQuick.Dialogs
import QtQuick.Layouts
import LocalyzeUI

// Claude-inspired chat surface. Centered content column with a max width,
// soft warm palette, message bubbles (user) + plain assistant text,
// rounded input row with an inline web toggle and a circular send button.
Page {
    id: root

    property string backendLabel: ""
    property string backendReason: ""

    // ----- design tokens (resolved by the Theme singleton; light by default,
    //       dark if the user toggles the Settings switch or the system palette
    //       reports a dark theme) -----
    readonly property color bgColor:         Theme.bgColor
    readonly property color surface:         Theme.surface
    readonly property color surfaceSubtle:   Theme.surfaceSubtle
    readonly property color border:          Theme.border
    readonly property color borderStrong:    Theme.borderStrong
    readonly property color textPrimary:     Theme.textPrimary
    readonly property color textSecondary:   Theme.textSecondary
    readonly property color textMuted:       Theme.textMuted
    readonly property color accent:          Theme.accent
    readonly property color accentHover:     Theme.accentHover
    readonly property color stopColor:       Theme.stopColor
    readonly property color codeBg:          Theme.codeBg
    readonly property color traceColor:      Theme.traceColor
    readonly property color passColor:       Theme.passColor
    readonly property color partialColor:    Theme.partialColor
    readonly property color failColor:       Theme.failColor
    readonly property int   contentMaxWidth: 760

    background: Rectangle { color: root.bgColor }

    // -------------------- agent <-> UI wiring --------------------
    property bool sending: false
    property bool hasModel: !!backendLabel && backendReason.indexOf("model not at") === -1
    property string lastUserPrompt: ""
    property bool spectatorOpen: false
    // === AUTO-RETRY ADDITION START ===
    // Counts how many times we've auto-regenerated for the CURRENT user turn.
    // Capped at 1 so an incoherent model can't trap us in an infinite loop.
    // Reset to 0 on every new user prompt (_send) and whenever a coherent
    // final answer arrives.
    property int _autoRetryCount: 0
    // === AUTO-RETRY ADDITION END ===
    // Index of the assistant-stream row currently receiving tokens. -1 between
    // turns or when a ReAct trace row has just been appended (so the NEXT token
    // appends a fresh stream row instead of merging into stale content).
    property int streamingIdx: -1
    // === ROUTE FLAG ADDITION START ===
    // Which subsystem owns the in-flight turn:
    //   "direct" → backend.generate() — only the `target: backend` Connections
    //              block should mutate `messages` for this turn.
    //   "agent"  → researchAgent.run() — only the `target: researchAgent` block
    //              should fire. ReActAgent re-emits the underlying backend's
    //              tokenStream as its own, so without this gate BOTH handlers
    //              would append every token, doubling output ("calccalc — — I I…").
    //   ""       → no turn in flight (idle).
    property string _currentRoute: ""
    // === ROUTE FLAG ADDITION END ===

    // === COMPOSER-PILLS ADDITION START ===
    // Mistral/Grok/Perplexity-style horizontal pill strip in the composer.
    // chatSettings holds toggles whose state is local to this Page (not
    // persisted via the Settings backend). Thinking flag injects a
    // step-by-step prefix into the next user turn so the existing
    // ThoughtCard renders the model's <thought>...</thought> reasoning.
    QtObject {
        id: chatSettings
        property bool thinkingEnabled: false
    }
    // === COMPOSER-PILLS ADDITION END ===

    // === ARTIFACT PANEL ADDITIONS START ===
    // Claude-style artifact workspace. The right-edge slide-in panel hosts
    // anything worth a dedicated surface: long code blocks, big markdown
    // tables, charts, long structured docs. Detection runs after each
    // assistant turn finishes (onStep("final") + onFinished); see the
    // _scanLatestForArtifacts() helper below.
    property bool artifactPanelOpen: false
    property int  artifactCurrentIndex: 0
    ListModel { id: artifactItems }

    function _findLatestAssistantText() {
        for (let i = messages.count - 1; i >= 0; --i) {
            const m = messages.get(i)
            if (m.role === "assistant" || m.role === "assistant-stream") {
                return m.content || ""
            }
        }
        return ""
    }
    function _shortTitle(s) {
        if (!s) return ""
        const trimmed = s.replace(/\s+/g, " ").trim()
        if (trimmed.length <= 48) return trimmed
        return trimmed.substring(0, 45) + "…"
    }
    function _scanLatestForArtifacts() {
        const text = _findLatestAssistantText()
        if (!text || text.length === 0) return
        // --- Code blocks ≥ 20 lines ---
        const fenceRe = /```([A-Za-z0-9_+\-]*)\s*\n([\s\S]*?)```/g
        let m
        while ((m = fenceRe.exec(text)) !== null) {
            const lang = (m[1] || "").trim()
            const body = m[2] || ""
            const lineCount = body.split("\n").length
            if (lineCount >= 20) {
                artifactItems.append({
                    type: "code",
                    title: (lang || "code") + " · " + lineCount + " lines",
                    language: lang,
                    content: body
                })
            }
        }
        // --- Markdown tables ≥ 6 data rows ---
        const lines = text.split("\n")
        let i = 0
        while (i < lines.length) {
            if (lines[i].indexOf("|") >= 0
                && i + 1 < lines.length
                && /^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$/.test(lines[i + 1])) {
                // collect contiguous table block
                const start = i
                let end = i + 2
                while (end < lines.length
                       && lines[end].indexOf("|") >= 0
                       && lines[end].trim().length > 0) {
                    end++
                }
                const dataRows = end - start - 2   // minus header + separator
                if (dataRows >= 6) {
                    const block = lines.slice(start, end).join("\n")
                    artifactItems.append({
                        type: "table",
                        title: "Table · " + dataRows + " rows",
                        language: "",
                        content: block
                    })
                }
                i = end
                continue
            }
            i++
        }
        // --- Inline chart hints ---
        const hasChartHint = /\b(as a bar chart|as a chart|plotted below|chart below|the chart shows|bar chart of|line chart of)\b/i.test(text)
        if (hasChartHint) {
            artifactItems.append({
                type: "chart",
                title: "Chart",
                language: "",
                content: text
            })
        }
        // --- Long structured docs (≥ 800 chars + multiple headings) ---
        if (text.length >= 800) {
            let headingCount = 0
            for (let k = 0; k < lines.length; ++k) {
                if (/^#{1,6}\s+\S/.test(lines[k])) headingCount++
            }
            if (headingCount >= 2) {
                // First heading makes a nice title.
                let docTitle = "Document"
                for (let k = 0; k < lines.length; ++k) {
                    const hm = lines[k].match(/^#{1,6}\s+(.+?)\s*#*\s*$/)
                    if (hm) { docTitle = _shortTitle(hm[1]); break }
                }
                artifactItems.append({
                    type: "doc",
                    title: docTitle,
                    language: "",
                    content: text
                })
            }
        }
        if (artifactItems.count > 0
            && artifactCurrentIndex >= artifactItems.count) {
            artifactCurrentIndex = artifactItems.count - 1
        }
    }
    // === ARTIFACT PANEL ADDITIONS END ===

    // Esc → stop generation. Global focus scope.
    focus: true
    Keys.onEscapePressed: if (root.sending) root._stop()
    Keys.onPressed: (e) => {
        // Ctrl+N — new chat
        if (e.key === Qt.Key_N && (e.modifiers & Qt.ControlModifier)) {
            root._newChat(); e.accepted = true
        }
        // Ctrl+, — open settings
        if (e.key === Qt.Key_Comma && (e.modifiers & Qt.ControlModifier)) {
            settingsDrawer.open(); e.accepted = true
        }
        // Ctrl+T — toggle auto-test feeder
        if (e.key === Qt.Key_T && (e.modifiers & Qt.ControlModifier)) {
            if (promptFeeder.active) promptFeeder.stop(); else promptFeeder.start();
            e.accepted = true
        }
        // Ctrl+H — toggle the conversation history drawer
        if (e.key === Qt.Key_H && (e.modifiers & Qt.ControlModifier)) {
            if (conversationDrawer.opened) conversationDrawer.close()
            else conversationDrawer.open()
            e.accepted = true
        }
        // === ARTIFACT PANEL ADDITIONS START ===
        // Ctrl+E — toggle the artifact side panel (Claude-style workspace)
        if (e.key === Qt.Key_E && (e.modifiers & Qt.ControlModifier)) {
            if (root.artifactItems.count > 0) root.artifactPanelOpen = !root.artifactPanelOpen
            e.accepted = true
        }
        // === ARTIFACT PANEL ADDITIONS END ===
    }

    // ---- Conversation history: load a saved chat back into the message list.
    // The store emits conversationLoaded(payload) after switchTo(id); we clear
    // the in-memory model and re-append each {role, content} entry.
    Connections {
        target: conversationStore
        function onConversationLoaded(msgs) {
            if (root.sending) root._stop()
            messages.clear()
            root.streamingIdx = -1
            // === ARTIFACT PANEL ADDITIONS START ===
            artifactItems.clear()
            root.artifactCurrentIndex = 0
            root.artifactPanelOpen = false
            // === ARTIFACT PANEL ADDITIONS END ===
            for (let i = 0; i < msgs.length; ++i) {
                const m = msgs[i]
                // === VARIANTS ADDITION START ===
                messages.append({
                    role: m.role,
                    kind: "",
                    content: m.content,
                    variants: [],
                    variantIndex: 0
                })
                // === VARIANTS ADDITION END ===
                if (m.role === "user") root.lastUserPrompt = m.content
            }
        }
    }

    // ---- Auto-test feeder: every line written to /tmp/qrepo/inject.txt is
    //      simulated as a user turn. The feeder waits for finished() before
    //      emitting the next prompt, so the user sees one streaming answer
    //      at a time, exactly like a real conversation.
    Connections {
        target: promptFeeder
        function onPromptReady(prompt) {
            if (root.sending) return    // safety; finished() will requeue
            // Reuse the real user-send path so the routing (direct vs agent),
            // _currentRoute flag, streaming placeholder, and assistant-side
            // connections all fire exactly the same way as for a real keypress.
            // Without this, the agent's step("final") events arrive but the
            // Connections block is gated by _currentRoute === "agent" and
            // never fires, so logAnswer() is never called and the feeder
            // hangs forever waiting for markDone.
            input.text = prompt
            _send()
        }
    }

    Connections {
        target: researchAgent
        // Only the agent's signals drive the UI for agent-routed turns. Direct
        // backend turns have _currentRoute === "direct"; ignoring agent signals
        // there prevents the doubled-token bug we saw on the live test
        // ("calccalc — — I I need need…").
        enabled: root._currentRoute === "agent"
        function onStep(kind, text) {
            if (kind === "final") {
                // The streamed row holds the model's RAW output including
                // <final> / <tool> tags. Replace its content with the cleaned
                // text so we render the answer once, not twice.
                const cleaned = _sanitizeAnswer(text)
                if (root.streamingIdx >= 0) {
                    // === VARIANTS ADDITION START ===
                    // If this row was created by a Retry, it already has a
                    // non-empty `variants` array (the previous answer pushed
                    // in by _regenerate). Append the freshly-generated text
                    // as the latest variant and select it.
                    const _row = messages.get(root.streamingIdx)
                    const _existing = (_row && _row.variants) ? _row.variants : []
                    if (_existing.length > 0) {
                        const _nextVariants = _existing.slice()
                        _nextVariants.push(cleaned)
                        messages.setProperty(root.streamingIdx, "variants", _nextVariants)
                        messages.setProperty(root.streamingIdx, "variantIndex", _nextVariants.length - 1)
                    }
                    // === VARIANTS ADDITION END ===
                    messages.setProperty(root.streamingIdx, "content", cleaned)
                    messages.setProperty(root.streamingIdx, "role", "assistant")
                    root.streamingIdx = -1
                } else {
                    _appendAssistant(cleaned)
                }
                conversationStore.appendMessage("assistant", cleaned)
                promptFeeder.logAnswer(cleaned)
                // === AUTO-RETRY ADDITION START ===
                // Detect client-side that the answer is internally inconsistent
                // (the model wrote "I made a mistake", "correction:", or
                // "should be X, not Y") and silently regenerate ONCE. The cap
                // prevents an infinite loop if the model keeps self-correcting.
                const incoherent = /(\bi made a mistake\b|\bcorrection[:.]|\bshould be\s+[\d.]+\s*,?\s*not\s+[\d.]+)/i.test(text)
                if (incoherent && root._autoRetryCount < 1) {
                    root._autoRetryCount++
                    toast.show("Detected an inconsistency — regenerating once…")
                    Qt.callLater(() => { if (root.lastUserPrompt) root._regenerate() })
                    return
                }
                if (!incoherent) root._autoRetryCount = 0   // reset counter for next user turn
                // === AUTO-RETRY ADDITION END ===
                // === ARTIFACT PANEL ADDITIONS START ===
                root._scanLatestForArtifacts()
                // === ARTIFACT PANEL ADDITIONS END ===
                return
            }
            // Plan/act/observe traces: finalize any in-flight stream, then
            // append a trace row. streamingIdx resets so the next token
            // (next step) spawns a fresh assistant-stream row.
            if (root.streamingIdx >= 0) {
                messages.setProperty(root.streamingIdx, "role", "assistant")
                root.streamingIdx = -1
            }
            _appendTrace(kind, text)
        }
        function onTokenStream(token) {
            if (root.streamingIdx < 0) {
                // === VARIANTS ADDITION START ===
                // Initialize the per-row variant fields on first append so
                // ListModel learns the roles. `variants` is left empty and
                // only gets populated when the user clicks Retry.
                messages.append({
                    role: "assistant-stream",
                    kind: "",
                    content: "",
                    variants: [],
                    variantIndex: 0
                })
                // === VARIANTS ADDITION END ===
                root.streamingIdx = messages.count - 1
            }
            messages.setProperty(root.streamingIdx, "content",
                messages.get(root.streamingIdx).content + token)
        }
        function onFinished() {
            if (root.streamingIdx >= 0) {
                messages.setProperty(root.streamingIdx, "role", "assistant")
                root.streamingIdx = -1
            }
            root.sending = false
            root._currentRoute = ""
            // === ARTIFACT PANEL ADDITIONS START ===
            root._scanLatestForArtifacts()
            // === ARTIFACT PANEL ADDITIONS END ===
        }
    }

    // === ROUTING ADDITION START ===
    // When _send() routes a simple prompt straight to backend.generate(), the
    // tokenStream/finished signals come from `backend` rather than the agent.
    // Wire them to the SAME in-place handlers so the streaming row, assistant
    // promotion, conversation persistence, and artifact scan all still fire.
    // backend.generate() never emits a `step("final", ...)` — onFinished alone
    // is enough to flip the streaming row to "assistant".
    Connections {
        target: backend
        // Mirror of the researchAgent block above: only handle backend signals
        // for direct-routed turns. ReActAgent re-emits this same backend's
        // tokenStream as its own; without this gate, both Connections fire
        // and every token gets appended twice.
        enabled: !!backend && root._currentRoute === "direct"
        function onTokenStream(token) {
            if (root.streamingIdx < 0) {
                messages.append({
                    role: "assistant-stream",
                    kind: "",
                    content: "",
                    variants: [],
                    variantIndex: 0
                })
                root.streamingIdx = messages.count - 1
            }
            messages.setProperty(root.streamingIdx, "content",
                messages.get(root.streamingIdx).content + token)
        }
        function onFinished() {
            // Promote the in-flight stream to its final assistant row and
            // persist it the same way the agent's final step would have.
            let finalText = ""
            if (root.streamingIdx >= 0) {
                const _raw = messages.get(root.streamingIdx).content || ""
                finalText = _sanitizeAnswer(_raw)
                messages.setProperty(root.streamingIdx, "content", finalText)
                messages.setProperty(root.streamingIdx, "role", "assistant")
                root.streamingIdx = -1
            }
            if (root.sending) {
                if (finalText.length > 0) {
                    conversationStore.appendMessage("assistant", finalText)
                    promptFeeder.logAnswer(finalText)
                }
                root.sending = false
                root._currentRoute = ""
                root._scanLatestForArtifacts()
            }
        }
    }
    // === ROUTING ADDITION END ===

    // Sanitize raw model output: strip stray tool-call syntax the model
    // sometimes leaks despite system-prompt rules. We:
    //   1. Unwrap ```tool_code / ```tool / ```tool_result fences and keep
    //      only the plain string args (text inside print("...") calls or
    //      the raw body if no print).
    //   2. Strip <tool_result>...</tool_result> and <tool ...>...</tool>
    //      XML-style blocks entirely (these are agent-internal, never user
    //      facing).
    //   3. Strip standalone <tool_name="..."> open tags that arrived
    //      without a closing tag.
    // Returns the cleaned text, trimmed.
    function _sanitizeAnswer(raw) {
        if (!raw || raw.length === 0) return raw
        let s = String(raw)
        // (1) Tool-style fenced blocks → keep contents (extract from print(...))
        s = s.replace(/```(?:tool_code|tool|tool_result|text)\s*\n([\s\S]*?)\n?```/g,
            function(_match, body) {
                // Extract strings from print("...") / print('...') calls
                const strs = []
                const re = /print\(\s*["'`]([\s\S]*?)["'`]\s*\)/g
                let m
                while ((m = re.exec(body)) !== null) strs.push(m[1])
                if (strs.length > 0) return strs.join("\n")
                // No print() — just keep the body as-is
                return body
            })
        // (2) XML-style tool blocks → drop entirely
        s = s.replace(/<tool_result[^>]*>[\s\S]*?<\/tool_result>/g, "")
        s = s.replace(/<tool[^>]*>[\s\S]*?<\/tool>/g, "")
        // (3) Standalone tool open tags (no closer) → drop
        s = s.replace(/<tool_name\s*=\s*"[^"]*">/g, "")
        s = s.replace(/<tool[^>]*\/?>/g, "")
        // Collapse extra blank lines and trim
        s = s.replace(/\n{3,}/g, "\n\n").trim()
        return s
    }

    function _appendAssistant(text) {
        // === VARIANTS ADDITION START ===
        messages.append({
            role: "assistant",
            kind: "",
            content: text,
            variants: [],
            variantIndex: 0
        })
        // === VARIANTS ADDITION END ===
    }
    function _appendTrace(kind, text) {
        messages.append({role: "trace", kind: kind, content: text})
    }
    // === ROUTING ADDITION START ===
    // Single-shot routing heuristic. Returns true when the prompt likely needs
    // the ReAct tool loop (math, search, files, "current/today" queries).
    // Otherwise the prompt is "simple" and goes straight to backend.generate(),
    // bypassing the multi-step planner.
    function _looksLikeToolPrompt(s) {
        if (!s) return false
        const text = String(s)
        const lower = text.toLowerCase()
        const hasDigit = /\d/.test(text)
        if (hasDigit) {
            const mathTriggers = [
                "compound interest", "calculate", "compute", "% of ",
                "factorial", "square root", "sqrt("
            ]
            for (let i = 0; i < mathTriggers.length; ++i) {
                if (lower.indexOf(mathTriggers[i]) >= 0) return true
            }
            if (/convert.*to/i.test(text)) return true
            if (/ × /.test(text)) return true   // ×
            if (/ \* /.test(text)) return true
            if (/ ÷ /.test(text)) return true   // ÷
            if (/ \/ /.test(text)) return true
        }
        const keywordTriggers = [
            "look up", "search", "find files", "my files",
            "my memory", "web search", "search the web"
        ]
        for (let j = 0; j < keywordTriggers.length; ++j) {
            if (lower.indexOf(keywordTriggers[j]) >= 0) return true
        }
        if (lower.indexOf("what is the current") === 0) return true
        if (lower.indexOf("latest news") === 0) return true
        if (lower.indexOf("today's") === 0) return true
        return false
    }
    // === ROUTING ADDITION END ===
    function _send() {
        const t = input.text.trim()
        if (t === "" || root.sending || !root.hasModel) return
        // === AUTO-RETRY ADDITION START ===
        // Fresh user turn: clear any stale auto-retry counter from the prior
        // turn so the new answer gets its own single retry budget.
        root._autoRetryCount = 0
        // === AUTO-RETRY ADDITION END ===
        messages.append({role: "user", kind: "", content: t})
        conversationStore.appendMessage("user", t)
        root.lastUserPrompt = t
        // === STREAM-POLISH ADDITION START ===
        // Append the assistant-stream placeholder in the SAME frame as the
        // user turn. The row is empty (content === "") but its `streaming`
        // flag is on, so the existing "thinking…" pulse and the soft caret
        // light up immediately rather than waiting for the first model token
        // (which can take 200-800ms). onTokenStream will reuse this row via
        // streamingIdx instead of allocating a fresh one.
        messages.append({
            role: "assistant-stream",
            kind: "",
            content: "",
            variants: [],
            variantIndex: 0
        })
        root.streamingIdx = messages.count - 1
        // === STREAM-POLISH ADDITION END ===
        // === COMPOSER-PILLS ADDITION START ===
        // When the Think pill is on, append the step-by-step instruction so
        // the model emits a <thought> block that ThoughtCard collapses for us.
        const prompt = chatSettings.thinkingEnabled
            ? t + "\n\nThink step-by-step before answering. Wrap reasoning in <thought>...</thought>."
            : t
        // === ROUTING ADDITION START ===
        // Simple prompts (chitchat, single-shot Q&A, creative writing) skip
        // the ReAct loop and stream straight from the backend. Anything that
        // looks like it might need tools — math, search, file/memory lookup,
        // "current/today's" queries — still routes through researchAgent.
        const _needsTools = _looksLikeToolPrompt(t)
        // Defer the actual backend call by one event-loop tick. Calling
        // backend.generate() synchronously from the click/Return handler has
        // bitten us before: the very first token can arrive back through
        // onTokenStream while QML is still mid-binding-update on `sending`,
        // and that re-entry has caused crashes via DecodingGuard. Qt.callLater
        // guarantees the QML state (sending=true, streamingIdx set, placeholder
        // row appended) is fully committed before any backend signal can fire.
        // We also re-check `backend` and `hasModel` inside the callback in case
        // the context property became null between the click and the tick.
        // Research mode needs more tokens — its ## Sources section was getting
        // truncated at the 1024-token cap. Bump to 2048 when Research is active.
        const _isResearch = (modeStore.currentMode === "research")
        const _maxToks = _isResearch ? 2048 : 1024
        if (_needsTools || !backend) {
            root._currentRoute = "agent"
            Qt.callLater(function() {
                if (root.sending && root.hasModel) researchAgent.run(prompt)
            })
        } else {
            root._currentRoute = "direct"
            Qt.callLater(function() {
                if (!root.sending || !root.hasModel) return
                if (!backend) { root._currentRoute = "agent"; researchAgent.run(prompt); return }
                backend.generate(prompt, _maxToks)
            })
        }
        // === ROUTING ADDITION END ===
        // === COMPOSER-PILLS ADDITION END ===
        input.text = ""
        root.sending = true
    }
    function _stop() {
        researchAgent.stop()
        // If a stream is in progress, finalize it as-is.
        if (messages.count > 0 && messages.get(messages.count - 1).role === "assistant-stream") {
            messages.setProperty(messages.count - 1, "role", "assistant")
        }
        root.sending = false
    }
    function _newChat() {
        if (root.sending) root._stop()
        conversationStore.newConversation()
        messages.clear()
        root.lastUserPrompt = ""
        input.text = ""
        // === ARTIFACT PANEL ADDITIONS START ===
        artifactItems.clear()
        root.artifactCurrentIndex = 0
        root.artifactPanelOpen = false
        // === ARTIFACT PANEL ADDITIONS END ===
        input.forceActiveFocus()
    }
    function _regenerate() {
        if (!root.lastUserPrompt || root.sending) return
        // === VARIANTS ADDITION START ===
        // Character.AI swipe behavior: keep the existing answer as a variant
        // and stream the new generation into a sibling slot on the SAME row.
        // 1. Find the most-recent assistant row (skipping any trailing trace
        //    rows that may sit between the assistant message and the bottom).
        // 2. Push its current content onto `variants` (creating the array if
        //    it doesn't exist yet) and bump `variantIndex` past the end so
        //    onStep("final") knows this is a Retry-driven regeneration.
        // 3. Drop only the trace rows that follow the assistant message; the
        //    assistant row itself is reused as the streaming target.
        let assistantIdx = -1
        for (let i = messages.count - 1; i >= 0; --i) {
            const r = messages.get(i).role
            if (r === "user") break
            if (r === "assistant" || r === "assistant-stream") {
                assistantIdx = i
                break
            }
        }
        if (assistantIdx >= 0) {
            const row = messages.get(assistantIdx)
            const currentContent = row.content || ""
            const existing = row.variants ? row.variants.slice() : []
            existing.push(currentContent)
            messages.setProperty(assistantIdx, "variants", existing)
            messages.setProperty(assistantIdx, "variantIndex", existing.length)
            // Reuse this row as the streaming target. Clear its content and
            // mark it as a stream so the existing token-stream path appends
            // into the same model row instead of creating a sibling.
            messages.setProperty(assistantIdx, "content", "")
            messages.setProperty(assistantIdx, "role", "assistant-stream")
            root.streamingIdx = assistantIdx
            // Remove any trailing rows AFTER the assistant slot (trace rows
            // from the previous turn — they belong to the old generation).
            for (let j = messages.count - 1; j > assistantIdx; --j) {
                messages.remove(j)
            }
        } else {
            // Fallback (no prior assistant row): behave like the old impl.
            for (let i = messages.count - 1; i >= 0; --i) {
                const r = messages.get(i).role
                if (r === "user") break
                messages.remove(i)
            }
        }
        // === VARIANTS ADDITION END ===
        // === STREAM-POLISH ADDITION START ===
        // Surface a tiny "Regenerating…" toast so the user knows the click
        // landed even before the first new token arrives (otherwise there's
        // a 200-800ms silent gap while the model spins up).
        toast.show("Regenerating…")
        // === STREAM-POLISH ADDITION END ===
        researchAgent.run(root.lastUserPrompt)
        root.sending = true
    }
    function _copyToClipboard(text) {
        clipboardHelper.text = text
        clipboardHelper.selectAll()
        clipboardHelper.copy()
        toast.show("Copied")
    }

    // === SLASH-COMMANDS ADDITION START ===
    // Show/hide the slash-command picker based on the current input buffer.
    // The picker opens iff the buffer matches `^/\w*$` — a leading slash
    // followed by zero-or-more word chars. Anything else (a trailing space,
    // multiple lines, leading text) closes it. The filter string (everything
    // after the slash) is passed straight to the picker which narrows its
    // visible commands by prefix match.
    function _maybeShowSlashMenu() {
        const t = input.text
        const m = /^\/(\w*)$/.exec(t)
        if (m) {
            slashMenu.filter = m[1]
            if (!slashMenu.visible) slashMenu.open()
        } else {
            if (slashMenu.visible) slashMenu.close()
        }
    }
    // === SLASH-COMMANDS ADDITION END ===

    // Off-screen helper for clipboard access (TextEdit has copy() method).
    TextEdit { id: clipboardHelper; visible: false }

    // -------------------- top header strip --------------------
    Rectangle {
        id: header
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        height: 56
        color: root.surface
        border.color: root.border
        border.width: 0
        Rectangle {
            anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
            height: 1; color: root.border
        }

        RowLayout {
            anchors.fill: parent
            anchors.leftMargin: 24
            anchors.rightMargin: 24
            spacing: 12

            Rectangle {
                width: 28; height: 28; radius: 7
                color: root.accent
                Label {
                    anchors.centerIn: parent
                    text: "L"; color: "white"
                    font.pixelSize: 16; font.bold: true
                }
            }
            Label {
                text: "Localyze"
                color: root.textPrimary
                font.pixelSize: 15; font.weight: Font.Medium
            }
            Item { Layout.fillWidth: true }
            Label {
                text: root.backendLabel
                color: root.textSecondary
                font.pixelSize: 12
                elide: Label.ElideRight
                Layout.maximumWidth: 380
            }
            // History (Ctrl+H) — open the conversation drawer
            ToolButton {
                text: "📜 History"
                hoverEnabled: true
                ToolTip.text: "Conversation history (Ctrl+H)"
                ToolTip.visible: hovered
                ToolTip.delay: 400
                onClicked: conversationDrawer.open()
                font.pixelSize: 13
                Layout.preferredHeight: 32
            }
            // New chat (Ctrl+N)
            ToolButton {
                text: "+ New"
                hoverEnabled: true
                ToolTip.text: "New chat (Ctrl+N)"
                ToolTip.visible: hovered
                ToolTip.delay: 400
                onClicked: root._newChat()
                font.pixelSize: 13
                Layout.preferredHeight: 32
            }
            // === POLISH ROUND ADDITION START ===
            // Copy entire conversation — plain-text dump of every user /
            // assistant turn to the clipboard via the existing clipboardHelper.
            // Lives next to "+ New" so it's discoverable but unobtrusive.
            ToolButton {
                text: "⎘"
                hoverEnabled: true
                enabled: messages.count > 0
                ToolTip.text: "Copy conversation"
                ToolTip.visible: hovered
                ToolTip.delay: 400
                font.pixelSize: 15
                Layout.preferredHeight: 32
                onClicked: {
                    let out = ""
                    for (let i = 0; i < messages.count; ++i) {
                        const m = messages.get(i)
                        const r = m.role || ""
                        // Skip trace / system rows — only user + assistant turns.
                        if (r !== "user" && r !== "assistant"
                            && r !== "assistant-stream") continue
                        const speaker = (r === "user") ? "User" : "Localyze"
                        const body = (m.content || "").toString()
                        if (body.trim() === "") continue
                        out += speaker + ": " + body + "\n\n"
                    }
                    if (out.length === 0) {
                        toast.show("Nothing to copy")
                        return
                    }
                    clipboardHelper.text = out
                    clipboardHelper.selectAll()
                    clipboardHelper.copy()
                    toast.show("Copied conversation")
                }
            }
            // === POLISH ROUND ADDITION END ===
            // === ARTIFACT PANEL ADDITIONS START ===
            // Artifact panel toggle (Ctrl+E) — Claude-style workspace
            ToolButton {
                text: artifactItems.count > 0
                      ? "📐 Artifacts (" + artifactItems.count + ")"
                      : "📐"
                enabled: artifactItems.count > 0
                onClicked: root.artifactPanelOpen = !root.artifactPanelOpen
                hoverEnabled: true
                ToolTip.text: "Artifact panel (Ctrl+E)"
                ToolTip.visible: hovered
                ToolTip.delay: 400
                font.pixelSize: 13
                Layout.preferredHeight: 32
            }
            // === ARTIFACT PANEL ADDITIONS END ===
            // Auto-Test (Ctrl+T) — drives the chat from /tmp/qrepo/inject.txt
            ToolButton {
                id: autoTestBtn
                text: promptFeeder.active ? ("● Auto-Test " + promptFeeder.sent) : "▶ Auto-Test"
                hoverEnabled: true
                ToolTip.text: promptFeeder.active
                    ? "Auto-test running — " + promptFeeder.pending + " queued (Ctrl+T to stop)"
                    : "Start auto-test feeder (Ctrl+T) — prompts from /tmp/qrepo/inject.txt run as user turns"
                ToolTip.visible: hovered
                ToolTip.delay: 400
                onClicked: promptFeeder.active ? promptFeeder.stop() : promptFeeder.start()
                font.pixelSize: 12
                Layout.preferredHeight: 32
                Rectangle {
                    visible: promptFeeder.active
                    anchors.fill: parent
                    color: "transparent"
                    border.color: root.accent
                    border.width: 1
                    radius: 4
                }
            }
            // Settings (Ctrl+,)
            ToolButton {
                text: "⚙"
                hoverEnabled: true
                ToolTip.text: "Settings (Ctrl+,)"
                ToolTip.visible: hovered
                ToolTip.delay: 400
                onClicked: settingsDrawer.open()
                font.pixelSize: 16
                Layout.preferredHeight: 32
            }
        }
    }

    // -------------------- mode picker (above composer, below conversation) --
    Item {
        id: modePickerWrap
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: composerWrap.top
        height: modePicker.implicitHeight + 12
        RowLayout {
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.top: parent.top
            anchors.topMargin: 6
            spacing: 10
            ModePicker {
                id: modePicker
            }
            // === STYLE-PICKER ADDITION START ===
            // Per-conversation response style. Sits to the right of the
            // ModePicker (above the composer). Selection appends a one-line
            // STYLE: addendum to the system prompt at LlamaCppBackend.generate().
            StylePicker {
                id: stylePicker
            }
            // === STYLE-PICKER ADDITION END ===
        }
    }

    // -------------------- centered scrollable conversation --------------------
    ScrollView {
        id: scroller
        anchors.top: header.bottom
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: modePickerWrap.top
        clip: true
        contentWidth: availableWidth

        ColumnLayout {
            width: scroller.availableWidth
            spacing: 0

            // ============ empty-state hero ============
            ColumnLayout {
                visible: messages.count === 0
                Layout.alignment: Qt.AlignHCenter
                Layout.topMargin: Math.max(80, (scroller.height - 260) / 2)
                Layout.preferredWidth: Math.min(root.contentMaxWidth, parent.width - 48)
                spacing: 18

                Label {
                    Layout.alignment: Qt.AlignHCenter
                    text: root.hasModel ? "Good to see you." : "Almost ready."
                    color: root.textPrimary
                    font.pixelSize: 30
                    font.family: "Charter, Georgia, serif"
                }
                Label {
                    Layout.alignment: Qt.AlignHCenter
                    text: root.hasModel
                        ? "Localyze runs Gemma 4 E4B on your device. Ask anything."
                        : "We couldn't find the model file. Open settings to point Localyze at your GGUF."
                    color: root.textSecondary
                    font.pixelSize: 14
                    wrapMode: Label.WordWrap
                    horizontalAlignment: Text.AlignHCenter
                    Layout.preferredWidth: 540
                }
                Label {
                    Layout.alignment: Qt.AlignHCenter
                    visible: !!root.backendReason
                    text: root.backendReason
                    color: root.textMuted
                    font.pixelSize: 12
                    wrapMode: Label.WordWrap
                    horizontalAlignment: Text.AlignHCenter
                    Layout.preferredWidth: 540
                }
                // === EMPTY-STATE-CARDS ADDITION START ===
                // ChatGPT-style 2x2 starter cards. Each card has an icon,
                // a title, and a one-line description. Clicking fills the
                // composer with a tailored seed prompt and focuses input.
                GridLayout {
                    id: starterGrid
                    visible: root.hasModel
                    Layout.alignment: Qt.AlignHCenter
                    Layout.topMargin: 24
                    Layout.preferredWidth: Math.min(600, parent.width - 48)
                    columns: 2
                    rowSpacing: 10
                    columnSpacing: 10

                    Repeater {
                        model: [
                            {
                                icon: "📝",
                                title: "Summarize a document",
                                sub:   "Paste text or a transcript and ask for the key points",
                                seed:  "Summarize this for me:\n\n",
                                cursorAtEnd: true,
                                selectToken: ""
                            },
                            {
                                icon: "💻",
                                title: "Debug some code",
                                sub:   "Share a snippet — Localyze finds the bug and suggests a fix",
                                seed:  "Debug this code and explain the fix:\n\n",
                                cursorAtEnd: true,
                                selectToken: ""
                            },
                            {
                                icon: "✉️",
                                title: "Draft a quick email",
                                sub:   "Pick a tone, send the polished version",
                                seed:  "Draft a quick email — tone: friendly. About: ",
                                cursorAtEnd: true,
                                selectToken: ""
                            },
                            {
                                icon: "🧠",
                                title: "Explain like I'm 5",
                                sub:   "Turn jargon into plain English",
                                seed:  "Explain X like I'm 5",
                                cursorAtEnd: false,
                                selectToken: "X"
                            }
                        ]
                        delegate: Rectangle {
                            Layout.fillWidth: true
                            Layout.preferredHeight: 88
                            radius: 12
                            color: cardArea.containsMouse ? root.surfaceSubtle : root.surface
                            border.color: cardArea.containsMouse ? root.borderStrong : root.border
                            border.width: 1
                            Behavior on color       { ColorAnimation { duration: 140 } }
                            Behavior on border.color { ColorAnimation { duration: 140 } }

                            Label {
                                id: cardIcon
                                text: modelData.icon
                                font.pixelSize: 22
                                anchors.left: parent.left
                                anchors.top: parent.top
                                anchors.leftMargin: 14
                                anchors.topMargin: 14
                            }
                            ColumnLayout {
                                anchors.left: cardIcon.right
                                anchors.right: parent.right
                                anchors.top: parent.top
                                anchors.bottom: parent.bottom
                                anchors.leftMargin: 10
                                anchors.rightMargin: 14
                                anchors.topMargin: 14
                                anchors.bottomMargin: 14
                                spacing: 4
                                Label {
                                    text: modelData.title
                                    color: root.textPrimary
                                    font.pixelSize: 14
                                    font.weight: Font.Medium
                                    elide: Label.ElideRight
                                    Layout.fillWidth: true
                                }
                                Label {
                                    text: modelData.sub
                                    color: root.textSecondary
                                    font.pixelSize: 12
                                    wrapMode: Label.WordWrap
                                    maximumLineCount: 2
                                    elide: Label.ElideRight
                                    Layout.fillWidth: true
                                }
                            }
                            MouseArea {
                                id: cardArea
                                anchors.fill: parent
                                hoverEnabled: true
                                cursorShape: Qt.PointingHandCursor
                                onClicked: {
                                    input.text = modelData.seed
                                    input.forceActiveFocus()
                                    if (modelData.cursorAtEnd) {
                                        input.cursorPosition = input.text.length
                                    } else if (modelData.selectToken && modelData.selectToken.length > 0) {
                                        const idx = input.text.indexOf(modelData.selectToken)
                                        if (idx >= 0) {
                                            input.select(idx, idx + modelData.selectToken.length)
                                        } else {
                                            input.cursorPosition = input.text.length
                                        }
                                    } else {
                                        input.cursorPosition = input.text.length
                                    }
                                }
                            }
                        }
                    }
                }
                // === EMPTY-STATE-CARDS ADDITION END ===
            }

            // ============ message list ============
            Repeater {
                model: ListModel { id: messages }
                delegate: ColumnLayout {
                    Layout.alignment: Qt.AlignHCenter
                    Layout.preferredWidth: Math.min(root.contentMaxWidth, scroller.availableWidth - 48)
                    Layout.topMargin: index === 0 ? 32 : 18

                    // ----- user bubble (right aligned) -----
                    Rectangle {
                        visible: role === "user"
                        Layout.alignment: Qt.AlignRight
                        Layout.maximumWidth: parent.width * 0.85
                        color: root.surfaceSubtle
                        radius: 14
                        border.color: root.border; border.width: 1
                        Layout.preferredWidth: userText.implicitWidth + 32
                        Layout.preferredHeight: userText.implicitHeight + 22
                        Label {
                            id: userText
                            anchors.left: parent.left; anchors.right: parent.right
                            anchors.top: parent.top
                            anchors.leftMargin: 16; anchors.rightMargin: 16; anchors.topMargin: 11
                            text: content
                            wrapMode: Label.Wrap
                            color: root.textPrimary
                            font.pixelSize: 14
                            font.family: "Roboto, Inter, system-ui, sans-serif"
                        }
                    }

                    // ----- assistant (no bubble, markdown rendering) -----
                    ColumnLayout {
                        id: assistantBlock
                        visible: role === "assistant" || role === "assistant-stream"
                        Layout.alignment: Qt.AlignLeft
                        Layout.fillWidth: true
                        spacing: 6
                        property bool hovered: assistantHover.hovered
                        property bool streaming: role === "assistant-stream"

                        RowLayout {
                            spacing: 8
                            Rectangle {
                                width: 22; height: 22; radius: 5
                                color: root.accent
                                Label {
                                    anchors.centerIn: parent
                                    text: "L"; color: "white"
                                    font.pixelSize: 12; font.bold: true
                                }
                            }
                            Label {
                                text: "Localyze"
                                color: root.textSecondary
                                font.pixelSize: 12; font.weight: Font.Medium
                            }
                            Label {
                                visible: assistantBlock.streaming
                                text: "thinking…"
                                color: root.textMuted
                                font.pixelSize: 12; font.italic: true
                                SequentialAnimation on opacity {
                                    running: assistantBlock.streaming
                                    loops: Animation.Infinite
                                    NumberAnimation { to: 0.4; duration: 600 }
                                    NumberAnimation { to: 1.0; duration: 600 }
                                }
                            }
                            Item { Layout.fillWidth: true }
                            // === VARIANTS ADDITION START ===
                            // Character.AI-style swipe nav. Shown only when
                            // the row has stored alternative generations
                            // (model.variants.length > 0). Arrows step
                            // through `variants` by mutating `variantIndex`;
                            // the bound `_displayedContent` (and therefore
                            // ThoughtCard + MessageBody) updates reactively.
                            // Rendered BEFORE the Copy/Retry pair so the
                            // layout reads "‹ 2/3 ›  ⧉ Copy  ↻ Retry".
                            Row {
                                id: variantsNav
                                spacing: 0
                                opacity: assistantBlock.hovered && !assistantBlock.streaming ? 1 : 0
                                Behavior on opacity { NumberAnimation { duration: 120 } }
                                visible: !assistantBlock.streaming
                                         && model.variants !== undefined
                                         && model.variants !== null
                                         && model.variants.length > 0
                                ToolButton {
                                    text: "‹"
                                    font.pixelSize: 14
                                    font.bold: true
                                    enabled: (model.variantIndex || 0) > 0
                                    onClicked: {
                                        const cur = model.variantIndex || 0
                                        if (cur > 0) {
                                            messages.setProperty(index, "variantIndex", cur - 1)
                                        }
                                    }
                                    hoverEnabled: true
                                    ToolTip.text: "Previous variant"
                                    ToolTip.visible: hovered
                                    ToolTip.delay: 400
                                }
                                Label {
                                    anchors.verticalCenter: parent.verticalCenter
                                    text: ((model.variantIndex || 0) + 1) + "/" + (model.variants ? model.variants.length : 1)
                                    color: root.textSecondary
                                    font.pixelSize: 12
                                    leftPadding: 4
                                    rightPadding: 4
                                }
                                ToolButton {
                                    text: "›"
                                    font.pixelSize: 14
                                    font.bold: true
                                    enabled: !!model.variants && (model.variantIndex || 0) < (model.variants.length - 1)
                                    onClicked: {
                                        const cur = model.variantIndex || 0
                                        const max = (model.variants ? model.variants.length : 1) - 1
                                        if (cur < max) {
                                            messages.setProperty(index, "variantIndex", cur + 1)
                                        }
                                    }
                                    hoverEnabled: true
                                    ToolTip.text: "Next variant"
                                    ToolTip.visible: hovered
                                    ToolTip.delay: 400
                                }
                            }
                            // === VARIANTS ADDITION END ===
                            // Action row — appears on hover or after streaming
                            Row {
                                spacing: 2
                                opacity: assistantBlock.hovered && !assistantBlock.streaming ? 1 : 0
                                Behavior on opacity { NumberAnimation { duration: 120 } }
                                visible: !assistantBlock.streaming
                                ToolButton {
                                    text: "⧉ Copy"
                                    font.pixelSize: 12
                                    // === VARIANTS ADDITION START ===
                                    // Copy the currently-displayed variant,
                                    // not the row's raw content (which on a
                                    // variants row holds the latest gen).
                                    onClicked: root._copyToClipboard(assistantBlock._cleanedBody)
                                    // === VARIANTS ADDITION END ===
                                    hoverEnabled: true
                                    ToolTip.text: "Copy to clipboard"
                                    ToolTip.visible: hovered
                                    ToolTip.delay: 400
                                }
                                ToolButton {
                                    text: "↻ Retry"
                                    font.pixelSize: 12
                                    // === VARIANTS ADDITION START ===
                                    // Retry is allowed on the most-recent
                                    // assistant row OR any row that already
                                    // has variants (so the user can keep
                                    // swiping & re-generating).
                                    visible: index === messages.count - 1
                                             || (model.variants !== undefined
                                                 && model.variants !== null
                                                 && model.variants.length > 0)
                                    // === VARIANTS ADDITION END ===
                                    onClicked: root._regenerate()
                                    hoverEnabled: true
                                    ToolTip.text: "Regenerate"
                                    ToolTip.visible: hovered
                                    ToolTip.delay: 400
                                }
                            }
                        }
                        // HoverHandler is the Qt 6 idiom — works inside Layouts
                        // without the "anchors on a layout-managed item" warning.
                        HoverHandler {
                            id: assistantHover
                        }
                        // === VARIANTS ADDITION START ===
                        // Resolve the currently-displayed assistant text. If
                        // the row has alternative generations stored in
                        // `variants` (populated by Retry / _regenerate), use
                        // the one at `variantIndex`; otherwise fall back to
                        // the live `content`. ThoughtCard and MessageBody
                        // below both consume `_cleanedBody`, which now reads
                        // from this variant-aware source.
                        property string _displayedContent: {
                            const vs = (model.variants && model.variants.length > 0) ? model.variants : null
                            if (vs) {
                                const idx = Math.max(0, Math.min(vs.length - 1, model.variantIndex || 0))
                                return vs[idx] || ""
                            }
                            return model.content || ""
                        }
                        // === VARIANTS ADDITION END ===
                        // === THOUGHT-CARD ADDITION START ===
                        // Extract any <thought>...</thought> segments from the
                        // raw assistant content. The joined thought text feeds
                        // a collapsible ThoughtCard (Claude-style reasoning
                        // panel), while the cleaned body (with thought tags
                        // stripped) is what MessageBody renders below.
                        property string _thoughtText: {
                            const re = /<thought>([\s\S]*?)<\/thought>/gi
                            const raw = assistantBlock._displayedContent
                            const parts = []
                            let m
                            while ((m = re.exec(raw)) !== null) {
                                parts.push(m[1].trim())
                            }
                            return parts.join("\n\n")
                        }
                        property string _cleanedBody: {
                            const raw = assistantBlock._displayedContent
                            return raw.replace(/<thought>[\s\S]*?<\/thought>/gi, "").trim()
                        }

                        ColumnLayout {
                            visible: assistantBlock._thoughtText.length > 0
                            Layout.fillWidth: true
                            Layout.leftMargin: 30
                            spacing: 0
                            ThoughtCard {
                                Layout.fillWidth: true
                                thoughtText: assistantBlock._thoughtText
                                elapsedHint: ""
                            }
                        }
                        // === THOUGHT-CARD ADDITION END ===

                        RowLayout {
                            Layout.fillWidth: true
                            Layout.leftMargin: 30
                            spacing: 0
                            // Rich-markdown renderer: splits the reply into
                            // paragraph + fenced-code segments and renders each
                            // with the right widget (paragraph → MarkdownText,
                            // code → styled card with language label + copy).
                            MessageBody {
                                Layout.fillWidth: true
                                content: assistantBlock._cleanedBody
                                onCopyRequested: (text) => toast.show("Copied")
                                // Writing Tools popover: selection → action template.
                                onActionRequested: (prompt) => {
                                    input.text = prompt
                                    root._send()
                                }
                            }
                            // === STREAM-POLISH ADDITION START ===
                            // Softer streaming caret. Replaces the hard 8x16
                            // rectangle with a slightly translucent accent
                            // chip (6x14, radius 2) that pulses to 0.4 rather
                            // than 0.15 — same 500ms timing on each leg, but
                            // the perceived motion reads as a gentle breath
                            // instead of a flickering box jumping at line ends.
                            Rectangle {
                                visible: assistantBlock.streaming
                                Layout.preferredWidth: 6; Layout.preferredHeight: 14
                                Layout.alignment: Qt.AlignBottom
                                Layout.bottomMargin: 4
                                color: root.accent
                                radius: 2
                                opacity: 0.85
                                SequentialAnimation on opacity {
                                    loops: Animation.Infinite
                                    running: assistantBlock.streaming
                                    NumberAnimation { to: 0.4; duration: 500 }
                                    NumberAnimation { to: 0.85; duration: 500 }
                                }
                            }
                            // === STREAM-POLISH ADDITION END ===
                        }

                        // === FOLLOWUP-CHIPS ADDITION START ===
                        // Claude-style follow-up suggestion chips. Rendered
                        // only on the most-recent finished assistant message.
                        // Click → auto-fill the composer with the chip text
                        // and send it (same code path as a manual send).
                        FollowUpChips {
                            Layout.fillWidth: true
                            Layout.leftMargin: 30
                            Layout.topMargin: 4
                            visible: role === "assistant"
                                     && index === messages.count - 1
                                     && !root.sending
                            content: assistantBlock._cleanedBody
                            onSelected: (text) => {
                                input.text = text
                                root._send()
                            }
                        }
                        // === FOLLOWUP-CHIPS ADDITION END ===
                    }

                    // ----- trace (plan/act/observe, expandable card) -----
                    Rectangle {
                        id: traceCard
                        visible: role === "trace"
                        Layout.alignment: Qt.AlignLeft
                        Layout.leftMargin: 30
                        Layout.preferredWidth: traceCard.expanded
                            ? Math.min(traceFull.implicitWidth + 24, parent.width - 60)
                            : Math.min(traceShort.implicitWidth + 24, parent.width - 60)
                        Layout.preferredHeight: traceCard.expanded
                            ? traceFull.implicitHeight + 18
                            : traceShort.implicitHeight + 12
                        color: traceArea.containsMouse ? root.codeBg : root.surface
                        radius: 8
                        border.color: root.border; border.width: 1
                        opacity: 0.85
                        Behavior on color { ColorAnimation { duration: 120 } }
                        property bool expanded: false
                        MouseArea {
                            id: traceArea
                            anchors.fill: parent
                            hoverEnabled: true
                            cursorShape: Qt.PointingHandCursor
                            onClicked: traceCard.expanded = !traceCard.expanded
                            ToolTip.text: "Click to " + (traceCard.expanded ? "collapse" : "expand")
                            ToolTip.visible: containsMouse && !traceCard.expanded
                            ToolTip.delay: 600
                        }
                        Label {
                            id: traceShort
                            visible: !traceCard.expanded
                            anchors.fill: parent
                            anchors.margins: 6
                            anchors.leftMargin: 12
                            text: "▸ " + kind + " · " + content
                            color: root.traceColor
                            font.pixelSize: 11
                            font.family: "Menlo, Monaco, monospace"
                            elide: Label.ElideRight
                            maximumLineCount: 1
                        }
                        Label {
                            id: traceFull
                            visible: traceCard.expanded
                            anchors.fill: parent
                            anchors.margins: 9
                            anchors.leftMargin: 12
                            text: "▾ " + kind + "\n" + content
                            color: root.traceColor
                            font.pixelSize: 11
                            font.family: "Menlo, Monaco, monospace"
                            wrapMode: Label.Wrap
                        }
                    }
                }
            }

            // bottom spacer
            Item { Layout.preferredHeight: 36 }
        }

        ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

        // "Stick to bottom" autoscroll: only pull to the latest content if the
        // user was already near the bottom. If they've scrolled up to re-read
        // an earlier turn, leave them where they are.
        property bool stickToBottom: true
        onContentHeightChanged: if (stickToBottom)
            contentItem.contentY = Math.max(0, contentHeight - height)
        Connections {
            target: scroller.contentItem
            function onContentYChanged() {
                const atBottom = (scroller.contentHeight - scroller.contentItem.contentY - scroller.height) < 24
                scroller.stickToBottom = atBottom
            }
        }
    }

    // -------------------- composer (input + web + send/stop) --------------------
    Rectangle {
        id: composerWrap
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        height: 156
        color: "transparent"

        Rectangle {
            id: composerCard
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.top: parent.top
            anchors.topMargin: 6
            width: Math.min(root.contentMaxWidth, parent.width - 48)
            height: composerCol.implicitHeight + 24
            radius: 18
            color: root.surface
            border.color: input.activeFocus ? root.borderStrong : root.border
            border.width: 1
            Behavior on border.color { ColorAnimation { duration: 120 } }

            ColumnLayout {
                id: composerCol
                anchors.fill: parent
                anchors.margins: 12
                spacing: 8

                TextArea {
                    id: input
                    Layout.fillWidth: true
                    Layout.preferredHeight: 50
                    placeholderText: root.hasModel
                        ? "Reply to Localyze…"
                        : "Set up the model in Settings to start chatting."
                    enabled: root.hasModel
                    color: root.textPrimary
                    placeholderTextColor: root.textMuted
                    wrapMode: TextArea.Wrap
                    background: null
                    font.pixelSize: 14
                    font.family: "Roboto, Inter, system-ui, sans-serif"
                    // === SLASH-COMMANDS ADDITION START ===
                    // Open/close the slash-command picker based on the current
                    // input buffer. We show the picker only when the buffer
                    // matches `^/\w*$` — a single leading slash followed by
                    // zero-or-more word chars. Anything else (a space,
                    // multiple lines, text before the slash) closes it.
                    onTextChanged: root._maybeShowSlashMenu()
                    // === SLASH-COMMANDS ADDITION END ===
                    Keys.onPressed: (e) => {
                        // === SLASH-COMMANDS ADDITION START ===
                        // While the picker is visible it owns arrow keys,
                        // Enter, Tab, and Escape. This prevents the message
                        // from being sent when the user presses Enter to
                        // pick a command.
                        if (slashMenu.visible) {
                            if (e.key === Qt.Key_Down) {
                                slashMenu.moveSelection(1); e.accepted = true; return
                            }
                            if (e.key === Qt.Key_Up) {
                                slashMenu.moveSelection(-1); e.accepted = true; return
                            }
                            if (e.key === Qt.Key_Return || e.key === Qt.Key_Enter
                                || e.key === Qt.Key_Tab) {
                                slashMenu.acceptCurrent(); e.accepted = true; return
                            }
                            if (e.key === Qt.Key_Escape) {
                                slashMenu.close(); e.accepted = true; return
                            }
                        }
                        // === SLASH-COMMANDS ADDITION END ===
                        // Send on Return / numpad Enter without Shift.
                        const isSend = (e.key === Qt.Key_Return || e.key === Qt.Key_Enter)
                                       && !(e.modifiers & Qt.ShiftModifier)
                        if (isSend) {
                            root._send()
                            e.accepted = true
                        }
                    }
                }

                // === COMPOSER-PILLS ADDITION START ===
                // Horizontal strip of pill toggles, Mistral/Grok/Perplexity-style.
                // The shared Component below is instantiated once per pill via
                // Loader so each pill stays self-contained but visually uniform.
                Component {
                    id: pillComponent
                    Rectangle {
                        id: pill
                        property string label: ""
                        property string glyph: ""
                        property bool   on: false
                        property bool   placeholder: false
                        property string tooltip: ""
                        signal toggled()

                        Layout.preferredHeight: 30
                        radius: 14
                        implicitWidth: pillContent.implicitWidth + 22
                        opacity: placeholder ? 0.55 : 1.0
                        color: on
                               ? root.accent
                               : (pillArea.containsMouse && !placeholder
                                    ? root.surfaceSubtle
                                    : "transparent")
                        border.color: on ? root.accent : root.border
                        border.width: 1
                        Behavior on color { ColorAnimation { duration: 120 } }
                        Behavior on border.color { ColorAnimation { duration: 120 } }

                        RowLayout {
                            id: pillContent
                            anchors.centerIn: parent
                            spacing: 6
                            Label {
                                text: pill.glyph
                                color: pill.on ? "white" : root.textSecondary
                                font.pixelSize: 12
                            }
                            Label {
                                text: pill.label
                                color: pill.on ? "white" : root.textSecondary
                                font.pixelSize: 12
                                font.weight: Font.Medium
                            }
                        }

                        MouseArea {
                            id: pillArea
                            anchors.fill: parent
                            hoverEnabled: !pill.placeholder
                            cursorShape: pill.placeholder ? Qt.ArrowCursor : Qt.PointingHandCursor
                            onClicked: pill.toggled()
                            ToolTip.text: pill.tooltip
                            ToolTip.visible: containsMouse && pill.tooltip.length > 0
                            ToolTip.delay: 400
                        }
                    }
                }

                RowLayout {
                    id: pillRow
                    Layout.fillWidth: true
                    spacing: 8

                    Loader {
                        sourceComponent: pillComponent
                        onLoaded: {
                            item.label = "Web"
                            item.glyph = "🌐"
                            item.tooltip = "Toggle web search (Ctrl+W)"
                            item.on = Qt.binding(function() { return settings.webSearchEnabled })
                            item.toggled.connect(function() {
                                settings.webSearchEnabled = !settings.webSearchEnabled
                            })
                        }
                    }
                    Loader {
                        sourceComponent: pillComponent
                        onLoaded: {
                            item.label = "Think"
                            item.glyph = "✦"
                            item.tooltip = "Ask the model to reason step-by-step"
                            item.on = Qt.binding(function() { return chatSettings.thinkingEnabled })
                            item.toggled.connect(function() {
                                chatSettings.thinkingEnabled = !chatSettings.thinkingEnabled
                            })
                        }
                    }
                    Loader {
                        sourceComponent: pillComponent
                        onLoaded: {
                            item.label = "Image"
                            item.glyph = "🖼"
                            item.tooltip = "Image input coming soon"
                            item.placeholder = true
                            item.on = false
                            item.toggled.connect(function() {
                                toast.show("Image input coming soon")
                            })
                        }
                    }
                    Loader {
                        sourceComponent: pillComponent
                        onLoaded: {
                            item.label = "Code"
                            item.glyph = "💻"
                            item.tooltip = "Switch to code mode"
                            item.on = Qt.binding(function() { return modeStore.currentMode === "code" })
                            item.toggled.connect(function() {
                                modeStore.currentMode = (modeStore.currentMode === "code") ? "chat" : "code"
                            })
                        }
                    }

                    // Spacer between the left-side action pills and the
                    // right-side model chip + send button. Keeps the pills
                    // on the bottom-left and the send affordance bottom-right
                    // on the same row — matches Claude / ChatGPT / Mistral
                    // composer layout. Previously these were two separate
                    // RowLayouts stacked in the ColumnLayout, which made the
                    // pills "float in mid-air" above the bottom row.
                    Item { Layout.fillWidth: true }

                    // Model chip
                    Rectangle {
                        Layout.preferredHeight: 30
                        Layout.preferredWidth: modelLabel.implicitWidth + 24
                        radius: 15
                        color: "transparent"
                        border.color: root.border; border.width: 1
                        Label {
                            id: modelLabel
                            anchors.centerIn: parent
                            text: "Gemma 4 E4B · int4"
                            color: root.textSecondary
                            font.pixelSize: 12
                        }
                    }

                    // Send / Stop button (filled circle)
                    Rectangle {
                        id: sendBtn
                        Layout.preferredWidth: 36
                        Layout.preferredHeight: 36
                        radius: 18
                        color: root.sending
                               ? (sendArea.containsMouse ? root.stopColor : root.accent)
                               : ((input.text.trim() === "" || !root.hasModel)
                                    ? root.surfaceSubtle
                                    : (sendArea.containsMouse ? root.accentHover : root.accent))
                        Behavior on color { ColorAnimation { duration: 120 } }
                        // While streaming, draw a faint 1px ring around the
                        // button so the user notices the stop affordance at a
                        // glance — the inner glyph already morphs to a square,
                        // but the ring gives it a clear secondary signal.
                        Rectangle {
                            visible: root.sending
                            anchors.fill: parent
                            anchors.margins: -2
                            radius: 20
                            color: "transparent"
                            border.color: Qt.rgba(1, 1, 1, 0.4)
                            border.width: 1
                            z: -1
                        }
                        MouseArea {
                            id: sendArea
                            anchors.fill: parent
                            hoverEnabled: true
                            cursorShape: Qt.PointingHandCursor
                            enabled: root.sending || (input.text.trim() !== "" && root.hasModel)
                            onClicked: root.sending ? root._stop() : root._send()
                        }
                        // Send arrow OR stop square
                        Label {
                            visible: !root.sending
                            anchors.centerIn: parent
                            text: "↑"
                            color: (input.text.trim() === "" || !root.hasModel) ? root.textMuted : "white"
                            font.pixelSize: 17; font.bold: true
                        }
                        Rectangle {
                            visible: root.sending
                            anchors.centerIn: parent
                            width: 12; height: 12; radius: 2
                            color: "white"
                        }
                    }
                }
                // === COMPOSER-PILLS ADDITION END ===
            }
        }

        Label {
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.bottom: parent.bottom
            anchors.bottomMargin: 6
            text: root.sending
                ? "Press Esc to stop · generation runs on your GPU"
                : "Press ⏎ to send · ⇧⏎ for newline · Ctrl+N new chat · all on-device"
            color: root.textMuted
            font.pixelSize: 11
        }

        // === SLASH-COMMANDS ADDITION START ===
        // Copilot/Cursor-style slash picker. Anchored to the composer card:
        // prefers floating ABOVE the input, but drops below when there isn't
        // enough room (e.g. the chat is empty and the composer is near the
        // top of the viewport). Visibility is driven from input.onTextChanged
        // via root._maybeShowSlashMenu(); keyboard handling is in input.Keys.
        SlashMenu {
            id: slashMenu
            target: input
            // Horizontally aligned with the composer card's left edge.
            x: composerCard.x + 4
            // Drop down when there is not enough room above the composer.
            readonly property bool dropDown: composerCard.y < (height + 12)
            y: dropDown
                ? composerCard.y + composerCard.height + 6
                : composerCard.y - height - 6
            z: 60
        }
        // === SLASH-COMMANDS ADDITION END ===
    }

    // -------------------- conversation history drawer --------------------
    ConversationDrawer {
        id: conversationDrawer
        width: Math.min(360, root.width * 0.42)
        height: root.height
        onCreateRequested: root._newChat()
    }

    // -------------------- settings drawer --------------------
    Drawer {
        id: settingsDrawer
        edge: Qt.RightEdge
        width: Math.min(420, root.width * 0.45)
        height: root.height
        modal: true
        dragMargin: 0
        background: Rectangle { color: root.surface; border.width: 0 }

        ColumnLayout {
            anchors.fill: parent
            anchors.margins: 22
            spacing: 18

            Label {
                text: "Settings"
                font.pixelSize: 20; font.weight: Font.Medium
                font.family: "Charter, Georgia, serif"
                color: root.textPrimary
            }

            // --- Web search ---
            ColumnLayout {
                spacing: 6
                Label { text: "Web search"; font.pixelSize: 13; font.weight: Font.Medium; color: root.textPrimary }
                Label { text: "When on, the model can call web.search for live data."; font.pixelSize: 12; color: root.textSecondary; wrapMode: Label.WordWrap; Layout.fillWidth: true }
                Switch {
                    checked: settings.webSearchEnabled
                    onToggled: settings.webSearchEnabled = checked
                }
            }

            Rectangle { Layout.fillWidth: true; height: 1; color: root.border }

            // --- Appearance / dark mode ---
            ColumnLayout {
                spacing: 6
                Label { text: "Appearance"; font.pixelSize: 13; font.weight: Font.Medium; color: root.textPrimary }
                Label {
                    text: themeBridge.hasUserPreference
                        ? (Theme.dark ? "Dark mode (manual override)" : "Light mode (manual override)")
                        : (Theme.dark ? "Dark mode (from system)" : "Light mode (from system)")
                    font.pixelSize: 12; color: root.textSecondary; wrapMode: Label.WordWrap; Layout.fillWidth: true
                }
                RowLayout {
                    spacing: 10
                    Switch {
                        id: darkSwitch
                        checked: Theme.dark
                        onToggled: Theme.applyTheme(checked)
                    }
                    Label { text: "Dark mode"; color: root.textPrimary; font.pixelSize: 12 }
                    Item { Layout.fillWidth: true }
                    Button {
                        text: "Follow system"
                        font.pixelSize: 11
                        visible: themeBridge.hasUserPreference
                        onClicked: themeBridge.clearPreference()
                    }
                }
            }

            Rectangle { Layout.fillWidth: true; height: 1; color: root.border }

            // --- Context length ---
            ColumnLayout {
                spacing: 6
                Label { text: "Context length"; font.pixelSize: 13; font.weight: Font.Medium; color: root.textPrimary }
                Label {
                    text: settings.contextSizeOverride > 0
                        ? "Override: " + settings.contextSizeOverride + " tokens"
                        : "Auto (" + settings.recommendedContextSize + " tokens — based on detected VRAM/RAM)"
                    font.pixelSize: 12; color: root.textSecondary
                    wrapMode: Label.WordWrap; Layout.fillWidth: true
                }
                RowLayout {
                    spacing: 6
                    Repeater {
                        model: [0, 2048, 4096, 8192, 16384, 32768]
                        delegate: Button {
                            text: modelData === 0 ? "Auto" : modelData.toString()
                            font.pixelSize: 11
                            highlighted: settings.contextSizeOverride === modelData
                            onClicked: settings.contextSizeOverride = modelData
                        }
                    }
                }
            }

            Rectangle { Layout.fillWidth: true; height: 1; color: root.border }

            // --- Model path ---
            ColumnLayout {
                spacing: 6
                Label { text: "Model file"; font.pixelSize: 13; font.weight: Font.Medium; color: root.textPrimary }
                Label { text: settings.modelPath; font.pixelSize: 11; color: root.textSecondary; wrapMode: Label.WrapAnywhere; Layout.fillWidth: true; font.family: "Menlo, Monaco, monospace" }
                RowLayout {
                    spacing: 8
                    Label {
                        text: root.hasModel ? "✓ Loaded" : "✗ Not found — pick a GGUF below"
                        color: root.hasModel ? "#4a7a3a" : "#a23a3a"
                        font.pixelSize: 11
                    }
                    Item { Layout.fillWidth: true }
                    Button {
                        text: "Browse…"
                        font.pixelSize: 11
                        onClicked: modelFileDialog.open()
                    }
                }
                Label {
                    visible: !root.hasModel
                    text: "Note: changing the path requires restarting Localyze."
                    color: root.textMuted; font.pixelSize: 10
                }
            }
            FileDialog {
                id: modelFileDialog
                title: "Select Gemma GGUF model"
                nameFilters: ["GGUF model (*.gguf)", "All files (*)"]
                onAccepted: settings.modelPath = selectedFile.toString().replace(/^file:\/\//, "")
            }

            Rectangle { Layout.fillWidth: true; height: 1; color: root.border }

            // --- Hardware info ---
            ColumnLayout {
                spacing: 4
                Label { text: "Hardware"; font.pixelSize: 13; font.weight: Font.Medium; color: root.textPrimary }
                Label { text: root.backendLabel; font.pixelSize: 12; color: root.textSecondary; wrapMode: Label.WordWrap; Layout.fillWidth: true }
                Label { text: root.backendReason; font.pixelSize: 11; color: root.textMuted; wrapMode: Label.WordWrap; Layout.fillWidth: true }
            }

            Item { Layout.fillHeight: true }

            // --- Reset onboarding (developer/QA aid) ---
            RowLayout {
                Layout.fillWidth: true
                spacing: 8
                Label {
                    text: "Re-run the welcome wizard"
                    font.pixelSize: 11; color: root.textMuted
                    Layout.fillWidth: true
                }
                Button {
                    text: "Reset"
                    font.pixelSize: 11
                    onClicked: {
                        settings.onboarded = false
                        settingsDrawer.close()
                        toast.show("Onboarding reset")
                    }
                }
            }

            // --- About ---
            Label {
                text: "Localyze runs Gemma 4 E4B Q4 entirely on your device. No prompts leave this machine unless web search is on."
                font.pixelSize: 11; color: root.textMuted
                wrapMode: Label.WordWrap; Layout.fillWidth: true
            }
        }
    }

    // ANCHOR: artifact-panel-mount
    // === ARTIFACT PANEL ADDITIONS START ===
    // Claude-style right-edge artifact workspace. Anchored below the header
    // bar so the chat-column header stays interactive while the panel is
    // open. Z above the conversation but below settings/onboarding modals.
    ArtifactPanel {
        id: artifactPanel
        anchors.top: header.bottom
        anchors.bottom: parent.bottom
        // The panel positions itself horizontally based on its `open`
        // property (slide-in animation). Width is computed inside.
        artifactModel: artifactItems
        currentIndex: root.artifactCurrentIndex
        open: root.artifactPanelOpen
        z: 50
        onCloseRequested: root.artifactPanelOpen = false
        onCurrentIndexChanged: root.artifactCurrentIndex = currentIndex
        onCopyRequested: (text) => toast.show("Copied")
    }
    // === ARTIFACT PANEL ADDITIONS END ===

    // -------------------- toast --------------------
    Rectangle {
        id: toast
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottom: composerWrap.top
        anchors.bottomMargin: 14
        width: toastLabel.implicitWidth + 28
        height: 32; radius: 16
        color: root.textPrimary
        opacity: 0
        z: 100
        function show(text) { toastLabel.text = text; toastAnim.restart() }
        Label {
            id: toastLabel
            anchors.centerIn: parent
            color: "white"
            font.pixelSize: 12
        }
        SequentialAnimation {
            id: toastAnim
            NumberAnimation { target: toast; property: "opacity"; to: 0.95; duration: 140 }
            PauseAnimation { duration: 1200 }
            NumberAnimation { target: toast; property: "opacity"; to: 0; duration: 240 }
        }
    }

    Component.onCompleted: input.forceActiveFocus()

    // -------------------- first-launch onboarding wizard --------------------
    OnboardingView {
        id: onboardingView
        anchors.fill: parent
        visible: !settings.onboarded
        z: 200
        backendLabel:  root.backendLabel
        backendReason: root.backendReason
        hasModel:      root.hasModel
        onOnFinished: {
            settings.onboarded = true
        }
    }
}
