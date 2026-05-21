import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Left-edge drawer that lists saved conversations newest-first.
// Click an item to switch; right-click or Delete button removes it.
// Opens via Ctrl+H or the History toolbutton in the chat header.
Drawer {
    id: drawerRoot
    edge: Qt.LeftEdge
    modal: true
    dragMargin: 0

    // Design tokens — kept in sync with ChatView so the drawer feels native.
    property color bgColor:       "#faf9f5"
    property color surface:       "#ffffff"
    property color surfaceSubtle: "#f0eee6"
    property color border:        "#e5e1d6"
    property color textPrimary:   "#1c1b1a"
    property color textSecondary: "#6b6960"
    property color textMuted:     "#9a9788"
    property color accent:        "#cc785c"
    property color danger:        "#a23a3a"

    // Notify the host when a conversation is picked, so the chat view can
    // close the drawer + focus the composer.
    signal selected(string id)
    signal createRequested()

    background: Rectangle { color: drawerRoot.surface; border.width: 0 }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 18
        spacing: 12

        // Header row
        RowLayout {
            Layout.fillWidth: true
            spacing: 8
            Label {
                text: "Conversations"
                color: drawerRoot.textPrimary
                font.pixelSize: 18
                font.weight: Font.Medium
                font.family: "Charter, Georgia, serif"
                Layout.fillWidth: true
            }
            Button {
                text: "+ New"
                font.pixelSize: 12
                onClicked: {
                    drawerRoot.createRequested()
                    drawerRoot.close()
                }
            }
        }

        Label {
            visible: convList.count === 0
            text: "No saved chats yet. Start a conversation and it will appear here."
            color: drawerRoot.textMuted
            font.pixelSize: 12
            wrapMode: Label.WordWrap
            Layout.fillWidth: true
            Layout.topMargin: 12
        }

        ListView {
            id: convList
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            spacing: 4
            model: conversationStore.conversations
            currentIndex: -1

            // The C++ store emits conversationsChanged whenever data shifts.
            // Keep the model bound to the live list so we don't show stale
            // counts after an append.
            Connections {
                target: conversationStore
                function onConversationsChanged() { convList.model = conversationStore.conversations }
            }

            delegate: Rectangle {
                width: convList.width
                height: row.implicitHeight + 18
                radius: 8
                color: rowArea.containsMouse
                    ? drawerRoot.surfaceSubtle
                    : (modelData.id === conversationStore.currentId
                        ? drawerRoot.surfaceSubtle
                        : drawerRoot.surface)
                border.color: modelData.id === conversationStore.currentId
                    ? drawerRoot.accent
                    : drawerRoot.border
                border.width: 1
                Behavior on color { ColorAnimation { duration: 100 } }

                MouseArea {
                    id: rowArea
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    acceptedButtons: Qt.LeftButton | Qt.RightButton
                    onClicked: (mouse) => {
                        if (mouse.button === Qt.RightButton) {
                            ctxMenu.popup()
                        } else {
                            drawerRoot.selected(modelData.id)
                            conversationStore.switchTo(modelData.id)
                            drawerRoot.close()
                        }
                    }
                }

                Menu {
                    id: ctxMenu
                    MenuItem {
                        text: "Open"
                        onTriggered: {
                            drawerRoot.selected(modelData.id)
                            conversationStore.switchTo(modelData.id)
                            drawerRoot.close()
                        }
                    }
                    MenuItem {
                        text: "Delete"
                        onTriggered: conversationStore.deleteConversation(modelData.id)
                    }
                }

                RowLayout {
                    id: row
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.verticalCenter: parent.verticalCenter
                    anchors.leftMargin: 12
                    anchors.rightMargin: 8
                    spacing: 10

                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 3
                        Label {
                            text: modelData.title
                            color: drawerRoot.textPrimary
                            font.pixelSize: 13
                            font.weight: Font.Medium
                            elide: Label.ElideRight
                            Layout.fillWidth: true
                        }
                        Label {
                            visible: modelData.preview && modelData.preview.length > 0
                            text: modelData.preview
                            color: drawerRoot.textSecondary
                            font.pixelSize: 11
                            elide: Label.ElideRight
                            maximumLineCount: 1
                            Layout.fillWidth: true
                        }
                        RowLayout {
                            spacing: 8
                            Label {
                                text: {
                                    if (!modelData.ts) return ""
                                    const d = new Date(modelData.ts)
                                    const now = new Date()
                                    const sameDay = d.toDateString() === now.toDateString()
                                    if (sameDay) return Qt.formatTime(d, "h:mm AP")
                                    return Qt.formatDate(d, "MMM d")
                                }
                                color: drawerRoot.textMuted
                                font.pixelSize: 10
                            }
                            Label {
                                text: modelData.count + " msg"
                                color: drawerRoot.textMuted
                                font.pixelSize: 10
                            }
                            Item { Layout.fillWidth: true }
                        }
                    }

                    ToolButton {
                        text: "✕"
                        font.pixelSize: 13
                        opacity: rowArea.containsMouse ? 1 : 0.0
                        Behavior on opacity { NumberAnimation { duration: 120 } }
                        hoverEnabled: true
                        ToolTip.text: "Delete this chat"
                        ToolTip.visible: hovered
                        ToolTip.delay: 400
                        onClicked: conversationStore.deleteConversation(modelData.id)
                    }
                }
            }
        }

        Label {
            text: "Ctrl+H to toggle · stored locally in conversations.json"
            color: drawerRoot.textMuted
            font.pixelSize: 10
            wrapMode: Label.WordWrap
            Layout.fillWidth: true
        }
    }
}
