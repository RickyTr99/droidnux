package app.droidnux.container

import java.io.File

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

    /** Vendor repositories, by the package that comes from them. */
    private val REPOSITORIES = mapOf("code" to "microsoft")

    /**
     * Microsoft's release key (fingerprint BC52 8686 B50D 79E3 39D3 721C EB3E 94AD BE12 29CF),
     * unarmored, where VS Code's own setup puts it: its postinst then keeps our source.
     */
    private const val MICROSOFT_KEY =
        "mQENBFYxWIwBCADAKoZhZlJxGNGWzqV+1OG1xiQeoowKhssGAKvd+buXCGISZJwTLXZqIcIiLP7pqdcZWtE9bSc7yBY2MalDp9Liu0Kek" +
            "ywQ6VVX1T72NPf5Ev6x6DLV7aVWsCzUAF+eb7DC9fPuFLEdxmOEYoPjzrQ7cCnSV4JQxAqhU4T6OjbvRazGl3agOeizPXmRljMtUUttHQZnRh" +
            "tlzkmwIrUivbfFPD+fEoHJ1+uIdfOzZX8/oKHKLe2jH632kvsNzJFlROVvGLYAk2WRcLu+RjjggixhwiB+Mu/A8Tf4V6b+YppS44q8EvVrM+Qv" +
            "Y7LNSOffSO6Slsy9oisGTdfE39nC7pVRABEBAAG0N01pY3Jvc29mdCAoUmVsZWFzZSBzaWduaW5nKSA8Z3Bnc2VjdXJpdHlAbWljcm9zb2Z0" +
            "LmNvbT6JATQEEwEIAB4FAlYxWIwCGwMGCwkIBwMCAxUIAwMWAgECHgECF4AACgkQ6z6Urb4SKc+P9gf/diY2900wvWEgV7iMgrtGzx79W/Pb" +
            "wWiOkKoD9sdzhARXWiP8Q5teL/t5TUH6TZ3BENboDjwr705jLLPwuEDtPI9jz4kvdT86JwwG6N8gnWM8Ldi56SdJEtXrzwtlB/Fe6tyfMT1E" +
            "/PrJfgALUG9MWTIJkc0GhRJoyPpGZ6YWSLGXnk4c0HltYKDFR7q4wtI84cBu4mjZHZbxIO6r8Cci+xxuJkpOTIpr4pdpQKpECM6x5SaT2gVn" +
            "scbN0PE19KK9nPsBxyK4wW0AvAhed2qldBPTipgzPhqB2gu0jSryil95bKrSmlYJd1Y1XfNHno5Dxfn5JwgySBIdWWvtOI05gw=="

    private val MICROSOFT_SETUP = """
        if [ ! -s /etc/apt/sources.list.d/vscode.sources ]; then
            apt-get install -y --no-install-recommends ca-certificates
            echo '$MICROSOFT_KEY' | base64 -d > /usr/share/keyrings/microsoft.gpg
            printf 'Types: deb\nURIs: https://packages.microsoft.com/repos/code\nSuites: stable\nComponents: main\nArchitectures: arm64\nSigned-By: /usr/share/keyrings/microsoft.gpg\n' \
                > /etc/apt/sources.list.d/vscode.sources
            apt-get update
        fi
    """.trimIndent()

    /** Translations installed along with a package, by the package (the language follows). */
    private val TRANSLATIONS = mapOf(
        "libreoffice-writer" to "libreoffice-l10n-",
        "thunderbird" to "thunderbird-l10n-",
    )

    // apt waits for a desktop install (or another store job) instead of failing on dpkg's lock.
    private const val APT = "apt-get -o DPkg::Lock::Timeout=600"

    private val HEADER = """
        set -e
        export DEBIAN_FRONTEND=noninteractive
        # A job cancelled halfway leaves packages unpacked but not set up.
        dpkg --configure -a
    """.trimIndent()

    /**
     * Guest shell script installing [packages] (Debian names), with their
     * translations for [language]; needs network. Ends with [DONE_MARKER].
     */
    fun installScript(packages: List<String>, language: String): String {
        val names = packages.filter(::isPackageName)
        require(names.isNotEmpty()) { "no package to install" }
        val lang = language.takeIf { Regex("[a-z]{2,3}").matches(it) } ?: "en"
        val setup = names.mapNotNull { REPOSITORIES[it] }.distinct().joinToString("\n") {
            when (it) {
                "microsoft" -> MICROSOFT_SETUP
                else -> error(it)
            }
        }
        val translations = names.mapNotNull { TRANSLATIONS[it] }.distinct().joinToString(" ") { "$it$lang" }
        val list = names.joinToString(" ")
        return """
        $HEADER
        $setup
        # Lists from the desktop install can be old: the mirror no longer has those versions.
        $APT install -y --no-install-recommends $list || { $APT update && $APT install -y --no-install-recommends $list; }
        for t in $translations; do
            if apt-cache show ${'$'}t > /dev/null 2>&1; then $APT install -y --no-install-recommends ${'$'}t; fi
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
        val list = packages.filter(::isPackageName).joinToString(" ")
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

    /** Packages dpkg has installed in [rootfs], read from its status file. */
    fun installedPackages(rootfs: File): Set<String> {
        val status = guestFile(rootfs, "var/lib/dpkg/status") ?: return emptySet()
        val installed = HashSet<String>()
        var name = ""
        status.forEachLine { line ->
            when {
                line.startsWith("Package: ") -> name = line.substring(9).trim()
                line == "Status: install ok installed" -> installed += name
            }
        }
        return installed
    }

    /**
     * The desktop id of the first shown launcher [pkg] installs ("org.gimp.GIMP"), or else one of its
     * dependencies does (keepassxc is only a dependency on keepassxc-full).
     */
    fun desktopId(rootfs: File, pkg: String): String? =
        launcherOf(rootfs, pkg) ?: dependencies(rootfs, pkg).firstNotNullOfOrNull { launcherOf(rootfs, it) }

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

    // Read only real files inside the rootfs, never through a guest link.
    private fun guestFile(rootfs: File, path: String): File? =
        File(rootfs.canonicalFile, path).takeIf { it.isFile && it.canonicalPath == it.path }

    fun isPackageName(name: String) = Regex("[a-z0-9][a-z0-9+.-]+").matches(name)
}
