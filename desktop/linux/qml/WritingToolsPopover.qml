import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import LocalyzeUI

// WritingToolsPopover — Apple-Intelligence-style selection-to-action menu.
//
// When the user selects text in an assistant message segment, MessageBody
// shows this Popup near the selection. Six small buttons (Rewrite, Friendly,
// Professional, Concise, Summarize, Bullets) emit `actionRequested(prompt)`
// when clicked. The host (ChatView via MessageBody) takes that prompt and
// stuffs it into the composer, then calls _send().
//
// The popover closes when the user clicks outside it or presses Esc.
Popup {
    id: popover

    // Currently selected text, set by the host before opening.
    property string selectedText: ""

    // Emitted when an action button is clicked. `prompt` already has the
    // selection text substituted in, so the host can just send it.
    signal actionRequested(string prompt)

    // Popover styling — small, frameless, floating card with drop shadow.
    padding: 0
    modal: false
    focus: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutsideParent

    implicitWidth: 520
    implicitHeight: 38

    background: Rectangle {
        color: Theme.surface
        border.color: Theme.border
        border.width: 1
        radius: 10
        // Faux drop shadow with a slightly-offset darker rectangle behind.
        // QtQuick.Effects isn't reliably present across builds; this keeps
        // the popover dependency-free and matches the requested visual.
        Rectangle {
            anchors.fill: parent
            anchors.topMargin: 4
            anchors.leftMargin: 0
            z: -1
            color: "transparent"
            radius: 10
            border.color: Qt.rgba(0, 0, 0, 0.12)
            border.width: 1
        }
    }

    // ----- the six action templates (one-line prompt prefixes) -----
    // Each item: { icon, label, template }. The template must contain "{sel}"
    // which gets replaced with the user's selected text before emission.
    readonly property var _actions: [
        { icon: "✎",  label: "Rewrite",      tpl: "Rewrite the following more clearly:\n\n{sel}" },
        { icon: "\u{1F60A}", label: "Friendly",   tpl: "Rewrite the following in a warm, friendly tone:\n\n{sel}" },
        { icon: "\u{1F4BC}", label: "Professional", tpl: "Rewrite the following in a professional, polished tone:\n\n{sel}" },
        { icon: "✂",  label: "Concise",      tpl: "Make the following more concise — cut redundant words:\n\n{sel}" },
        { icon: "\u{1F4CB}", label: "Summarize",  tpl: "Summarize the following in 1-2 sentences:\n\n{sel}" },
        { icon: "•",  label: "Bullets",      tpl: "Convert the following into 3-5 short bullet points:\n\n{sel}" }
    ]

    contentItem: RowLayout {
        spacing: 0
        anchors.fill: parent

        Repeater {
            model: popover._actions
            delegate: Rectangle {
                id: btn
                required property int index
                required property var modelData
                Layout.fillWidth: true
                Layout.fillHeight: true
                color: hover.hovered ? Theme.surfaceSubtle : "transparent"
                radius: 8

                HoverHandler {
                    id: hover
                    cursorShape: Qt.PointingHandCursor
                }
                TapHandler {
                    onTapped: {
                        const prompt = String(btn.modelData.tpl).replace(
                            "{sel}", popover.selectedText)
                        popover.actionRequested(prompt)
                        popover.close()
                    }
                }

                RowLayout {
                    anchors.centerIn: parent
                    spacing: 4
                    Label {
                        text: btn.modelData.icon
                        color: Theme.textPrimary
                        font.pixelSize: 12
                    }
                    Label {
                        text: btn.modelData.label
                        color: Theme.textPrimary
                        font.pixelSize: 12
                        font.weight: Font.Medium
                    }
                }
            }
        }
    }
}
