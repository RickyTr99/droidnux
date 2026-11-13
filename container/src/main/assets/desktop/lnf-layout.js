loadTemplate("org.kde.plasma.desktop.defaultPanel")

for (const desktop of desktopsForActivity(currentActivity())) {
    desktop.wallpaperPlugin = "org.kde.image"
}
for (const panel of panels()) {
    for (const tasks of panel.widgets("org.kde.plasma.icontasks")) {
        tasks.currentConfigGroup = ["General"]
        tasks.writeConfig("launchers", [
            "applications:systemsettings.desktop",
            "preferred://filemanager",
            "preferred://browser",
            "applications:org.kde.konsole.desktop",
        ])
    }
}
