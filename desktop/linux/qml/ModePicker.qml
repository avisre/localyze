import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import LocalyzeUI

// Compact horizontal capsule strip with 7 pills:
//   Chat / Code / Data / Write / Brainstorm / Communicate / Research
// Active pill uses accent; others surfaceSubtle. Click sets modeStore.currentMode.
// Tooltip per pill. Mirrors the Android capability mode picker so the desktop
// has feature parity for switching the SystemPromptBuilder mode at runtime.
Item {
    id: root

    // ----- design tokens (pull from central Theme so dark/light + the iOS
    // green accent stay consistent with ChatView and StylePicker) -----
    readonly property color surface:         Theme.surface
    readonly property color surfaceSubtle:   Theme.surfaceSubtle
    readonly property color border:          Theme.border
    readonly property color textSecondary:   Theme.textSecondary
    readonly property color accent:          Theme.accent

    implicitWidth: row.implicitWidth + 12
    implicitHeight: 36

    readonly property var modes: [
        { id: "chat",          label: "Chat",        tip: "General conversation — balanced, friendly default" },
        { id: "code",          label: "Code",        tip: "Programming help — code blocks, debugging, refactors" },
        { id: "data",          label: "Data",        tip: "Analysis — tables, math, structured reasoning" },
        { id: "write",         label: "Write",       tip: "Long-form writing — drafts, editing, prose" },
        { id: "brainstorm",    label: "Brainstorm",  tip: "Ideation — divergent lists, options, exploration" },
        { id: "communication", label: "Communicate", tip: "Messages & emails — tone-aware, concise replies" },
        { id: "research",      label: "Research",    tip: "Deep-research mode — TL;DR + analysis + sources" }
    ]

    Rectangle {
        id: strip
        anchors.fill: parent
        radius: 22
        color: root.surface
        border.color: root.border
        border.width: 1

        RowLayout {
            id: row
            anchors.verticalCenter: parent.verticalCenter
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.leftMargin: 4
            anchors.rightMargin: 4
            spacing: 2

            Repeater {
                model: root.modes
                delegate: Rectangle {
                    id: pill
                    readonly property bool active: modeStore.currentMode === modelData.id
                    Layout.preferredHeight: 28
                    Layout.preferredWidth: pillLabel.implicitWidth + 22
                    radius: 14
                    color: pill.active
                        ? root.accent
                        : (pillArea.containsMouse ? root.surfaceSubtle : "transparent")
                    border.color: pill.active ? root.accent : "transparent"
                    border.width: 1
                    Behavior on color { ColorAnimation { duration: 120 } }

                    Label {
                        id: pillLabel
                        anchors.centerIn: parent
                        text: modelData.label
                        color: pill.active ? "white" : root.textSecondary
                        font.pixelSize: 12
                        font.weight: pill.active ? Font.Medium : Font.Normal
                    }
                    MouseArea {
                        id: pillArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: modeStore.currentMode = modelData.id
                        ToolTip.text: modelData.tip
                        ToolTip.visible: containsMouse
                        ToolTip.delay: 400
                    }
                }
            }
        }
    }
}
