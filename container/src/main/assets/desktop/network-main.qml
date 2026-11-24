// Written by DroidNux: Android's default network in the system tray, from the
// state file the app writes (NetworkReporter). Status only: Android does not
// let apps switch networks.

import QtQuick
import QtQuick.Layouts
import org.kde.plasma.plasmoid
import org.kde.plasma.core as PlasmaCore
import org.kde.plasma.components as PlasmaComponents
import org.kde.kirigami as Kirigami

PlasmoidItem {
    id: root

    property string raw: ""
    property var fields: ({})
    readonly property bool online: !!fields.interface
    readonly property bool wifi: fields.type === "wifi"
    readonly property int signal: parseInt(fields.signal || "0")
    readonly property bool italian: Qt.locale().name.startsWith("it")

    function tr(en, it) { return italian ? it : en }

    function read() {
        const request = new XMLHttpRequest()
        request.onreadystatechange = () => {
            if (request.readyState !== XMLHttpRequest.DONE || request.responseText === root.raw) return
            root.raw = request.responseText
            const parsed = {}
            for (const line of root.raw.split("\n")) {
                const at = line.indexOf("=")
                if (at > 0) parsed[line.slice(0, at)] = line.slice(at + 1)
            }
            root.fields = parsed
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

    Plasmoid.icon: {
        if (!online) return "network-offline-symbolic"
        switch (fields.type) {
        case "wifi":
            return "network-wireless-signal-" + (signal >= 75 ? "excellent" : signal >= 50 ? "good" : signal >= 25 ? "ok" : signal > 0 ? "weak" : "none") + "-symbolic"
        case "cellular": return "network-mobile-100-symbolic"
        case "vpn": return "network-vpn"
        default: return "network-wired-activated-symbolic"
        }
    }
    Plasmoid.status: PlasmaCore.Types.ActiveStatus
    toolTipMainText: online ? fields.name : tr("Not connected", "Non connesso")
    toolTipSubText: online && wifi ? tr("Signal", "Segnale") + " " + signal + "%" : ""

    fullRepresentation: Item {
        Layout.minimumWidth: Kirigami.Units.gridUnit * 16
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
                    elide: Text.ElideRight
                    Layout.fillWidth: true
                }
            }

            GridLayout {
                visible: root.online
                columns: 2
                columnSpacing: Kirigami.Units.largeSpacing
                Layout.fillWidth: true

                PlasmaComponents.Label { visible: root.wifi; text: root.tr("Signal", "Segnale"); opacity: 0.7 }
                PlasmaComponents.Label { visible: root.wifi; text: root.signal + "%" }
                PlasmaComponents.Label { visible: !!root.fields.ipv4; text: "IPv4"; opacity: 0.7 }
                PlasmaComponents.Label { visible: !!root.fields.ipv4; text: (root.fields.ipv4 || "").split("/")[0] }
                PlasmaComponents.Label { visible: !!root.fields.ipv6; text: "IPv6"; opacity: 0.7 }
                PlasmaComponents.Label { visible: !!root.fields.ipv6; text: (root.fields.ipv6 || "").split("/")[0]; elide: Text.ElideRight; Layout.fillWidth: true }
            }

            PlasmaComponents.Label {
                text: root.tr("Managed by Android", "Gestita da Android")
                opacity: 0.7
                font: Kirigami.Theme.smallFont
            }
        }
    }
}
