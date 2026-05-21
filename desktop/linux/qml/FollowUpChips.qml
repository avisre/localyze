import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import LocalyzeUI

// Follow-up suggestion chips (Claude-style). Renders up to 3 pill-shaped
// clickable suggestions derived from the assistant message content.
// Click → emits selected(text) so the parent can auto-fill the composer
// and send the chosen follow-up.
Item {
    id: chipsRoot

    // ---- public API ----
    property string content: ""
    property var    model: _computeModel(content)
    signal selected(string text)

    implicitHeight: flow.implicitHeight
    implicitWidth: flow.implicitWidth

    opacity: model.length > 0 ? 1 : 0
    visible: model.length > 0
    Behavior on opacity { NumberAnimation { duration: 140 } }

    // ---- heuristic: produce up to 3 context-aware suggestions ----
    function _computeModel(c) {
        const out = []
        const add = (s) => { if (s && out.indexOf(s) === -1 && out.length < 3) out.push(s) }
        const text = (c || "").toString()
        if (text.length === 0) return out

        const lower = text.toLowerCase()

        // Markdown table with numbers → chart
        const tableMatch = text.match(/(^|\n)\s*\|[^\n]*\|[^\n]*\n\s*\|[\s\-:|]+\|/)
        const hasTable = !!tableMatch
        const hasNumbersInTable = hasTable && /\|\s*-?\d[\d,.]*\s*\|/.test(text)
        if (hasNumbersInTable) add("Show as a chart")

        // Fenced code block
        const hasCode = /```[\s\S]*?```/.test(text) || /(^|\n) {4,}\S/.test(text)
        if (hasCode) {
            add("Add tests for this")
            add("Explain line-by-line")
        }

        // Numbered list (e.g. "1." or "1)" at line start, two or more)
        const numberedMatches = text.match(/(^|\n)\s*\d+[.)]\s+\S/g) || []
        if (numberedMatches.length >= 2) add("Make a step-by-step plan")

        // Dates / years
        const hasDates = /\b(19|20)\d{2}\b/.test(text)
            || /\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\s+\d{1,2}\b/i.test(text)
        if (hasDates) add("Show as a timeline")

        // Multiple options mentioned
        const hasOptions = /\b(option|choice|alternative|either|or)\b/i.test(text)
            || (text.match(/\b(vs\.?|versus)\b/i) !== null)
        if (hasOptions) add("Compare pros and cons")

        // Short content
        if (text.length < 200) add("Tell me more")

        // "for example"
        if (/for example/i.test(text)) add("Give 3 more examples")

        // City / place name
        const place = _detectPlace(text)
        if (place) add("What about " + _otherPlace(place))

        // Generic fallback
        if (out.length === 0) {
            add("Tell me more")
            add("Why?")
            add("What's the alternative?")
        }

        return out.slice(0, 3)
    }

    // Tiny built-in city list — enough to fire the "What about <other>" chip
    // without needing a model call. Case-insensitive word-boundary match.
    function _detectPlace(text) {
        const cities = [
            "London", "Paris", "New York", "Tokyo", "Berlin", "Madrid",
            "Rome", "Mumbai", "Delhi", "Bangalore", "Sydney", "Toronto",
            "San Francisco", "Seattle", "Chicago", "Boston", "Singapore",
            "Hong Kong", "Dubai", "Amsterdam", "Barcelona", "Lisbon",
            "Vienna", "Prague", "Moscow", "Beijing", "Shanghai", "Seoul",
            "Bangkok", "Istanbul", "Cairo", "Cape Town", "Mexico City",
            "Buenos Aires", "Rio de Janeiro", "Los Angeles", "Vancouver"
        ]
        for (let i = 0; i < cities.length; ++i) {
            const c = cities[i]
            const re = new RegExp("\\b" + c.replace(/ /g, "\\s+") + "\\b", "i")
            if (re.test(text)) return c
        }
        return ""
    }

    function _otherPlace(p) {
        // Pick a different anchor city so the chip suggestion is meaningful.
        const pool = ["Tokyo", "Paris", "New York", "Berlin", "Sydney", "London"]
        for (let i = 0; i < pool.length; ++i) {
            if (pool[i].toLowerCase() !== p.toLowerCase()) return pool[i]
        }
        return "another city"
    }

    Flow {
        id: flow
        anchors.left: parent.left
        anchors.right: parent.right
        spacing: 8

        Repeater {
            model: chipsRoot.model
            delegate: Rectangle {
                id: chip
                radius: 18
                height: chipText.implicitHeight + 12   // 6px top + 6px bottom
                width:  chipText.implicitWidth + 28    // 14px left + 14px right
                color: chipMouse.containsMouse ? Theme.surfaceSubtle : Theme.surface
                border.color: Theme.border
                border.width: 1
                Behavior on color { ColorAnimation { duration: 120 } }

                Label {
                    id: chipText
                    anchors.centerIn: parent
                    text: modelData
                    color: Theme.textSecondary
                    font.pixelSize: 13
                }

                MouseArea {
                    id: chipMouse
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: chipsRoot.selected(modelData)
                }
            }
        }
    }
}
