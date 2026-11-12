package app.droidnux.container

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Linux apps the user adds to the desktop: apt scripts that install,
 * remove and search Debian's packages (and the few outside Debian, from
 * their vendor's repository), and what dpkg says is installed.
 */
object AppStore {

    /** Printed by [installScript] and [removeScript] when apt is done. */
    const val DONE_MARKER = "DROIDNUX_STORE_OK"

    /** Printed by [removeScript] instead of removing a package the desktop needs: the line ends with its name. */
    const val KEEP_MARKER = "DROIDNUX_STORE_KEEP"

    /** A Flathub app as a store package: "flatpak:org.kde.krita". */
    const val FLATPAK = "flatpak:"

    private fun flatpakIds(packages: List<String>) =
        packages.filter { it.startsWith(FLATPAK) }.map { it.removePrefix(FLATPAK) }.filter(::isFlatpakId)

    // Compiled once: the catalogs and the icons' archive ask for thousands of names.
    private val FLATPAK_ID = Regex("""[A-Za-z0-9_-]+(\.[A-Za-z0-9_-]+){2,}""")
    private val PACKAGE_NAME = Regex("[a-z0-9][a-z0-9+.-]+")

    fun isFlatpakId(id: String) = FLATPAK_ID.matches(id)

    /**
     * Flathub's apps, system-wide (the store runs as root), Flatpak and the remote set up the
     * first time; then DroidNux's GL extension for their runtime (DesktopEnvironment's droidnux-flatpak-gl).
     */
    private fun flatpakInstallScript(ids: List<String>, glScript: String) = """
        $HEADER
        command -v flatpak > /dev/null || $APT install -y --no-install-recommends flatpak || { $APT update && $APT install -y --no-install-recommends flatpak; }
        flatpak remote-add --system --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
        # Not --noninteractive: that drops the "Installing 1/3… 45%" lines the store's progress reads.
        flatpak install --system -y flathub ${ids.joinToString(" ")} < /dev/null
        $glScript || true
        echo $DONE_MARKER
    """.trimIndent()

    private fun flatpakRemoveScript(ids: List<String>) = """
        set -e
        flatpak uninstall --system --noninteractive -y ${ids.joinToString(" ")}
        flatpak uninstall --system --noninteractive -y --unused || true
        # Our GL extension is pinned (droidnux-flatpak-gl): it goes with the last runtime taking its branch (GNOME's and KDE's too).
        branches=${'$'}(flatpak list --system --runtime --columns=ref | while read -r ref; do flatpak info --system -m "${'$'}ref" 2> /dev/null; done |
            sed -n '/^\[Extension org.freedesktop.Platform.GL\]/,/^\[/s/^versions *= *//p' | tr ';' '\n')
        for b in ${'$'}(flatpak list --system --runtime --columns=application,branch | awk '${'$'}1 == "org.freedesktop.Platform.GL.droidnux" { print ${'$'}2 }'); do
            echo "${'$'}branches" | grep -qx "${'$'}b" && continue
            flatpak uninstall --system --noninteractive -y org.freedesktop.Platform.GL.droidnux//${'$'}b || true
            flatpak pin --system --remove runtime/org.freedesktop.Platform.GL.droidnux/${'$'}(flatpak --default-arch)/${'$'}b || true
        done
        echo $DONE_MARKER
    """.trimIndent()

    /** A package from Debian's backports as a store package: "backports:libreoffice-writer". */
    const val BACKPORTS = "backports:"

    /**
     * Apps whose vendor releases an arm64 .deb on GitHub and no apt repository, by package: the
     * repository whose latest release has it. Unsigned: only HTTPS from the app's own repository.
     */
    private val GITHUB = mapOf(
        "vesktop" to "Vencord/Vesktop",
        "bruno" to "usebruno/bruno",
        "beekeeper-studio" to "beekeeper-studio/beekeeper-studio",
        "feishin" to "jeffvli/feishin",
        "youtube-music" to "pear-devs/pear-desktop",
        "pulsar" to "pulsar-edit/pulsar",
        "localsend" to "localsend/localsend",
        "draw.io" to "jgraph/drawio-desktop",
        "siyuan" to "siyuan-note/siyuan",
        "mattermost-desktop" to "mattermost/desktop",
        "tabby-terminal" to "Eugeny/tabby",
        "motrix" to "agalwood/Motrix",
        "rustdesk" to "rustdesk/rustdesk",
        "ferdium" to "ferdium/ferdium-app",
        "github-desktop" to "shiftkey/desktop",
    )

