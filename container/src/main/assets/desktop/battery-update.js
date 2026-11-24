for (const panel of panels()) {
    for (const systray of panel.widgets("org.kde.plasma.systemtray")) {
        const tray = desktopById(systray.readConfig("SystrayContainmentId"))
        if (!tray) continue
        tray.currentConfigGroup = ["General"]
        const shown = tray.readConfig("shownItems", [])
        if (shown.indexOf("org.kde.plasma.battery") < 0) tray.writeConfig("shownItems", shown.concat(["org.kde.plasma.battery"]))
        const battery = tray.widgets("org.kde.plasma.battery")[0] || tray.addWidget("org.kde.plasma.battery")
        battery.currentConfigGroup = ["General"]
        battery.writeConfig("showPercentage", true)
    }
}
