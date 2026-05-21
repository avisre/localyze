import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtCharts
import LocalyzeUI

// ArtifactPanel — Claude-style right-edge slide-in workspace that hosts
// "artifacts" (code blocks, tables, charts, long docs) detected from the
// assistant's reply. Bound to a ListModel of items shaped like:
//
//   { type: "code"|"table"|"chart"|"doc",
//     title: <string>, language: <string>, content: <string> }
//
// The host (ChatView) decides when to open/close (via the `open` property)
// and supplies the model + currentIndex.
Item {
    id: panel

    // ------- contract with the host -------
    property var artifactModel: null      // ListModel of artifacts
    property int currentIndex: 0
    property bool open: false             // host toggles this

    signal closeRequested()
    signal copyRequested(string text)

    // The current artifact (an Object copied out of the model). We snapshot
    // it on index change so the body bindings don't churn while the model
    // mutates.
    property var currentItem: null
    function _refreshCurrent() {
        if (!artifactModel || artifactModel.count === 0) {
            currentItem = null
            return
        }
        if (currentIndex < 0) currentIndex = 0
        if (currentIndex >= artifactModel.count) currentIndex = artifactModel.count - 1
        const it = artifactModel.get(currentIndex)
        currentItem = {
            type: it.type || "code",
            title: it.title || "",
            language: it.language || "",
            content: it.content || ""
        }
    }
    onCurrentIndexChanged: _refreshCurrent()
    onArtifactModelChanged: _refreshCurrent()
    Connections {
        target: panel.artifactModel
        ignoreUnknownSignals: true
        function onCountChanged() { panel._refreshCurrent() }
    }
    Component.onCompleted: _refreshCurrent()

    // ------- slide-in geometry -------
    // Width = 45% of parent (capped at 560). When closed we park ourselves
    // past the right edge so the smooth slide hides cleanly.
    width: Math.min((parent ? parent.width : 800) * 0.45, 560)
    height: parent ? parent.height : 0
    x: open ? (parent ? parent.width - width : 0) : (parent ? parent.width : 0)
    visible: x < (parent ? parent.width : 0)
    Behavior on x { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }

    // Off-screen clipboard helper.
    TextEdit { id: clip; visible: false }
    function _copy(text) {
        clip.text = text
        clip.selectAll()
        clip.copy()
        panel.copyRequested(text)
    }

    // ------- background card -------
    Rectangle {
        anchors.fill: parent
        color: Theme.surface
        border.color: Theme.border
        border.width: 0
        // Left hairline to set the panel apart from the chat column.
        Rectangle {
            anchors.left: parent.left; anchors.top: parent.top; anchors.bottom: parent.bottom
            width: 1; color: Theme.border
        }

        ColumnLayout {
            anchors.fill: parent
            anchors.margins: 0
            spacing: 0

            // ------------------- header -------------------
            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: 48
                color: Theme.surface
                Rectangle {
                    anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
                    height: 1; color: Theme.border
                }
                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 14
                    anchors.rightMargin: 8
                    spacing: 8

                    Label {
                        text: "📐"
                        font.pixelSize: 14
                        color: Theme.textSecondary
                    }
                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 0
                        Label {
                            text: panel.currentItem
                                  ? (panel.currentItem.title && panel.currentItem.title.length > 0
                                     ? panel.currentItem.title
                                     : panel._defaultTitle(panel.currentItem))
                                  : "Artifacts"
                            color: Theme.textPrimary
                            font.pixelSize: 13
                            font.weight: Font.Medium
                            elide: Label.ElideRight
                            Layout.fillWidth: true
                        }
                        Label {
                            visible: panel.artifactModel && panel.artifactModel.count > 0
                            text: panel.artifactModel
                                  ? (panel.currentIndex + 1) + " of " + panel.artifactModel.count
                                    + (panel.currentItem ? "  ·  " + panel.currentItem.type : "")
                                  : ""
                            color: Theme.textMuted
                            font.pixelSize: 11
                        }
                    }

                    ToolButton {
                        text: "‹"
                        enabled: panel.artifactModel && panel.currentIndex > 0
                        onClicked: panel.currentIndex = Math.max(0, panel.currentIndex - 1)
                        ToolTip.text: "Previous artifact"
                        ToolTip.visible: hovered
                        ToolTip.delay: 400
                        font.pixelSize: 18
                        Layout.preferredHeight: 32
                        Layout.preferredWidth: 28
                    }
                    ToolButton {
                        text: "›"
                        enabled: panel.artifactModel
                                 && panel.currentIndex < panel.artifactModel.count - 1
                        onClicked: panel.currentIndex = Math.min(
                            panel.artifactModel.count - 1, panel.currentIndex + 1)
                        ToolTip.text: "Next artifact"
                        ToolTip.visible: hovered
                        ToolTip.delay: 400
                        font.pixelSize: 18
                        Layout.preferredHeight: 32
                        Layout.preferredWidth: 28
                    }
                    ToolButton {
                        text: "✕"
                        onClicked: panel.closeRequested()
                        ToolTip.text: "Close panel (Ctrl+E)"
                        ToolTip.visible: hovered
                        ToolTip.delay: 400
                        font.pixelSize: 13
                        Layout.preferredHeight: 32
                        Layout.preferredWidth: 32
                    }
                }
            }

            // ------------------- body -------------------
            Item {
                Layout.fillWidth: true
                Layout.fillHeight: true

                // Empty state
                ColumnLayout {
                    anchors.centerIn: parent
                    spacing: 8
                    visible: !panel.currentItem
                    Label {
                        Layout.alignment: Qt.AlignHCenter
                        text: "No artifacts yet."
                        color: Theme.textSecondary
                        font.pixelSize: 14
                    }
                    Label {
                        Layout.alignment: Qt.AlignHCenter
                        text: "Large code blocks, tables and charts will appear here."
                        color: Theme.textMuted
                        font.pixelSize: 12
                    }
                }

                Loader {
                    id: bodyLoader
                    anchors.fill: parent
                    active: panel.currentItem !== null
                    sourceComponent: {
                        if (!panel.currentItem) return null
                        const t = panel.currentItem.type
                        if (t === "code")  return codeComp
                        if (t === "table") return tableComp
                        if (t === "chart") return chartComp
                        if (t === "doc")   return docComp
                        return codeComp
                    }
                }
            }
        }
    }

    // -------- default title helper --------
    function _defaultTitle(item) {
        if (!item) return "Artifact"
        if (item.type === "code")  return (item.language || "code") + " block"
        if (item.type === "table") return "Table"
        if (item.type === "chart") return "Chart"
        if (item.type === "doc")   return "Document"
        return "Artifact"
    }

    // ========================== components ==========================

    // ----- code: dark card, monospace, scrollable, with Copy button -----
    Component {
        id: codeComp
        Item {
            anchors.fill: parent
            ColumnLayout {
                anchors.fill: parent
                anchors.margins: 14
                spacing: 8

                RowLayout {
                    Layout.fillWidth: true
                    spacing: 8
                    Label {
                        text: (panel.currentItem && panel.currentItem.language)
                              ? panel.currentItem.language.toLowerCase()
                              : "code"
                        color: Theme.textMuted
                        font.pixelSize: 11
                        font.family: "Menlo, Monaco, monospace"
                        font.weight: Font.Medium
                    }
                    Item { Layout.fillWidth: true }
                    Button {
                        id: copyBtn
                        property bool justCopied: false
                        text: justCopied ? "✓ Copied" : "⧉ Copy"
                        font.pixelSize: 11
                        onClicked: {
                            if (panel.currentItem) panel._copy(panel.currentItem.content)
                            copyBtn.justCopied = true
                            copyTimer.restart()
                        }
                        Timer {
                            id: copyTimer; interval: 1200
                            onTriggered: copyBtn.justCopied = false
                        }
                    }
                }

                Rectangle {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    color: Theme.codeBg
                    radius: 8
                    border.color: Theme.border
                    border.width: 1

                    ScrollView {
                        anchors.fill: parent
                        anchors.margins: 10
                        clip: true
                        TextArea {
                            text: panel.currentItem ? panel.currentItem.content : ""
                            readOnly: true
                            selectByMouse: true
                            wrapMode: TextArea.NoWrap
                            color: Theme.textPrimary
                            background: null
                            font.family: "Menlo, Monaco, Consolas, monospace"
                            font.pixelSize: 13
                        }
                    }
                }
            }
        }
    }

    // ----- table: styled grid, parses markdown table into rows -----
    Component {
        id: tableComp
        Item {
            anchors.fill: parent
            // Parse a markdown table into { headers: [...], rows: [[...], ...] }.
            // Forgiving: skips the alignment row, drops empty leading/trailing cells.
            function _parseMdTable(md) {
                if (!md) return { headers: [], rows: [] }
                const lines = md.split("\n").filter(l => l.trim().length > 0 && l.indexOf("|") >= 0)
                if (lines.length === 0) return { headers: [], rows: [] }
                const split = (line) => {
                    let parts = line.split("|")
                    if (parts.length > 0 && parts[0].trim() === "") parts.shift()
                    if (parts.length > 0 && parts[parts.length - 1].trim() === "") parts.pop()
                    return parts.map(s => s.trim())
                }
                const headers = split(lines[0])
                let start = 1
                if (lines.length > 1 && /^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$/.test(lines[1])) {
                    start = 2
                }
                const rows = []
                for (let i = start; i < lines.length; ++i) rows.push(split(lines[i]))
                return { headers: headers, rows: rows }
            }

            property var parsed: panel.currentItem ? _parseMdTable(panel.currentItem.content) : { headers: [], rows: [] }
            property real colWidth: Math.max(110,
                (width - 28 - (parsed.headers.length * 8)) / Math.max(1, parsed.headers.length))

            ScrollView {
                anchors.fill: parent
                anchors.margins: 14
                clip: true
                contentWidth: availableWidth

                ColumnLayout {
                    width: parent ? parent.width : 0
                    spacing: 1

                    // header
                    Rectangle {
                        Layout.fillWidth: true
                        Layout.preferredHeight: hdrRow.implicitHeight + 14
                        color: Theme.surfaceSubtle
                        radius: 6
                        Row {
                            id: hdrRow
                            anchors.left: parent.left
                            anchors.verticalCenter: parent.verticalCenter
                            anchors.leftMargin: 8
                            spacing: 8
                            Repeater {
                                model: parsed.headers
                                delegate: Label {
                                    width: colWidth
                                    elide: Text.ElideRight
                                    text: String(modelData)
                                    color: Theme.textPrimary
                                    font.pixelSize: 12
                                    font.weight: Font.Bold
                                }
                            }
                        }
                    }
                    // rows
                    Repeater {
                        model: parsed.rows
                        delegate: Rectangle {
                            required property int index
                            required property var modelData
                            Layout.fillWidth: true
                            Layout.preferredHeight: dataRow.implicitHeight + 12
                            color: (index % 2 === 0) ? Theme.surface : Theme.surfaceSubtle
                            Row {
                                id: dataRow
                                anchors.left: parent.left
                                anchors.verticalCenter: parent.verticalCenter
                                anchors.leftMargin: 8
                                spacing: 8
                                property var rowData: modelData
                                Repeater {
                                    model: parsed.headers.length
                                    delegate: Label {
                                        width: colWidth
                                        elide: Text.ElideRight
                                        text: String((dataRow.rowData && dataRow.rowData[index]) || "")
                                        color: Theme.textPrimary
                                        font.pixelSize: 12
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ----- chart: parse markdown table → ChartView -----
    Component {
        id: chartComp
        Item {
            anchors.fill: parent

            // Same parser as the table component — chart artifacts are usually
            // produced from a small two-column markdown table or a "label: value"
            // list in the assistant reply.
            function _parseData(md) {
                if (!md) return { labels: [], values: [] }
                const lines = md.split("\n")
                const labels = []
                const values = []
                // Try markdown table first.
                const tableLines = lines.filter(l => l.indexOf("|") >= 0 && l.trim().length > 0)
                if (tableLines.length >= 2) {
                    const split = (line) => {
                        let parts = line.split("|")
                        if (parts.length > 0 && parts[0].trim() === "") parts.shift()
                        if (parts.length > 0 && parts[parts.length - 1].trim() === "") parts.pop()
                        return parts.map(s => s.trim())
                    }
                    let start = 1
                    if (tableLines.length > 1 && /^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$/.test(tableLines[1])) {
                        start = 2
                    }
                    for (let i = start; i < tableLines.length; ++i) {
                        const cols = split(tableLines[i])
                        if (cols.length >= 2) {
                            const v = parseFloat(cols[1].replace(/[^0-9.\-]/g, ""))
                            if (!isNaN(v)) {
                                labels.push(cols[0])
                                values.push(v)
                            }
                        }
                    }
                }
                // Fallback — "label: value" lines.
                if (labels.length === 0) {
                    for (let i = 0; i < lines.length; ++i) {
                        const m = lines[i].match(/^\s*[-*]?\s*([^:]+?)\s*:\s*([\-0-9.]+)/)
                        if (m) {
                            const v = parseFloat(m[2])
                            if (!isNaN(v)) {
                                labels.push(m[1].trim())
                                values.push(v)
                            }
                        }
                    }
                }
                return { labels: labels, values: values }
            }

            property var data: panel.currentItem ? _parseData(panel.currentItem.content) : { labels: [], values: [] }

            ChartView {
                anchors.fill: parent
                anchors.margins: 8
                antialiasing: true
                legend.visible: false
                backgroundColor: Theme.surface
                titleColor: Theme.textPrimary
                title: panel.currentItem && panel.currentItem.title
                       ? panel.currentItem.title : ""
                Component.onCompleted: {
                    if (!data || data.values.length === 0) return
                    const set = setComp.createObject(this)
                    for (let i = 0; i < data.values.length; ++i) set.append(data.values[i])
                    const series = seriesComp.createObject(this)
                    series.append(set)
                    const axisX = axisXComp.createObject(this, { categories: data.labels })
                    series.attachAxis(axisX)
                    const axisY = axisYComp.createObject(this)
                    series.attachAxis(axisY)
                }
                Component { id: seriesComp; BarSeries {} }
                Component { id: setComp;    BarSet    {} }
                Component { id: axisXComp;  BarCategoryAxis {} }
                Component { id: axisYComp;  ValueAxis {} }
            }
        }
    }

    // ----- doc: long markdown render with a small table of contents -----
    Component {
        id: docComp
        Item {
            anchors.fill: parent

            // Collect the markdown headings for the TOC.
            function _toc(md) {
                const out = []
                if (!md) return out
                const lines = md.split("\n")
                for (let i = 0; i < lines.length; ++i) {
                    const m = lines[i].match(/^(#{1,6})\s+(.+?)\s*#*\s*$/)
                    if (m) out.push({ level: m[1].length, title: m[2] })
                }
                return out
            }

            property var headings: panel.currentItem ? _toc(panel.currentItem.content) : []

            ColumnLayout {
                anchors.fill: parent
                anchors.margins: 14
                spacing: 8

                // TOC
                Rectangle {
                    visible: headings.length > 0
                    Layout.fillWidth: true
                    Layout.preferredHeight: tocCol.implicitHeight + 16
                    color: Theme.surfaceSubtle
                    radius: 6
                    border.color: Theme.border
                    border.width: 1
                    ColumnLayout {
                        id: tocCol
                        anchors.fill: parent
                        anchors.margins: 8
                        spacing: 2
                        Label {
                            text: "Contents"
                            color: Theme.textSecondary
                            font.pixelSize: 11
                            font.weight: Font.Medium
                        }
                        Repeater {
                            model: headings
                            delegate: Label {
                                required property var modelData
                                text: Array(modelData.level).join("  ") + "• " + modelData.title
                                color: Theme.textPrimary
                                font.pixelSize: 12
                                elide: Label.ElideRight
                                Layout.fillWidth: true
                            }
                        }
                    }
                }

                // Body
                ScrollView {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    clip: true
                    contentWidth: availableWidth
                    TextArea {
                        readOnly: true
                        selectByMouse: true
                        wrapMode: TextArea.Wrap
                        textFormat: TextEdit.MarkdownText
                        text: panel.currentItem ? panel.currentItem.content : ""
                        color: Theme.textPrimary
                        background: null
                        font.family: "Charter, Georgia, serif"
                        font.pixelSize: 14
                    }
                }
            }
        }
    }
}