    /** Where a package comes from outside Debian: its vendor's apt repository, or a release on GitHub. */
    fun isFromVendor(pkg: String) = pkg in VendorRepository.BY_PACKAGE
    fun isFromGitHub(pkg: String) = pkg in GITHUB

    private fun githubDeb(pkg: String) = "/tmp/droidnux-$pkg.deb"

    /** Downloads the arm64 .deb of [pkg]'s latest release on GitHub to [githubDeb]. */
    private fun githubFetch(pkg: String, repo: String) = """
        command -v curl > /dev/null || $APT install -y --no-install-recommends curl ca-certificates || { $APT update && $APT install -y --no-install-recommends curl ca-certificates; }
        url=${'$'}(curl -fsSL https://api.github.com/repos/$repo/releases/latest | grep -o '"browser_download_url": *"[^"]*"' | cut -d '"' -f 4 | grep -E '(arm64|aarch64|arm-64)[^/]*\.deb${'$'}' | head -n 1)
        [ -n "${'$'}url" ] || { echo "No arm64 package in the latest release of $repo"; exit 1; }
        curl -fL --retry 2 -o ${githubDeb(pkg)} "${'$'}url"
    """.trimIndent()

    /** Translations installed along with a package, by the package (the language follows). */
    private val TRANSLATIONS = mapOf(
        "libreoffice-writer" to "libreoffice-l10n-",
        "thunderbird" to "thunderbird-l10n-",
        "firefox" to "firefox-l10n-",
    )

    /** Adds Debian's backports to apt's sources, once: apt takes nothing from them unless asked ("-t"). */
    private val BACKPORTS_SETUP = """
        if [ ! -s /etc/apt/sources.list.d/debian-backports.sources ]; then
            printf '%s\n' 'Types: deb' 'URIs: http://deb.debian.org/debian' 'Suites: ${BackportsCatalog.SUITE}' 'Components: main' 'Signed-By: /usr/share/keyrings/debian-archive-keyring.pgp' \
                > /etc/apt/sources.list.d/debian-backports.sources
            apt-get update -o Dir::Etc::sourcelist=sources.list.d/debian-backports.sources -o Dir::Etc::sourceparts=- -o APT::Get::List-Cleanup=0
        fi
    """.trimIndent()

    // apt waits for a desktop install (or another store job) instead of failing on dpkg's lock.
    private const val APT = "apt-get -o DPkg::Lock::Timeout=600"

    private val HEADER = """
        set -e
        export DEBIAN_FRONTEND=noninteractive
        ${DesktopEnvironment.NO_SYSTEM_BUS}
        # A job cancelled halfway leaves packages unpacked but not set up.
        dpkg --configure -a
    """.trimIndent()

    /**
     * Guest shell script installing [packages] (Debian names), with their
     * translations for [language]; needs network. Ends with [DONE_MARKER].
     */
    fun installScript(packages: List<String>, language: String): String {
        flatpakIds(packages).takeIf { it.isNotEmpty() }?.let { return flatpakInstallScript(it, DesktopEnvironment.FLATPAK_GL_SCRIPT) }
        val backports = packages.any { it.startsWith(BACKPORTS) }
        val names = packages.map { it.removePrefix(BACKPORTS) }.filter(::isPackageName)
        require(names.isNotEmpty()) { "no package to install" }
        val lang = language.takeIf { Regex("[a-z]{2,3}").matches(it) } ?: "en"
        val setup = (names.mapNotNull { VendorRepository.BY_PACKAGE[it]?.setupScript() } + listOfNotNull(BACKPORTS_SETUP.takeIf { backports }))
            .distinct().joinToString("\n")
        val fetch = names.filter { it in GITHUB }.joinToString("\n") { githubFetch(it, GITHUB.getValue(it)) }
        val translations = names.mapNotNull { TRANSLATIONS[it] }.distinct().joinToString(" ") { "$it$lang" }
        // A GitHub app is its downloaded file; "-t" takes the packages (and what they need) from backports.
        val list = names.joinToString(" ") { if (it in GITHUB) githubDeb(it) else it }
        val install = "$APT install -y --no-install-recommends" + if (backports) " -t ${BackportsCatalog.SUITE}" else ""
        return """
        $HEADER
        $setup
        $fetch
        # Lists from the desktop install can be old: the mirror no longer has those versions.
        $install $list || { $APT update && $install $list; }
        rm -f /tmp/droidnux-*.deb
        for t in $translations; do
            if apt-cache show ${'$'}t > /dev/null 2>&1; then $install ${'$'}t; fi
        done
        $APT clean
        echo $DONE_MARKER
        """.trimIndent()
    }

