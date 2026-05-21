import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import LocalyzeUI

// Compact pill (32px) that opens a Popup with 5 stacked style options:
//   Default · Concise · Explanatory · Formal · Creative
// Click sets styleStore.currentStyle. The selected style is reflected in
// the pill label "Style: <current>". A one-line addendum is then appended
// to the system prompt at LlamaCppBackend::generate().
Item {
    id: root

    implicitWidth: pill.implicitWidth
    implicitHeight: 32

    readonly property var styles: [
        { id: "default",     label: "Default",     desc: "No tone change — baseline behavior." },
        { id: "concise",     label: "Concise",     desc: "Tight, max 3 sentences, no preamble." },
        { id: "explanatory", label: "Explanatory", desc: "Worked examples, bullets, defined jargon." },
        { id: "formal",      label: "Formal",      desc: "Full sentences, no contractions or emojis." },
        { id: "creative",    label: "Creative",    desc: "Vivid voice, metaphors, memorable opener." }
    ]

    function _labelFor(id) {
        for (let i = 0; i < styles.length; ++i)
            if (styles[i].id === id) return styles[i].label
        return "Default"
    }

    // ------------------- pill -------------------
    Rectangle {
        id: pill
        anchors.fill: parent
        radius: 16
        color: pillArea.containsMouse ? Theme.surfaceSubtle : Theme.surface
        border.color: popup.opened ? Theme.borderStrong : Theme.border
        border.width: 1
        implicitWidth: pillRow.implicitWidth + 22
        Behavior on color        { ColorAnimation { duration: 120 } }
        Behavior on border.color { ColorAnimation { duration: 120 } }

        RowLayout {
            id: pillRow
            anchors.centerIn: parent
            spacing: 6

            Label {
                text: "Style: " + root._labelFor(styleStore.currentStyle)
                color: styleStore.currentStyle === "default"
                       ? Theme.textSecondary
                       : Theme.accent
                font.pixelSize: 12
                font.weight: styleStore.currentStyle === "default"
                             ? Font.Normal
                             : Font.Medium
            }
            Label {
                text: popup.opened ? "▲" : "▼"
                color: Theme.textSecondary
                font.pixelSize: 9
            }
        }

        MouseArea {
            id: pillArea
            anchors.fill: parent
            hoverEnabled: true
            cursorShape: Qt.PointingHandCursor
            onClicked: popup.opened ? popup.close() : popup.open()
            ToolTip.text: "Response style (per-conversation)"
            ToolTip.visible: containsMouse && !popup.opened
            ToolTip.delay: 400
        }
    }

    // ------------------- popup -------------------
    Popup {
        id: popup
        y: pill.height + 4
        x: 0
        padding: 4
        modal: false
        focus: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutsideParent | Popup.CloseOnPressOutside

        background: Rectangle {
            color: Theme.surface
            border.color: Theme.border
            border.width: 1
            radius: 10
        }

        contentItem: ColumnLayout {
            spacing: 2

            Repeater {
                model: root.styles
                delegate: Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredWidth: 220
                    Layout.preferredHeight: 42
                    radius: 6
                    readonly property bool selected: styleStore.currentStyle === modelData.id
                    color: selected
                           ? Qt.rgba(Theme.accent.r, Theme.accent.g, Theme.accent.b, 0.12)
                           : (rowArea.containsMouse ? Theme.surfaceSubtle : "transparent")
                    Behavior on color { ColorAnimation { duration: 100 } }

                    ColumnLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 10
                        anchors.rightMargin: 10
                        anchors.topMargin: 4
                        anchors.bottomMargin: 4
                        spacing: 1

                        Label {
                            text: modelData.label
                            color: parent.parent.selected ? Theme.accent : Theme.textPrimary
                            font.pixelSize: 13
                            font.weight: parent.parent.selected ? Font.Medium : Font.Normal
                            Layout.fillWidth: true
                            elide: Label.ElideRight
                        }
                        Label {
                            text: modelData.desc
                            color: Theme.textMuted
                            font.pixelSize: 10
                            Layout.fillWidth: true
                            elide: Label.ElideRight
                        }
                    }

                    MouseArea {
                        id: rowArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            styleStore.currentStyle = modelData.id
                            popup.close()
                        }
                    }
                }
            }
        }
    }
}
