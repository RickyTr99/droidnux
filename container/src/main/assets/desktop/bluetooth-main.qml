// Written by DroidNux: Android's Bluetooth in the system tray while it is on,
// as on Android's status bar, from the state file the app writes
// (NetworkReporter). Status only: Android does not let apps switch it.

import QtQuick
import QtQuick.Layouts
import org.kde.plasma.plasmoid
import org.kde.plasma.core as PlasmaCore
import org.kde.plasma.components as PlasmaComponents
import org.kde.kirigami as Kirigami

PlasmoidItem {
    id: root

    property bool on: false
    readonly property bool italian: Qt.locale().name.startsWith("it")

    function tr(en, it) { return italian ? it : en }

    function read() {
        const request = new XMLHttpRequest()
        request.onreadystatechange = () => {
            if (request.readyState === XMLHttpRequest.DONE) root.on = /^bluetooth=1$/m.test(request.responseText)
        }
        request.open("GET", "file://@NETWORK_STATE@")
        request.send()
    }

    // The file has no change notification in QML; reading it is a small local read.
    Timer {
        interval: 3000
        running: true
        repeat: true
        triggeredOnStart: true
        onTriggered: root.read()
    }

    Plasmoid.icon: "network-bluetooth-symbolic"
    Plasmoid.status: on ? PlasmaCore.Types.ActiveStatus : PlasmaCore.Types.HiddenStatus
    toolTipMainText: tr("Bluetooth is on", "Bluetooth acceso")
    toolTipSubText: tr("Managed by Android", "Gestito da Android")

    fullRepresentation: Item {
        Layout.minimumWidth: Kirigami.Units.gridUnit * 14
        Layout.minimumHeight: content.implicitHeight + Kirigami.Units.largeSpacing * 2

        // The tray's popup is taller than this: at its top.
        ColumnLayout {
            id: content
            anchors { left: parent.left; right: parent.right; top: parent.top; margins: Kirigami.Units.largeSpacing }
            spacing: Kirigami.Units.largeSpacing

            RowLayout {
                spacing: Kirigami.Units.largeSpacing
                Kirigami.Icon {
                    source: Plasmoid.icon
                    Layout.preferredWidth: Kirigami.Units.iconSizes.medium
                    Layout.preferredHeight: Kirigami.Units.iconSizes.medium
                }
                Kirigami.Heading {
                    text: root.toolTipMainText
                    level: 3
                    Layout.fillWidth: true
                }
            }

            PlasmaComponents.Label {
                text: root.toolTipSubText
                opacity: 0.7
                font: Kirigami.Theme.smallFont
            }
        }
    }
}