    /**
     * Guest shell script removing [packages] and what only they needed.
     * Refuses (with [KEEP_MARKER]) when that would take any of [keep] away.
     */
    fun removeScript(packages: List<String>, keep: List<String>): String {
        flatpakIds(packages).takeIf { it.isNotEmpty() }?.let { return flatpakRemoveScript(it) }
        val list = packages.map { it.removePrefix(BACKPORTS) }.filter(::isPackageName).joinToString(" ")
        require(list.isNotEmpty()) { "no package to remove" }
        return """
        $HEADER
        for p in ${'$'}($APT -s remove $list | awk '${'$'}1 == "Remv" { print ${'$'}2 }'); do
            case " ${keep.joinToString(" ")} " in *" ${'$'}p "*) echo "$KEEP_MARKER ${'$'}p"; exit 1 ;; esac
        done
        $APT remove -y $list
        $APT autoremove -y
        echo $DONE_MARKER
        """.trimIndent()
    }

    /** A package found by [searchCommand]. */
    data class Found(val name: String, val summary: String)

    /** Command searching Debian's packages for all the words of [query] (see [parseSearch]); null if nothing to search. */
    fun searchCommand(query: String): String? {
        val words = query.lowercase().split(' ').map { word -> word.filter { it.isLetterOrDigit() || it in "+-." } }
            .filter { it.length >= 2 }
        if (words.isEmpty()) return null
        val terms = words.joinToString(" ") { "'$it'" }
        // Name matches first, then descriptions; one line per package.
        return "{ apt-cache search --names-only $terms; apt-cache search $terms; } | awk '!seen[${'$'}1]++' | head -n 400"
    }

    /** The apps among [searchCommand]'s output: libraries, data and translations left out. */
    fun parseSearch(output: String): List<Found> = output.lineSequence().mapNotNull { line ->
        val name = line.substringBefore(" - ", "")
        if (!isPackageName(name) || NOT_APPS.containsMatchIn(name)) return@mapNotNull null
        Found(name, line.substringAfter(" - ").trim())
    }.take(MAX_FOUND).toList()

    private val NOT_APPS = Regex("""^lib|-(dev|doc|dbg|dbgsym|data|common|l10n|help|locale|i18n)(-|$)|^(fonts|gir1\.2|golang|node|python3?|ruby|r-cran|texlive)-""")
    private const val MAX_FOUND = 60

    /** Debian's AppStream icons, one "<package>_<icon>.png" for each app it has metadata for (AppCatalog's apps). */
    private const val ICONS_URL = "https://appstream.debian.org/data/trixie/main/icons-128x128.tar.gz"

    /** Icons Debian's archive lacks (or has only for a part of the app), from Flathub, by store icon name. */
    private val FLATHUB_ICONS = mapOf(
        "code" to "com.visualstudio.code",
        "darktable" to "org.darktable.Darktable",
        "libreoffice" to "org.libreoffice.LibreOffice",
    )

    private const val ICONS_DONE = ".done"

