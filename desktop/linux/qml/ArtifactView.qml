import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtCharts

/// Renders one parsed <viz> block with native Qt widgets.
/// Spec: ../../shared/viz-schema.md
Frame {
    id: root
    property string vizType: ""           // chart | table | code | run | image | map
    property var    attrs: ({})           // name → string
    property string inner: ""             // raw inner text (for code / run / pdf)
    property string data: ""              // JSON array string from attrs.data

    Layout.fillWidth: true
    padding: 8

    ColumnLayout {
        anchors.fill: parent
        spacing: 6

        Label {
            text: attrs["title"] || vizType
            font.bold: true
            Layout.fillWidth: true
        }

        Loader {
            id: body
            Layout.fillWidth: true
            Layout.fillHeight: true
            sourceComponent: {
                if (vizType === "chart")  return chartComp
                if (vizType === "table")  return tableComp
                if (vizType === "code")   return codeComp
                if (vizType === "run")    return runComp
                if (vizType === "image")  return imageComp
                return fallbackComp
            }
        }

        Row {
            spacing: 6
            Button { text: "Save"; onClicked: root.saveArtifact() }
            Button { text: "PDF";  onClicked: root.exportPdf() }
        }
    }

    // ----- chart -----
    Component {
        id: chartComp
        ChartView {
            antialiasing: true
            legend.visible: false
            Component.onCompleted: {
                const kind = attrs["kind"] || "line"
                const points = JSON.parse(data || "[]")
                if (points.length === 0) return
                const xKey = attrs["x"] || "x"
                const yKey = attrs["y"] || "y"
                let series
                if (kind === "bar") {
                    const set = barSetComp.createObject(this)
                    points.forEach(p => set.append(Number(p[yKey])))
                    series = barSeriesComp.createObject(this, { name: yKey })
                    series.append(set)
                } else {
                    series = lineSeriesComp.createObject(this, { name: yKey })
                    points.forEach(p => series.append(Number(p[xKey]), Number(p[yKey])))
                }
            }
            Component { id: lineSeriesComp; LineSeries {} }
            Component { id: barSeriesComp;  BarSeries  {} }
            Component { id: barSetComp;     BarSet     {} }
        }
    }

    // ----- table -----
    Component {
        id: tableComp
        ColumnLayout {
            id: tv
            property var rows: JSON.parse(data || "[]")
            property var headers: rows.length > 0 ? Object.keys(rows[0]) : []
            spacing: 1

            // Header row
            Row {
                spacing: 8
                Repeater {
                    model: tv.headers
                    delegate: Label {
                        width: 140
                        elide: Text.ElideRight
                        text: String(modelData)
                        font.bold: true
                    }
                }
            }
            // Data rows
            Repeater {
                model: tv.rows
                delegate: Row {
                    spacing: 8
                    // The Row's direct parent is the Repeater's Item host; the
                    // Label is a child of THIS Row, so `parent.rowData` from
                    // inside the inner Repeater resolves to this row's data.
                    property var rowData: modelData
                    Repeater {
                        model: tv.headers
                        delegate: Label {
                            width: 140
                            elide: Text.ElideRight
                            text: String((parent.rowData || {})[modelData] ?? "")
                        }
                    }
                }
            }
        }
    }

    // ----- code -----
    Component {
        id: codeComp
        Rectangle {
            color: "#0e1116"
            radius: 4
            TextArea {
                anchors.fill: parent
                anchors.margins: 8
                text: inner
                readOnly: true
                font.family: "monospace"
                color: "#e6edf3"
                wrapMode: TextEdit.WrapAnywhere
                selectByMouse: true
            }
        }
    }

    // ----- run -----
    Component {
        id: runComp
        ColumnLayout {
            spacing: 4
            Label { text: "Code (" + (attrs["lang"] || "") + ")"; opacity: 0.7 }
            Rectangle {
                color: "#0e1116"; radius: 4
                Layout.fillWidth: true
                implicitHeight: codeText.contentHeight + 16
                TextArea {
                    id: codeText
                    anchors.fill: parent
                    anchors.margins: 8
                    text: inner
                    readOnly: true
                    font.family: "monospace"
                    color: "#e6edf3"
                }
            }
            Label { text: "Output"; opacity: 0.7 }
            Rectangle {
                color: "#101418"; radius: 4
                Layout.fillWidth: true
                implicitHeight: outText.contentHeight + 16
                TextArea {
                    id: outText
                    anchors.fill: parent
                    anchors.margins: 8
                    text: attrs["stdout"] || "(run by agent — output rendered when ready)"
                    readOnly: true
                    font.family: "monospace"
                    color: "#c0c8d0"
                }
            }
        }
    }

    Component {
        id: imageComp
        Image {
            source: attrs["src"] || ""
            fillMode: Image.PreserveAspectFit
            asynchronous: true
        }
    }

    Component {
        id: fallbackComp
        Label { text: "Unsupported viz type: " + vizType }
    }

    function saveArtifact() {
        // Routed to a C++ slot via context property "artifactSaver" — wired in main.cpp.
        // (Stubbed here so the QML compiles without it; main.cpp adds the binding.)
    }
    function exportPdf() { /* same — wired in main.cpp */ }
}
