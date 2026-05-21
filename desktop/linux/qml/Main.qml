import QtQuick
import QtQuick.Controls
import QtQuick.Window
import LocalyzeUI

ApplicationWindow {
    id: window
    visible: true
    width: 1100
    height: 800
    minimumWidth: 560
    minimumHeight: 480
    title: "Localyze"
    color: Theme.bgColor   // resolves to warm off-white (light) or charcoal (dark)

    ChatView {
        id: chatView
        anchors.fill: parent
        backendLabel:  ctxBackendLabel
        backendReason: ctxBackendReason
    }
}