    /**
     * Downloads the store's icons into [dir] as "<name>.png" (blocking; about 11 MB, once).
     * Throws if a download fails: the icons already saved stay and the next call goes on.
     */
    fun downloadIcons(dir: File) {
        if (File(dir, ICONS_DONE).exists()) return
        dir.mkdirs()
        open(ICONS_URL).use { input ->
            TarArchiveInputStream(GZIPInputStream(input.buffered())).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    val name = entry.name.substringAfterLast('/').substringBefore('_')
                    // The first icon of a package is its app's (libreoffice-writer has one per package).
                    if (entry.isFile && isPackageName(name) && !File(dir, "$name.png").exists()) save(tar, File(dir, "$name.png"))
                }
            }
        }
        for ((name, id) in FLATHUB_ICONS) {
            open("https://dl.flathub.org/repo/appstream/aarch64/icons/128x128/$id.png").use { save(it, File(dir, "$name.png")) }
        }
        File(dir, ICONS_DONE).createNewFile()
    }

    internal fun open(url: String): InputStream {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}: $url")
        return connection.inputStream
    }

    /** Downloads [url] into [file] (blocking); throws if it fails. */
    fun download(url: String, file: File) {
        file.parentFile?.mkdirs()
        open(url).use { save(it, file) }
    }

    // Through a temporary file: a half-written icon is never read.
    private fun save(input: InputStream, file: File) {
        val temp = File(file.parentFile, "${file.name}.part")
        temp.outputStream().use { input.copyTo(it) }
        temp.renameTo(file)
    }

    /**
     * Packages dpkg has installed in [rootfs], read from its status file, and Flatpak's apps as [FLATPAK] ones;
     * a package from backports is there also as a [BACKPORTS] one.
     */
    fun installedPackages(rootfs: File): Set<String> = installedVersions(rootfs).flatMapTo(HashSet()) { (pkg, version) ->
        if ("~bpo" in version) listOf(pkg, BACKPORTS + pkg) else listOf(pkg)
    }

    /** [installedPackages] with their versions (empty for Flatpak's apps). */
    fun installedVersions(rootfs: File): Map<String, String> {
        val installed = HashMap<String, String>()
        for (dir in flatpakAppDirs(rootfs)) {
            dir.list()?.filter(::isFlatpakId)?.forEach { installed[FLATPAK + it] = "" }
        }
        val status = guestFile(rootfs, "var/lib/dpkg/status") ?: return installed
        var name = ""
        var version = ""
        var ok = false
        fun stanza() {
            if (ok && name.isNotEmpty()) installed[name] = version
            name = ""
            version = ""
            ok = false
        }
        status.forEachLine { line ->
            when {
                line.isEmpty() -> stanza()
                line.startsWith("Package: ") -> name = line.substring(9).trim()
                line.startsWith("Version: ") -> version = line.substring(9).trim()
                line == "Status: install ok installed" -> ok = true
            }
        }
        stanza()
        return installed
    }

    /**
     * The desktop id of the first shown launcher [pkg] installs ("org.gimp.GIMP"), or else one of its
     * dependencies does (keepassxc is only a dependency on keepassxc-full).
     */
    fun desktopId(rootfs: File, pkg: String): String? {
        if (pkg.startsWith(FLATPAK)) return pkg.removePrefix(FLATPAK).takeIf(::isFlatpakId)
        val name = pkg.removePrefix(BACKPORTS)
        return launcherOf(rootfs, name) ?: dependencies(rootfs, name).firstNotNullOfOrNull { launcherOf(rootfs, it) }
    }

    /** The packages [pkg] depends on, from dpkg's status file. */
    private fun dependencies(rootfs: File, pkg: String): List<String> {
        val status = guestFile(rootfs, "var/lib/dpkg/status") ?: return emptyList()
        var current = ""
        status.useLines { lines ->
            for (line in lines) {
                if (line.startsWith("Package: ")) current = line.substring(9).trim()
                if (current == pkg && line.startsWith("Depends: ")) {
                    return line.substring(9).split(',').map { it.trim().substringBefore(' ').substringBefore(':') }
                }
            }
        }
        return emptyList()
    }

    private fun launcherOf(rootfs: File, pkg: String): String? {
        if (!isPackageName(pkg)) return null
        val list = guestFile(rootfs, "var/lib/dpkg/info/$pkg.list") ?: guestFile(rootfs, "var/lib/dpkg/info/$pkg:arm64.list")
            ?: return null
        val ids = list.useLines { lines -> lines.mapNotNull { LAUNCHER.matchEntire(it)?.groupValues?.get(1) }.toList() }
        // VS Code's URL handler and the like are hidden launchers.
        return ids.firstOrNull { id ->
            guestFile(rootfs, "usr/share/applications/$id.desktop")?.useLines { lines -> lines.none { it.trim() == "NoDisplay=true" } } == true
        } ?: ids.firstOrNull()
    }

    private val LAUNCHER = Regex("""/usr/share/applications/([A-Za-z0-9._-]+)\.desktop""")

    /** Where Flatpak keeps its apps: system-wide and in each user's home. */
    private fun flatpakAppDirs(rootfs: File): List<File> {
        val homes = guestDir(rootfs, "home")?.listFiles().orEmpty().map { "home/${it.name}/.local/share/flatpak/app" }
        return (listOf("var/lib/flatpak/app") + homes).mapNotNull { guestDir(rootfs, it) }
    }

    private fun guestDir(rootfs: File, path: String): File? =
        File(rootfs.canonicalFile, path).takeIf { it.isDirectory && it.canonicalPath == it.path }

    // Read only real files inside the rootfs, never through a guest link.
    private fun guestFile(rootfs: File, path: String): File? =
        File(rootfs.canonicalFile, path).takeIf { it.isFile && it.canonicalPath == it.path }

    fun isPackageName(name: String) = PACKAGE_NAME.matches(name)
}
