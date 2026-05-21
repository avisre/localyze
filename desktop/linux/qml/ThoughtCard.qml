import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import LocalyzeUI

// ThoughtCard — Claude-style collapsible "Thinking…" / "Reasoning" panel.
//
// Renders a faint rounded card with a chevron header. Collapsed by default;
// click the header (or chevron) to expand the full thought text. The height
// is animated for a smooth open/close transition.
//
// API:
//   thoughtText   : string  — the inner reasoning text (already stripped of
//                              <thought>...</thought> tags by the caller).
//   elapsedHint   : string  — optional ("3s", "12s"). If empty, we just show
//                              "Reasoning" as the title.
//
// Visual style follows the Theme.* singleton so the card adapts to light /
// dark mode along with the rest of the app.
Item {
    id: root

    property string thoughtText: ""
    property string elapsedHint: ""
    property bool   expanded:    false

    // Width is driven by the parent layout; the height is computed from
    // either just the header (collapsed) or header + body (expanded).
    readonly property int collapsedHeight: 36
    readonly property int bodyPadding:     12
    Layout.fillWidth: true
    Layout.preferredHeight: card.implicitHeight

    Rectangle {
        id: card
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        radius: 10
        color: Theme.codeBg
        border.color: Theme.border
        border.width: 1

        // Height animates between collapsed and expanded states.
        implicitHeight: root.expanded
            ? (header.height + bodyText.implicitHeight + root.bodyPadding * 2)
            : header.height

        Behavior on implicitHeight {
            NumberAnimation { duration: 160; easing.type: Easing.OutQuad }
        }

        // ---- Header row (always visible; toggles expansion on click) ----
        Item {
            id: header
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            height: 36

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 12
                anchors.rightMargin: 12
                spacing: 8

                Label {
                    text: "✦"
                    color: Theme.textSecondary
                    font.pixelSize: 12
                }
                Label {
                    text: root.elapsedHint && root.elapsedHint.length > 0
                        ? ("Thought for " + root.elapsedHint)
                        : "Reasoning"
                    color: Theme.textSecondary
                    font.pixelSize: 12
                    font.weight: Font.Medium
                }
                Item { Layout.fillWidth: true }
                Label {
                    id: chevron
                    text: root.expanded ? "▾" : "▸"
                    color: Theme.textSecondary
                    font.pixelSize: 12
                }
            }

            MouseArea {
                anchors.fill: parent
                hoverEnabled: true
                cursorShape: Qt.PointingHandCursor
                onClicked: root.expanded = !root.expanded
            }
        }

        // ---- Body (visible only when expanded) ----
        Label {
            id: bodyText
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: header.bottom
            anchors.leftMargin: root.bodyPadding
            anchors.rightMargin: root.bodyPadding
            anchors.topMargin: 4
            text: root.thoughtText
            color: Theme.textMuted
            font.pixelSize: 12
            font.family: "Menlo, Monaco, monospace"
            wrapMode: Label.Wrap
            visible: root.expanded
            opacity: root.expanded ? 1.0 : 0.0
            Behavior on opacity { NumberAnimation { duration: 140 } }
        }
    }
}
