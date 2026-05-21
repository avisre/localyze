import QtQuick
import QtQuick.Controls
import QtQuick.Dialogs
import QtQuick.Layouts

// First-launch onboarding wizard. Three steps:
//   1) Welcome
//   2) Hardware probe summary + CPU/GPU/Memory pills
//   3) Model file confirmation or GGUF picker
//
// Visibility is controlled from outside (visible: !settings.onboarded).
// Emits onFinished() once the user clicks Finish on the last step.
Rectangle {
    id: onboarding

    // --- inputs from parent ---
    property string backendLabel: ""
    property string backendReason: ""
    property bool   hasModel: false

    signal onFinished()

    // --- design tokens (mirror ChatView so we don't pull in a singleton) ---
    readonly property color bgColor:       "#faf9f5"
    readonly property color surface:       "#ffffff"
    readonly property color surfaceSubtle: "#f0eee6"
    readonly property color border:        "#e5e1d6"
    readonly property color borderStrong:  "#c8c3b3"
    readonly property color textPrimary:   "#1c1b1a"
    readonly property color textSecondary: "#6b6960"
    readonly property color textMuted:     "#9a9788"
    readonly property color accent:        "#cc785c"
    readonly property color accentHover:   "#b66348"
    readonly property color okGreen:       "#4a7a3a"

    // --- wizard state ---
    property int step: 0          // 0..2

    // --- download placeholders ---
    // Default URL + sha256 for the GGUF download. Both are placeholders so they
    // can be swapped in without re-touching this file; SettingsStore exposes
    // the same defaults as Q_PROPERTYs so a test harness or manifest-resolver
    // can override at runtime. Until a real sha256 is wired in, verification
    // will fail by design (safe default).
    readonly property string modelDownloadUrl:
        (typeof settings.modelDownloadUrl === "string" && settings.modelDownloadUrl.length > 0)
            ? settings.modelDownloadUrl
            : "https://huggingface.co/ggml-org/gemma-4-e4b-it-Q4_K_M-GGUF/resolve/main/gemma-4-e4b-it-q4.gguf"
    readonly property string modelDownloadSha256:
        (typeof settings.modelDownloadSha256 === "string" && settings.modelDownloadSha256.length > 0)
            ? settings.modelDownloadSha256
            : "0000000000000000000000000000000000000000000000000000000000000000"

    // Maps the C++ ModelDownloader::State enum (Idle=0 .. Error=4) to the
    // string the spec asks for. Used by the state label and by the
    // auto-advance trigger.
    function _dlStateName(s) {
        switch (s) {
            case 0: return "Idle"
            case 1: return "Downloading"
            case 2: return "Verifying"
            case 3: return "Done"
            case 4: return "Error"
        }
        return "Idle"
    }
    function _fmtBytes(b) {
        if (!b || b <= 0) return "0 B"
        if (b < 1024) return b + " B"
        if (b < 1024 * 1024) return (b / 1024).toFixed(1) + " KB"
        if (b < 1024 * 1024 * 1024) return (b / (1024 * 1024)).toFixed(1) + " MB"
        return (b / (1024 * 1024 * 1024)).toFixed(2) + " GB"
    }

    color: bgColor

    // Block input behind the wizard.
    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onWheel: function(w){ w.accepted = true } }

    // -------- parsed hardware tokens from backendLabel / backendReason --------
    // backendLabel example:  "Gemma 4 E4B (Q4_K_M) — Vulkan · ctx 8192"
    // backendReason can mention CPU/GPU/Memory hints in free text.
    function _hasGpu() {
        const lbl = onboarding.backendLabel.toLowerCase()
        return lbl.indexOf("vulkan") >= 0
            || lbl.indexOf("cuda") >= 0
            || lbl.indexOf("rocm") >= 0
            || lbl.indexOf("hip") >= 0
    }
    function _gpuName() {
        const lbl = onboarding.backendLabel
        if (lbl.toLowerCase().indexOf("vulkan") >= 0) return "GPU · Vulkan"
        if (lbl.toLowerCase().indexOf("cuda")   >= 0) return "GPU · CUDA"
        if (lbl.toLowerCase().indexOf("rocm")   >= 0) return "GPU · ROCm"
        if (lbl.toLowerCase().indexOf("hip")    >= 0) return "GPU · HIP"
        return "GPU · not detected"
    }
    function _memLabel() {
        // Pull "ctx N" out of label.
        const m = onboarding.backendLabel.match(/ctx\s+(\d+)/i)
        return m ? ("Memory · ctx " + m[1]) : "Memory · auto"
    }

    // ========================== top progress strip ==========================
    Rectangle {
        id: progressBar
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        height: 60
        color: onboarding.surface
        Rectangle {
            anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
            height: 1; color: onboarding.border
        }
        RowLayout {
            anchors.fill: parent
            anchors.leftMargin: 28
            anchors.rightMargin: 28
            spacing: 12

            Rectangle {
                width: 26; height: 26; radius: 7
                color: onboarding.accent
                Label {
                    anchors.centerIn: parent
                    text: "L"; color: "white"
                    font.pixelSize: 14; font.bold: true
                }
            }
            Label {
                text: "Localyze"
                color: onboarding.textPrimary
                font.pixelSize: 14; font.weight: Font.Medium
            }
            Item { Layout.fillWidth: true }

            // step dots
            Repeater {
                model: 3
                delegate: Rectangle {
                    width: index === onboarding.step ? 22 : 8
                    height: 8; radius: 4
                    color: index <= onboarding.step ? onboarding.accent : onboarding.border
                    Behavior on width { NumberAnimation { duration: 160 } }
                    Behavior on color { ColorAnimation  { duration: 160 } }
                }
            }
            Label {
                text: "Step " + (onboarding.step + 1) + " of 3"
                color: onboarding.textMuted
                font.pixelSize: 12
                Layout.leftMargin: 8
            }
        }
    }

    // ============================ content stack ============================
    Item {
        id: stage
        anchors.top: progressBar.bottom
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.margins: 0

        // ---------------- STEP 1 — Welcome ----------------
        ColumnLayout {
            id: stepWelcome
            visible: onboarding.step === 0
            anchors.centerIn: parent
            width: Math.min(640, parent.width - 80)
            spacing: 22

            Label {
                Layout.alignment: Qt.AlignHCenter
                text: "Welcome to Localyze."
                color: onboarding.textPrimary
                font.pixelSize: 44
                font.family: "Charter, Georgia, serif"
            }
            Label {
                Layout.alignment: Qt.AlignHCenter
                Layout.preferredWidth: 540
                text: "An AI that runs entirely on your machine."
                color: onboarding.textSecondary
                font.pixelSize: 17
                wrapMode: Label.WordWrap
                horizontalAlignment: Text.AlignHCenter
            }
            Label {
                Layout.alignment: Qt.AlignHCenter
                Layout.preferredWidth: 540
                Layout.topMargin: 4
                text: "No accounts. No cloud round-trips. Your prompts stay on this device."
                color: onboarding.textMuted
                font.pixelSize: 13
                wrapMode: Label.WordWrap
                horizontalAlignment: Text.AlignHCenter
            }

            Item { Layout.preferredHeight: 12 }

            // Continue button
            Rectangle {
                id: welcomeBtn
                Layout.alignment: Qt.AlignHCenter
                Layout.preferredWidth: 200
                Layout.preferredHeight: 44
                radius: 22
                color: welcomeArea.containsMouse ? onboarding.accentHover : onboarding.accent
                Behavior on color { ColorAnimation { duration: 120 } }
                Label {
                    anchors.centerIn: parent
                    text: "Continue  →"
                    color: "white"
                    font.pixelSize: 14; font.weight: Font.Medium
                }
                MouseArea {
                    id: welcomeArea
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: onboarding.step = 1
                }
            }
        }

        // ---------------- STEP 2 — Hardware ----------------
        ColumnLayout {
            id: stepHardware
            visible: onboarding.step === 1
            anchors.centerIn: parent
            width: Math.min(680, parent.width - 80)
            spacing: 18

            Label {
                Layout.alignment: Qt.AlignHCenter
                text: "Your hardware"
                color: onboarding.textPrimary
                font.pixelSize: 32
                font.family: "Charter, Georgia, serif"
            }
            Label {
                Layout.alignment: Qt.AlignHCenter
                Layout.preferredWidth: 560
                text: "We picked the fastest backend your system supports."
                color: onboarding.textSecondary
                font.pixelSize: 14
                wrapMode: Label.WordWrap
                horizontalAlignment: Text.AlignHCenter
            }

            // Backend card
            Rectangle {
                Layout.alignment: Qt.AlignHCenter
                Layout.preferredWidth: 560
                Layout.preferredHeight: hwCol.implicitHeight + 28
                radius: 12
                color: onboarding.surface
                border.color: onboarding.border
                border.width: 1
                ColumnLayout {
                    id: hwCol
                    anchors.fill: parent
                    anchors.margins: 14
                    spacing: 10

                    Label {
                        text: onboarding.backendLabel || "Probing…"
                        color: onboarding.textPrimary
                        font.pixelSize: 14
                        font.weight: Font.Medium
                        wrapMode: Label.WordWrap
                        Layout.fillWidth: true
                    }
                    Label {
                        visible: !!onboarding.backendReason
                        text: onboarding.backendReason
                        color: onboarding.textMuted
                        font.pixelSize: 11
                        font.family: "Menlo, Monaco, monospace"
                        wrapMode: Label.WordWrap
                        Layout.fillWidth: true
                    }
                }
            }

            // Pill row — CPU / GPU / Memory
            RowLayout {
                Layout.alignment: Qt.AlignHCenter
                spacing: 10
                Layout.topMargin: 4

                // CPU pill (always present — we wouldn't be running otherwise)
                Rectangle {
                    height: 36; radius: 18
                    width: cpuRow.implicitWidth + 22
                    color: onboarding.surfaceSubtle
                    border.color: onboarding.border
                    border.width: 1
                    RowLayout {
                        id: cpuRow
                        anchors.centerIn: parent
                        spacing: 8
                        Rectangle {
                            width: 22; height: 22; radius: 6
                            color: onboarding.surface
                            border.color: onboarding.borderStrong
                            border.width: 1
                            Label {
                                anchors.centerIn: parent
                                text: "▦"; color: onboarding.textSecondary
                                font.pixelSize: 12
                            }
                        }
                        Label {
                            text: "CPU · ready"
                            color: onboarding.textPrimary
                            font.pixelSize: 12; font.weight: Font.Medium
                        }
                    }
                }

                // GPU pill — filled accent if detected
                Rectangle {
                    height: 36; radius: 18
                    width: gpuRow.implicitWidth + 22
                    color: onboarding._hasGpu() ? onboarding.accent : onboarding.surfaceSubtle
                    border.color: onboarding._hasGpu() ? onboarding.accent : onboarding.border
                    border.width: 1
                    RowLayout {
                        id: gpuRow
                        anchors.centerIn: parent
                        spacing: 8
                        Rectangle {
                            width: 22; height: 22; radius: 6
                            color: onboarding._hasGpu() ? "white" : onboarding.surface
                            border.color: onboarding._hasGpu() ? "white" : onboarding.borderStrong
                            border.width: 1
                            Label {
                                anchors.centerIn: parent
                                text: "◆"
                                color: onboarding._hasGpu() ? onboarding.accent : onboarding.textSecondary
                                font.pixelSize: 12; font.bold: true
                            }
                        }
                        Label {
                            text: onboarding._gpuName()
                            color: onboarding._hasGpu() ? "white" : onboarding.textPrimary
                            font.pixelSize: 12; font.weight: Font.Medium
                        }
                    }
                }

                // Memory pill — derived from context length
                Rectangle {
                    height: 36; radius: 18
                    width: memRow.implicitWidth + 22
                    color: onboarding.surfaceSubtle
                    border.color: onboarding.border
                    border.width: 1
                    RowLayout {
                        id: memRow
                        anchors.centerIn: parent
                        spacing: 8
                        Rectangle {
                            width: 22; height: 22; radius: 6
                            color: onboarding.surface
                            border.color: onboarding.borderStrong
                            border.width: 1
                            Label {
                                anchors.centerIn: parent
                                text: "≡"; color: onboarding.textSecondary
                                font.pixelSize: 12
                            }
                        }
                        Label {
                            text: onboarding._memLabel()
                            color: onboarding.textPrimary
                            font.pixelSize: 12; font.weight: Font.Medium
                        }
                    }
                }
            }

            // Nav row
            RowLayout {
                Layout.alignment: Qt.AlignHCenter
                Layout.topMargin: 18
                spacing: 12

                Button {
                    text: "← Back"
                    flat: true
                    font.pixelSize: 13
                    onClicked: onboarding.step = 0
                }
                Rectangle {
                    Layout.preferredWidth: 180
                    Layout.preferredHeight: 42
                    radius: 21
                    color: hwArea.containsMouse ? onboarding.accentHover : onboarding.accent
                    Behavior on color { ColorAnimation { duration: 120 } }
                    Label {
                        anchors.centerIn: parent
                        text: "Continue  →"
                        color: "white"
                        font.pixelSize: 14; font.weight: Font.Medium
                    }
                    MouseArea {
                        id: hwArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: onboarding.step = 2
                    }
                }
            }
        }

        // ---------------- STEP 3 — Model file ----------------
        ColumnLayout {
            id: stepModel
            visible: onboarding.step === 2
            anchors.centerIn: parent
            width: Math.min(680, parent.width - 80)
            spacing: 18

            Label {
                Layout.alignment: Qt.AlignHCenter
                text: "Model file"
                color: onboarding.textPrimary
                font.pixelSize: 32
                font.family: "Charter, Georgia, serif"
            }

            // -------- model-loaded path --------
            ColumnLayout {
                visible: onboarding.hasModel
                Layout.alignment: Qt.AlignHCenter
                Layout.preferredWidth: 560
                spacing: 14

                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 84
                    radius: 12
                    color: onboarding.surface
                    border.color: onboarding.border
                    border.width: 1
                    RowLayout {
                        anchors.fill: parent
                        anchors.margins: 16
                        spacing: 14

                        Rectangle {
                            Layout.preferredWidth: 44
                            Layout.preferredHeight: 44
                            radius: 22
                            color: onboarding.okGreen
                            Label {
                                anchors.centerIn: parent
                                text: "✓"
                                color: "white"
                                font.pixelSize: 22; font.bold: true
                            }
                        }
                        ColumnLayout {
                            spacing: 2
                            Layout.fillWidth: true
                            Label {
                                text: "Loaded — Gemma 4 E4B Q4"
                                color: onboarding.textPrimary
                                font.pixelSize: 14
                                font.weight: Font.Medium
                            }
                            Label {
                                text: settings.modelPath
                                color: onboarding.textMuted
                                font.pixelSize: 11
                                font.family: "Menlo, Monaco, monospace"
                                wrapMode: Label.WrapAnywhere
                                elide: Label.ElideMiddle
                                Layout.fillWidth: true
                            }
                        }
                    }
                }
                Label {
                    Layout.fillWidth: true
                    horizontalAlignment: Text.AlignHCenter
                    text: "You're ready to chat."
                    color: onboarding.textSecondary
                    font.pixelSize: 13
                }
            }

            // -------- pick-a-file path --------
            ColumnLayout {
                visible: !onboarding.hasModel
                Layout.alignment: Qt.AlignHCenter
                Layout.preferredWidth: 560
                spacing: 14

                // ----- Download model card -----
                Rectangle {
                    id: dlCard
                    Layout.fillWidth: true
                    Layout.preferredHeight: dlCol.implicitHeight + 28
                    radius: 12
                    color: onboarding.surface
                    border.color: onboarding.border
                    border.width: 1

                    // Live state mirrors of the ModelDownloader context property.
                    // Pulled into the QML scope so the bindings re-evaluate
                    // whenever stateChanged / progressChanged fires.
                    readonly property int    dlStateInt: modelDownloader ? modelDownloader.state    : 0
                    readonly property string dlStateStr: onboarding._dlStateName(dlStateInt)
                    readonly property real   dlProgress: modelDownloader ? modelDownloader.progress : 0.0
                    readonly property double dlDone:     modelDownloader ? modelDownloader.downloaded : 0
                    readonly property double dlTotal:    modelDownloader ? modelDownloader.total      : 0
                    readonly property string dlError:    modelDownloader ? modelDownloader.errorMessage : ""
                    readonly property bool   dlBusy:     dlStateInt === 1 || dlStateInt === 2

                    // Auto-advance to the next onboarding step once verification
                    // passes. The download card is only visible when !hasModel,
                    // so reaching "Done" means we just produced the GGUF — bump
                    // the wizard along (there is no step beyond model file in
                    // this 3-step wizard, so we fire onFinished()).
                    onDlStateStrChanged: {
                        if (dlStateStr === "Done") {
                            onboarding.onFinished()
                        }
                    }

                    ColumnLayout {
                        id: dlCol
                        anchors.fill: parent
                        anchors.margins: 14
                        spacing: 10

                        Label {
                            text: "Download the model"
                            color: onboarding.textPrimary
                            font.pixelSize: 14
                            font.weight: Font.Medium
                        }
                        Label {
                            Layout.fillWidth: true
                            text: "We'll fetch Gemma 4 E4B Q4 (≈ 4 GB) and place it at:"
                            color: onboarding.textSecondary
                            font.pixelSize: 12
                            wrapMode: Label.WordWrap
                        }
                        Label {
                            Layout.fillWidth: true
                            text: settings.modelPath
                            color: onboarding.textMuted
                            font.pixelSize: 11
                            font.family: "Menlo, Monaco, monospace"
                            wrapMode: Label.WrapAnywhere
                            elide: Label.ElideMiddle
                        }

                        // Big primary action — start / resume.
                        Rectangle {
                            id: dlBtn
                            Layout.alignment: Qt.AlignHCenter
                            Layout.topMargin: 4
                            Layout.preferredWidth: 280
                            Layout.preferredHeight: 46
                            radius: 23
                            color: dlBtnArea.containsMouse && !dlCard.dlBusy
                                   ? onboarding.accentHover
                                   : (dlCard.dlBusy ? onboarding.surfaceSubtle : onboarding.accent)
                            Behavior on color { ColorAnimation { duration: 120 } }
                            Label {
                                anchors.centerIn: parent
                                text: dlCard.dlBusy ? "Downloading…" : "Download model  (≈ 4 GB)"
                                color: dlCard.dlBusy ? onboarding.textMuted : "white"
                                font.pixelSize: 14
                                font.weight: Font.Medium
                            }
                            MouseArea {
                                id: dlBtnArea
                                anchors.fill: parent
                                hoverEnabled: true
                                cursorShape: dlCard.dlBusy ? Qt.ArrowCursor : Qt.PointingHandCursor
                                enabled: !dlCard.dlBusy && modelDownloader !== null
                                onClicked: {
                                    modelDownloader.start(
                                        onboarding.modelDownloadUrl,
                                        settings.modelPath,
                                        onboarding.modelDownloadSha256)
                                }
                            }
                        }

                        // Progress bar — bound to modelDownloader.progress (0..1).
                        ProgressBar {
                            Layout.fillWidth: true
                            Layout.topMargin: 6
                            from: 0; to: 1
                            value: dlCard.dlProgress
                            indeterminate: dlCard.dlStateInt === 2  // Verifying
                        }

                        // Bytes label  +  state label.
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 8
                            Label {
                                text: {
                                    const pct = dlCard.dlTotal > 0
                                              ? Math.floor((dlCard.dlDone / dlCard.dlTotal) * 100)
                                              : 0
                                    return onboarding._fmtBytes(dlCard.dlDone)
                                         + "  /  "
                                         + onboarding._fmtBytes(dlCard.dlTotal)
                                         + "  ·  "
                                         + pct + "%"
                                }
                                color: onboarding.textSecondary
                                font.pixelSize: 11
                                font.family: "Menlo, Monaco, monospace"
                            }
                            Item { Layout.fillWidth: true }
                            Label {
                                text: {
                                    switch (dlCard.dlStateStr) {
                                        case "Idle":        return "Idle"
                                        case "Downloading": return "Downloading…"
                                        case "Verifying":   return "Verifying SHA-256…"
                                        case "Done":        return "Done ✓"
                                        case "Error":       return "Error: " + dlCard.dlError
                                    }
                                    return ""
                                }
                                color: dlCard.dlStateStr === "Error"
                                       ? "#b94a3a"
                                       : (dlCard.dlStateStr === "Done"
                                              ? onboarding.okGreen
                                              : onboarding.textPrimary)
                                font.pixelSize: 11
                                font.weight: Font.Medium
                            }
                        }
                    }
                }

                // ----- Fallback: pick a local file -----
                Label {
                    Layout.fillWidth: true
                    Layout.topMargin: 4
                    horizontalAlignment: Text.AlignHCenter
                    text: "Already have the GGUF on disk?"
                    color: onboarding.textSecondary
                    font.pixelSize: 13
                    wrapMode: Label.WordWrap
                }
                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 90
                    radius: 12
                    color: onboarding.surface
                    border.color: pathValid ? onboarding.border : "#d8b4a0"
                    border.width: 1

                    // Local check — path resolves to a .gguf file mentioned in modelPath.
                    property bool pathValid: settings.modelPath.toLowerCase().endsWith(".gguf")

                    ColumnLayout {
                        anchors.fill: parent
                        anchors.margins: 14
                        spacing: 8

                        Label {
                            text: settings.modelPath
                            color: onboarding.textPrimary
                            font.pixelSize: 11
                            font.family: "Menlo, Monaco, monospace"
                            wrapMode: Label.WrapAnywhere
                            elide: Label.ElideMiddle
                            Layout.fillWidth: true
                        }
                        RowLayout {
                            spacing: 8
                            Label {
                                text: "GGUF file extension required"
                                color: onboarding.textMuted
                                font.pixelSize: 10
                            }
                            Item { Layout.fillWidth: true }
                            Button {
                                text: "Browse for local file…"
                                font.pixelSize: 12
                                onClicked: ggufDialog.open()
                            }
                        }
                    }
                }
                Label {
                    text: "Localyze runs Gemma 4 E4B Q4 (≈4 GB). After selecting or downloading the file, restart Localyze to load it."
                    color: onboarding.textMuted
                    font.pixelSize: 11
                    wrapMode: Label.WordWrap
                    Layout.fillWidth: true
                    horizontalAlignment: Text.AlignHCenter
                }
            }

            FileDialog {
                id: ggufDialog
                title: "Select Gemma GGUF model"
                nameFilters: ["GGUF model (*.gguf)", "All files (*)"]
                onAccepted: settings.modelPath = selectedFile.toString().replace(/^file:\/\//, "")
            }

            // Nav row
            RowLayout {
                Layout.alignment: Qt.AlignHCenter
                Layout.topMargin: 10
                spacing: 12

                Button {
                    text: "← Back"
                    flat: true
                    font.pixelSize: 13
                    onClicked: onboarding.step = 1
                }
                Rectangle {
                    id: finishBtn
                    Layout.preferredWidth: 180
                    Layout.preferredHeight: 42
                    radius: 21
                    // Finish is enabled when either: the model is already loaded,
                    // or the user has chosen a path that ends in .gguf (we can't
                    // hit the filesystem from QML easily, so the extension is the
                    // proxy — main.cpp re-checks on next startup).
                    property bool enabledNow: onboarding.hasModel
                                              || settings.modelPath.toLowerCase().endsWith(".gguf")
                    color: !enabledNow
                           ? onboarding.surfaceSubtle
                           : (finishArea.containsMouse ? onboarding.accentHover : onboarding.accent)
                    Behavior on color { ColorAnimation { duration: 120 } }
                    Label {
                        anchors.centerIn: parent
                        text: "Finish"
                        color: finishBtn.enabledNow ? "white" : onboarding.textMuted
                        font.pixelSize: 14; font.weight: Font.Medium
                    }
                    MouseArea {
                        id: finishArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: finishBtn.enabledNow ? Qt.PointingHandCursor : Qt.ArrowCursor
                        enabled: finishBtn.enabledNow
                        onClicked: onboarding.onFinished()
                    }
                }
            }
        }
    }
}
