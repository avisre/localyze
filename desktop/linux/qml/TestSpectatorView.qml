import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Live view of /tmp/qrepo/answered.jsonl — every entry the parallel tester
// agents drop in lands here. The user can watch the model being graded in
// real time without leaving the app.
Page {
    id: root

    readonly property color bgColor:         "#faf9f5"
    readonly property color surface:         "#ffffff"
    readonly property color surfaceSubtle:   "#f0eee6"
    readonly property color border:          "#e5e1d6"
    readonly property color textPrimary:     "#1c1b1a"
    readonly property color textSecondary:   "#6b6960"
    readonly property color textMuted:       "#9a9788"
    readonly property color accent:          "#cc785c"
    readonly property color passColor:       "#4a7a3a"
    readonly property color partialColor:    "#b87a2a"
    readonly property color failColor:       "#a23a3a"

    background: Rectangle { color: root.bgColor }

    property int passCount: 0
    property int partialCount: 0
    property int failCount: 0
    property string filterGrade: "all"    // all|pass|partial|fail
    property string filterTester: "all"   // all|research|chat

    Component.onCompleted: {
        testSpectator.replayAll()
    }

    Connections {
        target: testSpectator
        function onEntry(id, tester, category, prompt, answer, grade, elapsed) {
            entries.append({
                id: id, tester: tester, category: category,
                prompt: prompt, answer: answer, grade: grade, elapsed: elapsed
            })
            if (grade === "pass")    root.passCount++
            else if (grade === "partial") root.partialCount++
            else root.failCount++
        }
    }

    ListModel { id: entries }

    // ---- Header strip ----
    Rectangle {
        id: header
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        height: 76
        color: root.surface
        Rectangle {
            anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
            height: 1; color: root.border
        }

        ColumnLayout {
            anchors.fill: parent
            anchors.margins: 14
            spacing: 4

            RowLayout {
                Layout.fillWidth: true
                spacing: 14
                Label {
                    text: "Live Test Spectator"
                    font.pixelSize: 17; font.weight: Font.Medium
                    font.family: "Charter, Georgia, serif"
                    color: root.textPrimary
                }
                Label {
                    text: testSpectator.watching ? "● watching" : "○ paused"
                    color: testSpectator.watching ? root.passColor : root.textMuted
                    font.pixelSize: 12
                }
                Item { Layout.fillWidth: true }
                Label {
                    text: root.passCount + " pass · " + root.partialCount + " partial · " + root.failCount + " fail"
                    color: root.textSecondary
                    font.pixelSize: 12
                }
                Button {
                    text: "Close"
                    font.pixelSize: 12
                    onClicked: root.parent.spectatorOpen = false
                }
            }
            RowLayout {
                spacing: 6
                Label { text: "Grade:"; font.pixelSize: 11; color: root.textMuted }
                Repeater {
                    model: ["all", "pass", "partial", "fail"]
                    delegate: Button {
                        text: modelData
                        font.pixelSize: 11
                        highlighted: root.filterGrade === modelData
                        flat: true
                        onClicked: root.filterGrade = modelData
                    }
                }
                Item { width: 14 }
                Label { text: "Tester:"; font.pixelSize: 11; color: root.textMuted }
                Repeater {
                    model: ["all", "research", "chat"]
                    delegate: Button {
                        text: modelData
                        font.pixelSize: 11
                        highlighted: root.filterTester === modelData
                        flat: true
                        onClicked: root.filterTester = modelData
                    }
                }
            }
        }
    }

    // ---- Entry list ----
    ScrollView {
        anchors.top: header.bottom
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        clip: true

        ColumnLayout {
            width: parent.width
            spacing: 0

            Repeater {
                model: entries
                delegate: Rectangle {
                    visible: (root.filterGrade === "all" || grade === root.filterGrade)
                          && (root.filterTester === "all" || tester === root.filterTester)
                    Layout.fillWidth: true
                    Layout.preferredHeight: visible ? entryCol.implicitHeight + 24 : 0
                    Layout.leftMargin: 24; Layout.rightMargin: 24; Layout.topMargin: 12
                    color: rowHover.containsMouse ? root.surfaceSubtle : root.surface
                    radius: 10
                    border.color: root.border; border.width: 1

                    MouseArea {
                        id: rowHover
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: parent.expanded = !parent.expanded
                    }
                    property bool expanded: false

                    ColumnLayout {
                        id: entryCol
                        anchors.left: parent.left; anchors.right: parent.right; anchors.top: parent.top
                        anchors.margins: 12
                        spacing: 6

                        RowLayout {
                            spacing: 8
                            Rectangle {
                                width: gradeLabel.implicitWidth + 14; height: 22
                                radius: 11
                                color: grade === "pass" ? root.passColor
                                     : (grade === "partial" ? root.partialColor : root.failColor)
                                Label { id: gradeLabel; anchors.centerIn: parent; text: grade.toUpperCase(); color: "white"; font.pixelSize: 10; font.weight: Font.Bold }
                            }
                            Rectangle {
                                width: testerLabel.implicitWidth + 14; height: 22; radius: 11
                                color: root.surfaceSubtle; border.color: root.border; border.width: 1
                                Label { id: testerLabel; anchors.centerIn: parent; text: tester; color: root.textSecondary; font.pixelSize: 10; font.weight: Font.Medium }
                            }
                            Label {
                                text: category
                                color: root.textMuted; font.pixelSize: 11
                                font.family: "Menlo, Monaco, monospace"
                            }
                            Item { Layout.fillWidth: true }
                            Label { text: elapsed.toFixed(1) + "s"; color: root.textMuted; font.pixelSize: 10 }
                            Label { text: id; color: root.textMuted; font.pixelSize: 10; font.family: "Menlo, Monaco, monospace" }
                        }
                        Label {
                            text: prompt
                            color: root.textPrimary
                            font.pixelSize: 13; font.weight: Font.Medium
                            wrapMode: Label.Wrap
                            Layout.fillWidth: true
                            maximumLineCount: parent.parent.expanded ? -1 : 2
                            elide: parent.parent.expanded ? Label.ElideNone : Label.ElideRight
                        }
                        Label {
                            text: answer
                            color: root.textSecondary
                            font.pixelSize: 12
                            font.family: "Charter, Georgia, serif"
                            wrapMode: Label.Wrap
                            Layout.fillWidth: true
                            maximumLineCount: parent.parent.expanded ? -1 : 3
                            elide: parent.parent.expanded ? Label.ElideNone : Label.ElideRight
                            lineHeight: 1.35
                        }
                    }
                }
            }

            // Empty state
            Label {
                visible: entries.count === 0
                Layout.alignment: Qt.AlignHCenter
                Layout.topMargin: 80
                text: testSpectator.watching
                    ? "Waiting for the first answer from the test agents…"
                    : "Spectator paused."
                color: root.textMuted
                font.pixelSize: 13
                horizontalAlignment: Text.AlignHCenter
            }
        }
    }
}
