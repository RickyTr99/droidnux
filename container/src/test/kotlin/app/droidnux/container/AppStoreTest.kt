package app.droidnux.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AppStoreTest {

    @Test
    fun backportsInstallFromTheirSuite() {
        val script = AppStore.installScript(listOf("backports:libreoffice-writer", "backports:libreoffice-calc"), "it")
        assertTrue(script.contains("debian-backports.sources"))
        assertTrue(script.contains("install -y --no-install-recommends -t trixie-backports libreoffice-writer libreoffice-calc"))
        assertTrue(script.contains("libreoffice-l10n-it"))
        assertFalse(script.contains("backports:"))
    }

    @Test
    fun debianInstallLeavesBackportsAlone() {
        val script = AppStore.installScript(listOf("gimp"), "it")
        assertFalse(script.contains("trixie-backports"))
        assertFalse(script.contains("sources.list.d/"))
    }

    @Test
    fun vendorAppsSetUpTheirRepository() {
        val script = AppStore.installScript(listOf("firefox"), "it")
        assertTrue(script.contains("https://packages.mozilla.org/apt"))
        assertTrue(script.contains("firefox-l10n-it"))
        assertTrue(AppStore.installScript(listOf("code"), "en").contains("/etc/apt/sources.list.d/vscode.sources"))
    }

    @Test
    fun githubAppsInstallTheirDownloadedPackage() {
        val script = AppStore.installScript(listOf("vesktop"), "en")
        assertTrue(script.contains("https://api.github.com/repos/Vencord/Vesktop/releases/latest"))
        assertTrue(script.contains("install -y --no-install-recommends /tmp/droidnux-vesktop.deb"))
    }

    @Test
    fun removingBackportsRemovesThePackage() {
        assertTrue(AppStore.removeScript(listOf("backports:darktable"), emptyList()).contains("remove -y darktable"))
    }

    @Test
    fun installedPackagesMarkBackports() {
        val rootfs = Files.createTempDirectory("rootfs").toFile()
        val status = java.io.File(rootfs, "var/lib/dpkg/status")
        status.parentFile.mkdirs()
        status.writeText(
            """
            Package: darktable
            Status: install ok installed
            Version: 5.4.1-1~bpo13+1

            Package: gimp
            Status: install ok installed
            Version: 3.0.4-3

            Package: krita
            Status: deinstall ok config-files
            Version: 1:5.2.9+dfsg-2
            """.trimIndent() + "\n",
        )
        assertEquals(setOf("darktable", "backports:darktable", "gimp"), AppStore.installedPackages(rootfs))
        assertEquals("3.0.4-3", AppStore.installedVersions(rootfs)["gimp"])
        rootfs.deleteRecursively()
    }

    @Test
    fun backportsHaveTheirLauncher() {
        val rootfs = Files.createTempDirectory("rootfs").toFile()
        java.io.File(rootfs, "var/lib/dpkg/info").mkdirs()
        java.io.File(rootfs, "var/lib/dpkg/info/flameshot.list").writeText("/usr/share/applications/org.flameshot.Flameshot.desktop\n")
        java.io.File(rootfs, "usr/share/applications").mkdirs()
        java.io.File(rootfs, "usr/share/applications/org.flameshot.Flameshot.desktop").writeText("[Desktop Entry]\n")
        assertEquals("org.flameshot.Flameshot", AppStore.desktopId(rootfs, "backports:flameshot"))
        assertEquals("org.flameshot.Flameshot", AppStore.desktopId(rootfs, "flameshot"))
        rootfs.deleteRecursively()
    }

    @Test
    fun backportsVersions() {
        val list = "Package: darktable\nVersion: 5.4.1-1~bpo13+1\n\nPackage: libreoffice-core\nVersion: 4:26.8.2.2-2~bpo13+1\n"
        val versions = BackportsCatalog.parse(list.byteInputStream())
        assertEquals("5.4.1-1~bpo13+1", versions["darktable"])
        assertEquals("26.8.2.2", BackportsCatalog.shortVersion(versions.getValue("libreoffice-core")))
        assertEquals("9.0.8", BackportsCatalog.shortVersion("9.0.8+dfsg-1~bpo13+1"))
    }
    @Test
    fun updatesFromAptAndFlathub() {
        val output = listOf(
            "DROIDNUX_UPDATES apt",
            "Inst liblzma5 [5.8.1-1+deb13u1] (5.8.1-1+deb13u2 Debian-Security:13/stable-security [arm64])",
            "Inst libnew1 (2.0-1 Debian:13.2/stable [arm64])",
            "DROIDNUX_UPDATES uris",
            "'http://deb.debian.org/debian-security/pool/updates/main/x/xz-utils/liblzma5_5.8.1-1%2bdeb13u2_arm64.deb' liblzma5_5.8.1-1+deb13u2_arm64.deb 302984 SHA256:00",
            "DROIDNUX_UPDATES flatpak",
            "org.kde.krita\tstable\t5.2.9",
            "DROIDNUX_UPDATES flatpak-updates",
            "org.kde.krita\tstable\t5.2.11\t141.2 MB",
            "DROIDNUX_STORE_OK",
        ).joinToString("\n")
        assertEquals(
            listOf(
                AppStore.Update("liblzma5", "5.8.1-1+deb13u1", "5.8.1-1+deb13u2", 302984),
                AppStore.Update("libnew1", "", "2.0-1", 0),
                AppStore.Update("flatpak:org.kde.krita", "5.2.9", "5.2.11", 141_200_000),
            ),
            AppStore.parseUpdates(output),
        )
    }

    @Test
    fun upgradeOnlyWhatIsAsked() {
        val one = AppStore.upgradeScript(listOf("backports:darktable"))
        assertTrue(one.contains("install -y --only-upgrade -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold darktable"))
        assertFalse(one.contains("flatpak update"))
        assertTrue(AppStore.upgradeScript(listOf("flatpak:org.kde.krita")).contains("flatpak update --system -y org.kde.krita"))
        val all = AppStore.upgradeScript(emptyList())
        assertTrue(all.contains("upgrade -y --with-new-pkgs"))
        assertTrue(all.contains("apt-mark hold"))
    }
}
