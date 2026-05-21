import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import LocalyzeUI

// Copilot/Cursor-style slash-command picker. A floating popup anchored
// above (or below, when no room above) the composer input. Filters the
// canonical command list by the user's typed prefix, supports arrow-key
// navigation, Enter/Tab to insert, mouse hover/click to insert.
//
// Inserting writes the expanded template back to the target TextArea and
// places the cursor at the END of the text. Closes on Escape, after a
// selection, when the input no longer matches `^/\w*$`, or when a space
// is typed after the command.
Popup {
    id: slashMenu

    // ---- Public API ----------------------------------------------------
    // The TextArea we are picking commands for. Required.
    property var target: null
    // Current filter prefix (everything after the leading "/"). Caller is
    // expected to update this from the TextArea's onTextChanged hook.
    property string filter: ""

    // ---- Sizing / visuals ----------------------------------------------
    width: 360
    padding: 0
    // Cap at 320 px tall; ListView handles scroll for longer filtered lists.
    height: Math.min(320, contentColumn.implicitHeight + 12)
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutsideParent
    modal: false
    focus: false

    background: Rectangle {
        color: Theme.surface
        border.color: Theme.border
        border.width: 1
        radius: 8
    }

    // ---- Command catalogue --------------------------------------------
    // Single source of truth. Each entry: {name, expansion, description,
    // cursorAtEnd}. cursorAtEnd === false means the caret should land at
    // the literal end of the expansion (used for /translate so the user
    // immediately types the target language).
    readonly property var commands: [
        { name: "/explain",   expansion: "Explain in plain English what the following does:\n", description: "Explain code or a concept" },
        { name: "/summarize", expansion: "Summarize the following in 3 short bullets:\n",      description: "Quick summary" },
        { name: "/fix",       expansion: "Find the bug in this code and propose a minimal fix:\n", description: "Debug" },
        { name: "/tests",     expansion: "Write unit tests for the following:\n",              description: "Generate tests" },
        { name: "/rewrite",   expansion: "Rewrite the following more clearly:\n",              description: "Polish text" },
        { name: "/translate", expansion: "Translate the following to ",                        description: "Translate (type target language)" },
        { name: "/shorter",   expansion: "Make the following shorter without losing meaning:\n", description: "Trim" },
        { name: "/think",     expansion: "Think step-by-step before answering. Wrap your reasoning in <thought>...</thought>.\n\n", description: "Force reasoning mode" }
    ]

    // Currently filtered, displayable subset of `commands`.
    property var filteredCommands: commands
    // Index within filteredCommands.
    property int selectedIndex: 0

    onFilterChanged: _recomputeFilter()
    Component.onCompleted: _recomputeFilter()

    function _recomputeFilter() {
        const prefix = ("/" + filter).toLowerCase()
        const out = []
        for (let i = 0; i < commands.length; ++i) {
            if (commands[i].name.toLowerCase().indexOf(prefix) === 0) {
                out.push(commands[i])
            }
        }
        filteredCommands = out
        if (selectedIndex >= out.length) selectedIndex = 0
        if (selectedIndex < 0) selectedIndex = 0
    }

    // ---- Selection actions --------------------------------------------
    function moveSelection(delta) {
        if (filteredCommands.length === 0) return
        let next = selectedIndex + delta
        if (next < 0) next = filteredCommands.length - 1
        if (next >= filteredCommands.length) next = 0
        selectedIndex = next
        listView.positionViewAtIndex(next, ListView.Contain)
    }

    function acceptCurrent() {
        if (filteredCommands.length === 0 || !target) return
        const cmd = filteredCommands[selectedIndex]
        target.text = cmd.expansion
        target.cursorPosition = target.text.length
        close()
    }

    function acceptIndex(idx) {
        if (idx < 0 || idx >= filteredCommands.length || !target) return
        selectedIndex = idx
        acceptCurrent()
    }

    // ---- Layout --------------------------------------------------------
    contentItem: Item {
        id: contentColumn
        implicitWidth: 360
        implicitHeight: Math.min(320, listView.contentHeight + 12)

        ListView {
            id: listView
            anchors.fill: parent
            anchors.margins: 6
            clip: true
            model: slashMenu.filteredCommands
            currentIndex: slashMenu.selectedIndex
            interactive: true
            boundsBehavior: Flickable.StopAtBounds
            spacing: 0
            ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

            delegate: Rectangle {
                width: ListView.view ? ListView.view.width : 348
                height: 38
                radius: 6
                color: index === slashMenu.selectedIndex ? Theme.surfaceSubtle : "transparent"
                Behavior on color { ColorAnimation { duration: 80 } }

                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 10
                    anchors.rightMargin: 10
                    spacing: 10

                    Label {
                        text: modelData.name
                        color: Theme.textPrimary
                        font.pixelSize: 14
                        font.family: "Menlo, Monaco, monospace"
                        font.bold: true
                        Layout.preferredWidth: 96
                    }
                    Label {
                        text: modelData.description
                        color: Theme.textSecondary
                        font.pixelSize: 12
                        font.italic: true
                        elide: Label.ElideRight
                        Layout.fillWidth: true
                    }
                }

                MouseArea {
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onEntered: slashMenu.selectedIndex = index
                    onClicked: slashMenu.acceptIndex(index)
                }
            }

            // Empty-state row (no commands match the typed prefix).
            Label {
                anchors.centerIn: parent
                visible: slashMenu.filteredCommands.length === 0
                text: "No matching commands"
                color: Theme.textMuted
                font.pixelSize: 12
                font.italic: true
            }
        }
    }
}
