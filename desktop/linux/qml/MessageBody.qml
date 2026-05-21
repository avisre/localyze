import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtCharts
import LocalyzeUI

// MessageBody — drop-in replacement for the assistant TextEdit that used to
// render the whole reply with textFormat: MarkdownText. The problem with that
// approach: fenced code blocks (```lang ... ```) rendered as walls of
// unstyled monospace with no per-block copy button.
//
// This component splits the markdown into ordered segments — paragraphs,
// fenced code blocks, AND markdown tables — and renders each one with the
// right widget:
//
//   * Paragraph  → TextEdit { MarkdownText }   (existing rich rendering)
//   * Fenced code → styled card with language label + per-block "Copy" button
//   * Markdown table → styled table + inline QtCharts ChartView when ≥1 col is numeric
//
// Inline code (`foo`) stays inside the paragraph TextEdit so MarkdownText
// styles it. We keep the parsing simple — no external library — so the
// streaming case (partial fences / partial tables while tokens arrive) stays
// correct.
Item {
    id: root

    // The full markdown text. Re-parsed on every change so it works during
    // token-by-token streaming.
    property string content: ""

    // ----- design tokens (mirrors ChatView for visual consistency) -----
    readonly property color textPrimary:   "#1c1b1a"
    readonly property color textSecondary: "#6b6960"
    readonly property color accent:        "#cc785c"
    // Code-card palette — dark, like Claude/ChatGPT
    readonly property color codeCardBg:    "#1c1b1a"
    readonly property color codeCardFg:    "#e6edf3"
    readonly property color codeCardSub:   "#9a9788"
    readonly property color codeCardBdr:   "#2d2c2a"

    signal copyRequested(string text)   // bubbled up so ChatView can show its toast

    // === WRITING-TOOLS ADDITION START ===
    // Bubbled up to ChatView when the user picks a Writing-Tools action from
    // the selection-floating popover. `prompt` already has the selected text
    // substituted into the template, so ChatView just stuffs it into the
    // composer and fires _send().
    signal actionRequested(string prompt)
    // === WRITING-TOOLS ADDITION END ===

    implicitHeight: col.implicitHeight
    implicitWidth: col.implicitWidth

    // ---- segment parser ---------------------------------------------------
    // Walks the markdown line-by-line, accumulating paragraphs until it hits
    // a ``` fence or a markdown table block. Output: a flat list of
    // {kind, lang, text, ...} where kind is "para", "code", or "table".
    //
    // Table detection — two paths:
    //   1. Strict: header row + divider row (`|---|---|`, `| --- |`,
    //      `|:---:|`, `|===|`, single-dash `|-|-|`, alignment-marked, etc.)
    //      + ≥1 data row.
    //   2. Soft (fallback when no divider is present): ≥3 consecutive lines
    //      that all start with `|` and share the same column count. Row 0
    //      becomes the header.
    // Streaming-safe: a table is only recognised once it is followed by a
    // non-table line (blank or other), i.e. a clear closing boundary. If
    // the table is still mid-stream we fall back to rendering it as a
    // plain paragraph.
    function _parse(md) {
        const out = []
        if (!md || md.length === 0) return out
        const lines = md.split("\n")
        let buf = []
        let inCode = false
        let codeLang = ""
        let codeBuf = []
        const flushPara = () => {
            if (buf.length === 0) return
            while (buf.length && buf[buf.length - 1] === "") buf.pop()
            if (buf.length === 0) return
            out.push({kind: "para", lang: "", text: buf.join("\n"), closed: true,
                      tableJson: "", chartHint: ""})
            buf = []
        }
        const flushCode = (closed) => {
            out.push({
                kind: "code",
                lang: codeLang,
                text: codeBuf.join("\n"),
                closed: closed,
                tableJson: "",
                chartHint: ""
            })
            codeBuf = []
            codeLang = ""
        }
        const isTableLine = (s) => /^\s*\|.*\|\s*$/.test(s)
        // Divider line: cells of only dashes/equals/colons/whitespace.
        // Accept loose variants:
        //   |---|---|              (existing strict)
        //   | --- | --- |          (spaces inside cells)
        //   |:---|---:|:---:|      (alignment markers)
        //   |-|-|                  (single dash)
        //   |===|===|              (equals as divider char)
        //   |:=:|                  (mixed)
        // Each cell must contain at least one - or = and only those chars,
        // optional surrounding colons, and whitespace.
        const isDividerLine = (s) => /^\s*\|(\s*:?[-=]+:?\s*\|)+\s*$/.test(s)
        // Count columns in a table line. Used for the "soft table" fallback
        // when no divider line is present — we accept the block as a table
        // if ≥3 consecutive `|`-rows share the same column count.
        const countCols = (s) => {
            let t = s.trim()
            if (t.charAt(0) === "|") t = t.substring(1)
            if (t.length > 0 && t.charAt(t.length - 1) === "|") t = t.substring(0, t.length - 1)
            return t.split("|").length
        }

        for (let i = 0; i < lines.length; ++i) {
            const ln = lines[i]
            const fenceMatch = ln.match(/^\s*```\s*([A-Za-z0-9_+\-]*)\s*$/)
            if (fenceMatch) {
                if (!inCode) {
                    flushPara()
                    inCode = true
                    codeLang = fenceMatch[1] || ""
                } else {
                    flushCode(true)
                    inCode = false
                }
                continue
            }
            if (inCode) {
                codeBuf.push(ln)
                continue
            }

            // Detect a table starting at line i: header + divider + ≥1 row
            // AND a clear closing boundary (next non-table line exists, or
            // there's a blank line in the trailing context). This avoids
            // grabbing a half-streamed table.
            if (isTableLine(ln)
                && i + 1 < lines.length
                && isDividerLine(lines[i + 1])) {
                // Walk forward to collect all consecutive table rows
                let end = i + 2
                while (end < lines.length && isTableLine(lines[end])) end++
                // Streaming guard: only commit the table once we see a clear
                // close — either there's a line AFTER the table block (so
                // the model has moved on) or end has reached EOF AND the
                // raw content ends with a newline (signalling closure).
                const hasFollowingLine = end < lines.length
                const endsWithNewline = md.length > 0 && md.charAt(md.length - 1) === "\n"
                const dataRowCount = end - (i + 2)
                if (dataRowCount >= 1 && (hasFollowingLine || endsWithNewline)) {
                    // Sniff a chart-kind hint from the preceding paragraph text
                    let hint = ""
                    const ctx = buf.join(" ").toLowerCase()
                    if (/\bbar chart\b|\bas a bar\b/.test(ctx)) hint = "bar"
                    else if (/\bline chart\b|\bas a line\b/.test(ctx)) hint = "line"
                    else if (/\bpie chart\b|\bas a pie\b/.test(ctx)) hint = "pie"

                    flushPara()
                    const headerCells = root._splitRow(lines[i])
                    const dataRows = []
                    for (let r = i + 2; r < end; ++r) {
                        dataRows.push(root._splitRow(lines[r]))
                    }
                    const tableJson = JSON.stringify({
                        headers: headerCells,
                        rows: dataRows
                    })
                    out.push({
                        kind: "table",
                        lang: "",
                        text: lines.slice(i, end).join("\n"),
                        closed: true,
                        tableJson: tableJson,
                        chartHint: hint
                    })
                    i = end - 1   // jump past the table block
                    continue
                }
                // else: fall through to paragraph accumulation (streaming)
            }

            // Soft-table fallback: ≥3 consecutive `|`-rows with the same
            // column count and NO divider line. Treat row 0 as the header,
            // rest as data. Only commit once we see a closing boundary so
            // we don't grab a half-streamed block. We also require that the
            // strict path above did NOT already fire (it would have
            // `continue`d), so reaching here means there's no divider on
            // line i+1.
            if (isTableLine(ln)
                && i + 2 < lines.length
                && isTableLine(lines[i + 1])
                && isTableLine(lines[i + 2])) {
                const baseCols = countCols(ln)
                if (baseCols >= 2
                    && countCols(lines[i + 1]) === baseCols
                    && countCols(lines[i + 2]) === baseCols) {
                    // Walk forward to collect all consecutive table rows
                    // sharing the same column count.
                    let end = i + 1
                    while (end < lines.length
                           && isTableLine(lines[end])
                           && countCols(lines[end]) === baseCols) {
                        end++
                    }
                    const hasFollowingLine = end < lines.length
                    const endsWithNewline = md.length > 0 && md.charAt(md.length - 1) === "\n"
                    // Need ≥3 rows total (header + ≥2 data) to be confident
                    // this is a table and not just two pipe-bracketed lines.
                    const totalRows = end - i
                    if (totalRows >= 3 && (hasFollowingLine || endsWithNewline)) {
                        // Sniff a chart-kind hint from preceding paragraph text
                        let hint = ""
                        const ctx = buf.join(" ").toLowerCase()
                        if (/\bbar chart\b|\bas a bar\b/.test(ctx)) hint = "bar"
                        else if (/\bline chart\b|\bas a line\b/.test(ctx)) hint = "line"
                        else if (/\bpie chart\b|\bas a pie\b/.test(ctx)) hint = "pie"

                        flushPara()
                        const headerCells = root._splitRow(lines[i])
                        const dataRows = []
                        for (let r = i + 1; r < end; ++r) {
                            dataRows.push(root._splitRow(lines[r]))
                        }
                        const tableJson = JSON.stringify({
                            headers: headerCells,
                            rows: dataRows
                        })
                        out.push({
                            kind: "table",
                            lang: "",
                            text: lines.slice(i, end).join("\n"),
                            closed: true,
                            tableJson: tableJson,
                            chartHint: hint
                        })
                        i = end - 1   // jump past the soft-table block
                        continue
                    }
                }
            }

            buf.push(ln)
        }
        if (inCode) flushCode(false)
        else flushPara()
        return out
    }

    // Split a markdown table row "| a | b | c |" → ["a", "b", "c"]
    // Strips the leading/trailing empty cells produced by the surrounding pipes.
    function _splitRow(line) {
        const trimmed = line.trim()
        // Remove leading & trailing | so split doesn't produce empty edge cells
        let s = trimmed
        if (s.charAt(0) === "|") s = s.substring(1)
        if (s.length > 0 && s.charAt(s.length - 1) === "|") s = s.substring(0, s.length - 1)
        return s.split("|").map(c => c.trim())
    }

    // The ListModel that drives the Repeater. We rebuild it on content change.
    // Segment count per reply is tiny so O(n) rebuilds are fine.
    ListModel { id: segments }

    function _rebuild() {
        const parsed = _parse(root.content)
        segments.clear()
        for (let i = 0; i < parsed.length; ++i) segments.append(parsed[i])
    }

    onContentChanged: _rebuild()
    Component.onCompleted: _rebuild()

    // Off-screen clipboard helper (TextEdit has a copy() method).
    TextEdit { id: clip; visible: false }
    function _copy(text) {
        clip.text = text
        clip.selectAll()
        clip.copy()
        root.copyRequested(text)
    }

    // === WRITING-TOOLS ADDITION START ===
    // One Writing-Tools popover instance per MessageBody. Paragraph TextEdits
    // open it (positioned at the selection's bottom edge) when the user
    // highlights text. Click an action → bubbles up via `actionRequested`.
    WritingToolsPopover {
        id: writingTools
        // Parent is set dynamically by _showWritingTools() so the popover's
        // coordinate system matches the segment that owns the selection.
        onActionRequested: (prompt) => root.actionRequested(prompt)
    }

    // Open the popover anchored 6 px below the selection end inside `te`.
    // We compute the cursor rectangle for `selectionEnd` and translate it
    // into the popover's parent coordinates.
    function _showWritingTools(te) {
        if (!te) return
        const sel = te.selectedText
        if (!sel || sel.length === 0) {
            writingTools.close()
            return
        }
        const r = te.cursorRectangle   // rectangle at the current cursor end
        // Re-parent to the TextEdit so x/y are in its local coords.
        writingTools.parent = te
        writingTools.selectedText = sel
        // Clamp x so the 520-wide popover stays inside the segment.
        let x = r.x
        const maxX = Math.max(0, te.width - writingTools.implicitWidth)
        if (x > maxX) x = maxX
        if (x < 0) x = 0
        writingTools.x = x
        writingTools.y = r.y + r.height + 6
        if (!writingTools.opened) writingTools.open()
    }
    // === WRITING-TOOLS ADDITION END ===

    // -------- tiny keyword highlighter (Python / JS / SQL) ---------------
    // Optional sugar — wraps a small set of keywords in <span> tags so the
    // RichText code-card renderer can colour them. No external library.
    readonly property var _kwPy: [
        "def","class","return","import","from","as","if","elif","else","for",
        "while","try","except","finally","with","lambda","yield","pass","break",
        "continue","raise","global","nonlocal","in","is","not","and","or","None",
        "True","False","self"
    ]
    readonly property var _kwJs: [
        "function","return","const","let","var","if","else","for","while","do",
        "switch","case","break","continue","new","class","extends","import",
        "from","export","default","async","await","try","catch","finally","throw",
        "typeof","instanceof","in","of","this","null","undefined","true","false"
    ]
    readonly property var _kwSql: [
        "SELECT","FROM","WHERE","INSERT","INTO","VALUES","UPDATE","SET","DELETE",
        "CREATE","TABLE","DROP","ALTER","JOIN","INNER","LEFT","RIGHT","OUTER",
        "ON","GROUP","BY","ORDER","HAVING","LIMIT","OFFSET","AS","AND","OR","NOT",
        "NULL","IS","IN","LIKE","BETWEEN","CASE","WHEN","THEN","ELSE","END",
        "UNION","ALL","DISTINCT","COUNT","SUM","AVG","MIN","MAX"
    ]
    function _escape(s) {
        return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    }
    function _highlight(lang, code) {
        const lc = (lang || "").toLowerCase()
        let kws = null
        if (lc === "py" || lc === "python") kws = root._kwPy
        else if (lc === "js" || lc === "javascript" || lc === "ts" || lc === "typescript") kws = root._kwJs
        else if (lc === "sql") kws = root._kwSql
        const escaped = root._escape(code)
        if (!kws) return escaped
        const pattern = new RegExp("\\b(" + kws.join("|") + ")\\b", "g")
        return escaped
            .replace(pattern, '<span style="color:#d2a8ff;font-weight:600">$1</span>')
            .replace(/("[^"\n]*"|'[^'\n]*')/g, '<span style="color:#a5d6a3">$1</span>')
            .replace(/(^|\n)(\s*(?:#|\/\/)[^\n]*)/g, '$1<span style="color:#6b6960;font-style:italic">$2</span>')
    }

    // ----- table → numeric column analysis ------------------------------
    // Returns an object describing which columns are numeric, parsed cell
    // values per row, and a recommended chart kind given the column shape
    // and any explicit hint from surrounding prose.
    function _analyzeTable(tableJson, hint) {
        let parsed
        try { parsed = JSON.parse(tableJson) } catch (e) { return null }
        if (!parsed || !parsed.headers || !parsed.rows) return null
        const headers = parsed.headers
        const rows = parsed.rows
        if (headers.length === 0 || rows.length === 0) return null

        // Per-column numeric detection: ≥80 % of rows must parse as Number.
        const numericFlags = []
        const parsedValues = []   // parallel to rows; each row → array of values
        for (let r = 0; r < rows.length; ++r) parsedValues.push([])

        for (let c = 0; c < headers.length; ++c) {
            let okCount = 0
            const colVals = []
            for (let r = 0; r < rows.length; ++r) {
                const cell = (rows[r][c] !== undefined) ? rows[r][c] : ""
                // Strip common decorations: %, $, commas, thousands separators
                const stripped = String(cell).replace(/[,%$\s]/g, "")
                const n = Number(stripped)
                if (stripped !== "" && isFinite(n)) {
                    okCount++
                    colVals.push(n)
                } else {
                    colVals.push(NaN)
                }
            }
            const isNum = (okCount / rows.length) >= 0.8
            numericFlags.push(isNum)
            for (let r = 0; r < rows.length; ++r) parsedValues[r].push(colVals[r])
        }

        // Locate categorical column (first non-numeric) and numeric column
        // indices. Used to drive series construction.
        let catIdx = -1
        const numericIdxs = []
        for (let c = 0; c < headers.length; ++c) {
            if (numericFlags[c]) numericIdxs.push(c)
            else if (catIdx === -1) catIdx = c
        }

        if (numericIdxs.length === 0) {
            return { headers: headers, rows: rows, numericFlags: numericFlags,
                     parsedValues: parsedValues, kind: "none",
                     catIdx: -1, numericIdxs: [] }
        }

        // Default: 1 numeric col → bar; 2+ numeric cols → line. Explicit
        // hint from surrounding text overrides.
        let kind = (numericIdxs.length === 1) ? "bar" : "line"
        if (hint === "bar" || hint === "line" || hint === "pie") kind = hint
        // Pie needs exactly 1 numeric col + 1 categorical
        if (kind === "pie" && (numericIdxs.length !== 1 || catIdx === -1)) {
            kind = "bar"
        }
        // Bar needs a categorical axis; if none, fall back to line
        if (kind === "bar" && catIdx === -1) kind = "line"

        return {
            headers: headers,
            rows: rows,
            numericFlags: numericFlags,
            parsedValues: parsedValues,
            kind: kind,
            catIdx: catIdx,
            numericIdxs: numericIdxs
        }
    }

    ColumnLayout {
        id: col
        width: parent.width
        spacing: 8

        Repeater {
            id: segmentRepeater
            model: segments

            // Each delegate is a stack: at most one of {paragraph, codeCard,
            // tableCard} is visible based on segment kind.
            delegate: Item {
                id: segItem
                required property int index
                required property string kind
                required property string lang
                required property string text
                required property bool closed
                required property string tableJson
                required property string chartHint

                Layout.fillWidth: true
                implicitHeight: segItem.kind === "code"
                                ? codeCard.implicitHeight
                                : (segItem.kind === "table"
                                   ? tableCard.implicitHeight
                                   : paraEdit.implicitHeight)

                // -------- paragraph (existing MarkdownText rendering) --------
                TextEdit {
                    id: paraEdit
                    visible: segItem.kind === "para"
                    anchors.left: parent.left
                    anchors.right: parent.right
                    text: segItem.kind === "para" ? segItem.text : ""
                    wrapMode: TextEdit.Wrap
                    readOnly: true
                    selectByMouse: true
                    color: root.textPrimary
                    font.pixelSize: 15
                    font.family: "Charter, Georgia, serif"
                    textFormat: TextEdit.MarkdownText
                    selectionColor: root.accent
                    selectedTextColor: "white"

                    // === WRITING-TOOLS ADDITION START ===
                    // Show the floating Writing-Tools popover whenever the
                    // user has highlighted ≥1 char inside this paragraph.
                    // We watch selectionStart/selectionEnd, which fire on
                    // every drag step and on keyboard selection too.
                    onSelectionStartChanged: paraEdit._maybeShowTools()
                    onSelectionEndChanged:   paraEdit._maybeShowTools()
                    function _maybeShowTools() {
                        if (selectionStart !== selectionEnd) {
                            root._showWritingTools(paraEdit)
                        }
                    }
                    // === WRITING-TOOLS ADDITION END ===
                }

                // -------- fenced code card -----------------------------------
                Rectangle {
                    id: codeCard
                    visible: segItem.kind === "code"
                    anchors.left: parent.left
                    anchors.right: parent.right
                    color: root.codeCardBg
                    radius: 8
                    border.color: root.codeCardBdr
                    border.width: 1
                    implicitHeight: codeCol.implicitHeight + 20

                    ColumnLayout {
                        id: codeCol
                        anchors.fill: parent
                        anchors.margins: 10
                        spacing: 6

                        // Header row — language label + copy button
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 8
                            Label {
                                text: segItem.lang ? segItem.lang.toLowerCase() : "code"
                                color: root.codeCardSub
                                font.pixelSize: 11
                                font.family: "Menlo, Monaco, monospace"
                                font.weight: Font.Medium
                            }
                            Label {
                                visible: !segItem.closed
                                text: "· streaming"
                                color: root.codeCardSub
                                font.pixelSize: 10
                                font.italic: true
                            }
                            Item { Layout.fillWidth: true }
                            // Copy button — only when the fence has closed,
                            // so we never copy a half-written block.
                            Rectangle {
                                id: copyBtn
                                visible: segItem.closed
                                Layout.preferredHeight: 22
                                Layout.preferredWidth: copyRow.implicitWidth + 12
                                radius: 4
                                color: copyArea.containsMouse ? "#2d2c2a" : "transparent"
                                border.color: copyArea.containsMouse ? "#3d3c3a" : "#2d2c2a"
                                border.width: 1
                                Behavior on color { ColorAnimation { duration: 100 } }

                                property bool justCopied: false
                                Timer {
                                    id: copiedReset
                                    interval: 1200
                                    onTriggered: copyBtn.justCopied = false
                                }

                                RowLayout {
                                    id: copyRow
                                    anchors.centerIn: parent
                                    spacing: 4
                                    Label {
                                        text: copyBtn.justCopied ? "✓" : "⧉"
                                        color: copyBtn.justCopied ? "#7fc97f" : root.codeCardSub
                                        font.pixelSize: 11
                                    }
                                    Label {
                                        text: copyBtn.justCopied ? "Copied" : "Copy"
                                        color: copyBtn.justCopied ? "#7fc97f" : root.codeCardSub
                                        font.pixelSize: 11
                                        font.weight: Font.Medium
                                    }
                                }
                                MouseArea {
                                    id: copyArea
                                    anchors.fill: parent
                                    hoverEnabled: true
                                    cursorShape: Qt.PointingHandCursor
                                    onClicked: {
                                        root._copy(segItem.text)
                                        copyBtn.justCopied = true
                                        copiedReset.restart()
                                    }
                                }
                            }
                        }

                        // Code body — RichText so the (optional) keyword
                        // highlighter can colour spans. Falls back to plain
                        // escaped text when the language is unknown.
                        TextEdit {
                            Layout.fillWidth: true
                            readOnly: true
                            selectByMouse: true
                            wrapMode: TextEdit.Wrap
                            textFormat: TextEdit.RichText
                            text: segItem.kind === "code"
                                ? ("<pre style=\"margin:0;font-family:Menlo,Monaco,Consolas,monospace;font-size:13px;color:"
                                   + root.codeCardFg + ";white-space:pre-wrap\">"
                                   + root._highlight(segItem.lang, segItem.text)
                                   + "</pre>")
                                : ""
                            color: root.codeCardFg
                            selectionColor: root.accent
                            selectedTextColor: "white"
                            font.family: "Menlo, Monaco, Consolas, monospace"
                            font.pixelSize: 13
                        }
                    }
                }

                // -------- markdown table + inline chart card ----------------
                Rectangle {
                    id: tableCard
                    visible: segItem.kind === "table"
                    anchors.left: parent.left
                    anchors.right: parent.right
                    color: "transparent"
                    radius: 8
                    border.color: Theme.border
                    border.width: 1
                    implicitHeight: tableCol.implicitHeight + 16

                    // Lazy parse — only run when the segment is a table.
                    property var analysis: segItem.kind === "table"
                        ? root._analyzeTable(segItem.tableJson, segItem.chartHint)
                        : null

                    ColumnLayout {
                        id: tableCol
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.top: parent.top
                        anchors.margins: 8
                        spacing: 6

                        // ----- styled table -----
                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 0

                            // Header row (bold)
                            RowLayout {
                                Layout.fillWidth: true
                                spacing: 0
                                Repeater {
                                    model: tableCard.analysis ? tableCard.analysis.headers : []
                                    delegate: Rectangle {
                                        Layout.fillWidth: true
                                        Layout.preferredHeight: 28
                                        color: Theme.surfaceSubtle
                                        border.color: Theme.border
                                        border.width: 1
                                        Label {
                                            anchors.fill: parent
                                            anchors.leftMargin: 8
                                            anchors.rightMargin: 8
                                            verticalAlignment: Text.AlignVCenter
                                            text: String(modelData)
                                            font.bold: true
                                            font.pixelSize: 13
                                            color: Theme.textPrimary
                                            elide: Text.ElideRight
                                        }
                                    }
                                }
                            }

                            // Data rows
                            Repeater {
                                model: tableCard.analysis ? tableCard.analysis.rows : []
                                delegate: RowLayout {
                                    Layout.fillWidth: true
                                    spacing: 0
                                    property var rowData: modelData
                                    property int rowIdx: index
                                    Repeater {
                                        model: tableCard.analysis ? tableCard.analysis.headers.length : 0
                                        delegate: Rectangle {
                                            Layout.fillWidth: true
                                            Layout.preferredHeight: 26
                                            color: (rowIdx % 2 === 0)
                                                   ? Theme.surface
                                                   : Theme.surfaceSubtle
                                            border.color: Theme.border
                                            border.width: 1
                                            Label {
                                                anchors.fill: parent
                                                anchors.leftMargin: 8
                                                anchors.rightMargin: 8
                                                verticalAlignment: Text.AlignVCenter
                                                text: (rowData && rowData[index] !== undefined)
                                                      ? String(rowData[index]) : ""
                                                font.pixelSize: 13
                                                font.family: "Menlo, Monaco, Consolas, monospace"
                                                color: Theme.textPrimary
                                                elide: Text.ElideRight
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // ----- "no chart" hint when table is fully textual -----
                        // Shown only when the analysis finished and found
                        // zero numeric columns — surfaces a small grey
                        // subtitle so the user knows the table is intentional
                        // and the missing chart isn't a render glitch.
                        Label {
                            id: noChartHint
                            visible: tableCard.analysis
                                     && tableCard.analysis.numericIdxs
                                     && tableCard.analysis.numericIdxs.length === 0
                            Layout.fillWidth: true
                            Layout.topMargin: 2
                            text: "Text comparison · no chart"
                            color: Theme.textMuted
                            font.pixelSize: 11
                            font.italic: true
                            horizontalAlignment: Text.AlignLeft
                            elide: Text.ElideRight
                        }

                        // ----- inline chart (only when ≥1 numeric col) -----
                        // We strictly gate on numericIdxs.length >= 1 — if the
                        // table is entirely textual (e.g. Python vs Rust vs
                        // Go comparison with prose cells) the ChartView is
                        // fully skipped (visible:false drops it from the
                        // ColumnLayout) so we never render empty/zero-length
                        // bars below a text-only table.
                        ChartView {
                            id: chartView
                            visible: tableCard.analysis
                                     && tableCard.analysis.numericIdxs
                                     && tableCard.analysis.numericIdxs.length >= 1
                                     && tableCard.analysis.kind !== "none"
                            Layout.fillWidth: true
                            Layout.preferredHeight: 220
                            antialiasing: true
                            legend.visible: tableCard.analysis
                                            && tableCard.analysis.numericIdxs
                                            && tableCard.analysis.numericIdxs.length > 1
                            legend.alignment: Qt.AlignBottom
                            backgroundColor: Theme.surfaceSubtle
                            plotAreaColor: Theme.surfaceSubtle
                            margins.top: 8
                            margins.bottom: 8
                            margins.left: 8
                            margins.right: 8

                            Component { id: lineSeriesComp; LineSeries {} }
                            Component { id: barSeriesComp;  BarSeries  {} }
                            Component { id: barSetComp;     BarSet     {} }
                            Component { id: pieSeriesComp;  PieSeries  {} }
                            Component { id: barCatAxisComp; BarCategoryAxis {} }
                            Component { id: valueAxisComp;  ValueAxis {} }

                            Component.onCompleted: chartView._buildSeries()

                            // When the analysis changes (re-render on new
                            // streaming data), wipe and rebuild.
                            Connections {
                                target: tableCard
                                function onAnalysisChanged() { chartView._buildSeries() }
                            }

                            function _buildSeries() {
                                // Clear any prior series/axes before rebuilding
                                chartView.removeAllSeries()
                                const axes = chartView.axes.slice()
                                for (let a = 0; a < axes.length; ++a) {
                                    chartView.removeAxis(axes[a])
                                }
                                const an = tableCard.analysis
                                if (!an || an.kind === "none") return

                                const accent = Theme.accent

                                if (an.kind === "bar") {
                                    // Category axis from catIdx, numeric set
                                    // from the first numeric col.
                                    const cats = an.rows.map(r => String(
                                        an.catIdx >= 0 ? (r[an.catIdx] || "") : ""))
                                    const numCol = an.numericIdxs[0]
                                    const series = barSeriesComp.createObject(
                                        chartView, { name: String(an.headers[numCol] || "") })
                                    const set = barSetComp.createObject(series, {
                                        label: String(an.headers[numCol] || ""),
                                        color: accent
                                    })
                                    for (let r = 0; r < an.rows.length; ++r) {
                                        const v = an.parsedValues[r][numCol]
                                        set.append(isFinite(v) ? v : 0)
                                    }
                                    series.append(set)
                                    const catAxis = barCatAxisComp.createObject(chartView, {
                                        categories: cats
                                    })
                                    const valAxis = valueAxisComp.createObject(chartView, {})
                                    chartView.addAxis(catAxis, Qt.AlignBottom)
                                    chartView.addAxis(valAxis, Qt.AlignLeft)
                                    series.attachAxis(catAxis)
                                    series.attachAxis(valAxis)
                                } else if (an.kind === "line") {
                                    // Each numeric col → its own LineSeries.
                                    // X axis: row index (0..N-1) since we
                                    // can't guarantee a numeric X column.
                                    const valAxisX = valueAxisComp.createObject(chartView, {})
                                    const valAxisY = valueAxisComp.createObject(chartView, {})
                                    chartView.addAxis(valAxisX, Qt.AlignBottom)
                                    chartView.addAxis(valAxisY, Qt.AlignLeft)
                                    valAxisX.min = 0
                                    valAxisX.max = Math.max(1, an.rows.length - 1)

                                    // Palette: accent + a couple of muted
                                    // companions for multi-series.
                                    const palette = [accent, Theme.textSecondary,
                                                     Theme.accentHover, Theme.textMuted]
                                    for (let s = 0; s < an.numericIdxs.length; ++s) {
                                        const colIdx = an.numericIdxs[s]
                                        const series = lineSeriesComp.createObject(chartView, {
                                            name: String(an.headers[colIdx] || ""),
                                            color: palette[s % palette.length],
                                            width: 2
                                        })
                                        for (let r = 0; r < an.rows.length; ++r) {
                                            const v = an.parsedValues[r][colIdx]
                                            if (isFinite(v)) series.append(r, v)
                                        }
                                        series.attachAxis(valAxisX)
                                        series.attachAxis(valAxisY)
                                    }
                                } else if (an.kind === "pie") {
                                    const series = pieSeriesComp.createObject(chartView, {})
                                    const numCol = an.numericIdxs[0]
                                    for (let r = 0; r < an.rows.length; ++r) {
                                        const label = an.catIdx >= 0
                                            ? String(an.rows[r][an.catIdx] || "")
                                            : ("Row " + (r + 1))
                                        const v = an.parsedValues[r][numCol]
                                        if (isFinite(v) && v > 0) series.append(label, v)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
