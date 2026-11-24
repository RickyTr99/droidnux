package app.droidnux.ui

import androidx.compose.ui.graphics.vector.ImageVector
import app.droidnux.R
import app.droidnux.container.AppCatalog
import app.droidnux.container.AppStore
import app.droidnux.container.BackportsCatalog

/**
 * The store's categories, each with the freedesktop categories it shows as filters
 * (by their name in .desktop files and AppStream).
 */
enum class StoreCategory(val label: Int, val icon: ImageVector, val filters: List<String> = emptyList()) {
    DEVELOPMENT(R.string.store_development, Icons.Code, listOf("IDE", "TextEditor", "WebDevelopment", "Database", "RevisionControl")),
    OFFICE(R.string.store_office, Icons.Document, listOf("WordProcessor", "Spreadsheet", "Presentation", "Finance", "Viewer", "Dictionary")),
    GRAPHICS(R.string.store_graphics, Icons.Palette, listOf("RasterGraphics", "VectorGraphics", "Photography", "3DGraphics", "Viewer", "Scanning")),
    MEDIA(R.string.store_media, Icons.Music, listOf("Audio", "Video", "Player", "AudioVideoEditing", "Music")),
    INTERNET(R.string.store_internet, Icons.Globe, listOf("WebBrowser", "Email", "Chat", "FileTransfer", "RemoteAccess")),
    GAMES(R.string.store_games, Icons.Gamepad, listOf("ActionGame", "ArcadeGame", "BoardGame", "CardGame", "LogicGame", "StrategyGame", "AdventureGame")),
    SCIENCE(R.string.store_science, Icons.Flask, listOf("Education", "Math", "Physics", "Chemistry", "Biology", "Astronomy", "Geography", "Electronics")),
    UTILITY(R.string.store_utility, Icons.Wrench, listOf("Archiving", "FileManager", "TerminalEmulator", "Monitor", "Settings", "Security", "TextEditor")),
    TOOLS(R.string.store_tools, Icons.Terminal),
    ;

    companion object {
        /** Where an app with these freedesktop [categories] goes: the first that matches, in this order. */
        fun of(categories: List<String>): StoreCategory = when {
            "Game" in categories -> GAMES
            "Development" in categories -> DEVELOPMENT
            "Office" in categories -> OFFICE
            "Graphics" in categories -> GRAPHICS
            categories.any { it == "AudioVideo" || it == "Audio" || it == "Video" } -> MEDIA
            "Network" in categories -> INTERNET
            "Education" in categories || "Science" in categories -> SCIENCE
            else -> UTILITY
        }

        /** The label of a freedesktop category used as a filter. */
        val FILTER_LABELS = mapOf(
            "IDE" to R.string.store_filter_ide,
            "TextEditor" to R.string.store_filter_editor,
            "WebDevelopment" to R.string.store_filter_web,
            "Database" to R.string.store_filter_database,
            "RevisionControl" to R.string.store_filter_revision,
            "WordProcessor" to R.string.store_filter_text,
            "Spreadsheet" to R.string.store_filter_spreadsheet,
            "Presentation" to R.string.store_filter_presentation,
            "Finance" to R.string.store_filter_finance,
            "Viewer" to R.string.store_filter_viewer,
            "Dictionary" to R.string.store_filter_dictionary,
            "RasterGraphics" to R.string.store_filter_raster,
            "VectorGraphics" to R.string.store_filter_vector,
            "Photography" to R.string.store_filter_photo,
            "3DGraphics" to R.string.store_filter_3d,
            "Scanning" to R.string.store_filter_scanning,
            "Audio" to R.string.store_filter_audio,
            "Video" to R.string.store_filter_video,
            "Player" to R.string.store_filter_player,
            "AudioVideoEditing" to R.string.store_filter_editing,
            "Music" to R.string.store_filter_music,
            "WebBrowser" to R.string.store_filter_browser,
            "Email" to R.string.store_filter_email,
            "Chat" to R.string.store_filter_chat,
            "FileTransfer" to R.string.store_filter_transfer,
            "RemoteAccess" to R.string.store_filter_remote,
            "ActionGame" to R.string.store_filter_action,
            "ArcadeGame" to R.string.store_filter_arcade,
            "BoardGame" to R.string.store_filter_board,
            "CardGame" to R.string.store_filter_card,
            "LogicGame" to R.string.store_filter_logic,
            "StrategyGame" to R.string.store_filter_strategy,
            "AdventureGame" to R.string.store_filter_adventure,
            "Education" to R.string.store_filter_education,
            "Math" to R.string.store_filter_math,
            "Physics" to R.string.store_filter_physics,
            "Chemistry" to R.string.store_filter_chemistry,
            "Biology" to R.string.store_filter_biology,
            "Astronomy" to R.string.store_filter_astronomy,
            "Geography" to R.string.store_filter_geography,
            "Electronics" to R.string.store_filter_electronics,
            "Archiving" to R.string.store_filter_archiving,
            "FileManager" to R.string.store_filter_files,
            "TerminalEmulator" to R.string.store_filter_terminal,
            "Monitor" to R.string.store_filter_monitor,
            "Settings" to R.string.store_filter_settings,
            "Security" to R.string.store_filter_security,
        )
    }
}

/**
 * An app of the store: [packages] are installed together, and the launcher
 * of the first one (or [launcher]) opens it. [sizeMb] is roughly what it adds
 * to a desktop install, [color] its tile until an icon is there. [icon] names its
 * store icon (see AppStore.downloadIcons). [flathub] is its Flatpak id when Flathub
 * has it for arm64: its page then shows Flathub's screenshots and text, and offers it.
 */
data class StoreApp(
    val id: String,
    val name: String,
    val summary: Int,
    val category: StoreCategory,
    val packages: List<String>,
    val sizeMb: Int,
    val color: Long,
    val vendor: Int? = null,
    val launcher: String? = null,
    val flathub: String? = null,
    val icon: String = flathub?.let { AppStore.FLATPAK + it } ?: packages.first(),
)

object StoreCatalog {
    val APPS = listOf(
        StoreApp("code", "Visual Studio Code", R.string.store_code, StoreCategory.DEVELOPMENT, listOf("code"), 1000, 0xFF2489CA, vendor = R.string.store_vendor_microsoft),
        StoreApp("kate", "Kate", R.string.store_kate, StoreCategory.DEVELOPMENT, listOf("kate"), 20, 0xFF3A6EA5),
        StoreApp("kdevelop", "KDevelop", R.string.store_kdevelop, StoreCategory.DEVELOPMENT, listOf("kdevelop"), 160, 0xFF4F6E8F),
        StoreApp(
            "libreoffice", "LibreOffice", R.string.store_libreoffice, StoreCategory.OFFICE,
            listOf("libreoffice-writer", "libreoffice-calc", "libreoffice-impress", "libreoffice-kf6"), 450, 0xFF18A303,
            launcher = "libreoffice-startcenter", icon = "libreoffice",
        ),
        StoreApp("keepassxc", "KeePassXC", R.string.store_keepassxc, StoreCategory.OFFICE, listOf("keepassxc"), 30, 0xFF5E9E3A, icon = "keepassxc-full"),
        StoreApp("gimp", "GIMP", R.string.store_gimp, StoreCategory.GRAPHICS, listOf("gimp"), 300, 0xFF6E5494),
        StoreApp("inkscape", "Inkscape", R.string.store_inkscape, StoreCategory.GRAPHICS, listOf("inkscape"), 200, 0xFF3B3B3B),
        StoreApp("krita", "Krita", R.string.store_krita, StoreCategory.GRAPHICS, listOf("krita"), 350, 0xFFC83E8A),
        StoreApp("darktable", "darktable", R.string.store_darktable, StoreCategory.GRAPHICS, listOf("darktable"), 150, 0xFF8F6A3A),
        StoreApp("blender", "Blender", R.string.store_blender, StoreCategory.GRAPHICS, listOf("blender"), 450, 0xFFE87D0D),
        StoreApp("vlc", "VLC", R.string.store_vlc, StoreCategory.MEDIA, listOf("vlc"), 120, 0xFFE85E00),
        StoreApp("kdenlive", "Kdenlive", R.string.store_kdenlive, StoreCategory.MEDIA, listOf("kdenlive"), 250, 0xFF3C5A7D),
        StoreApp("audacity", "Audacity", R.string.store_audacity, StoreCategory.MEDIA, listOf("audacity"), 80, 0xFF2C5FA8),
        StoreApp("elisa", "Elisa", R.string.store_elisa, StoreCategory.MEDIA, listOf("elisa"), 20, 0xFF2D9C8F),
        StoreApp("chromium", "Chromium", R.string.store_chromium, StoreCategory.INTERNET, listOf("chromium"), 330, 0xFF4A7FCF),
        StoreApp("thunderbird", "Thunderbird", R.string.store_thunderbird, StoreCategory.INTERNET, listOf("thunderbird"), 280, 0xFF2A6CB0),
        StoreApp("qbittorrent", "qBittorrent", R.string.store_qbittorrent, StoreCategory.INTERNET, listOf("qbittorrent"), 40, 0xFF3D7DCA),
        // From their vendors' apt repositories (AppStore.isFromVendor).
        StoreApp("firefox", "Firefox", R.string.store_firefox, StoreCategory.INTERNET, listOf("firefox"), 250, 0xFFE66000, R.string.store_vendor_mozilla, flathub = "org.mozilla.firefox"),
        StoreApp("brave", "Brave", R.string.store_brave, StoreCategory.INTERNET, listOf("brave-browser"), 450, 0xFFFB542B, R.string.store_vendor_brave, flathub = "com.brave.Browser"),
        StoreApp("sublime-text", "Sublime Text", R.string.store_sublime_text, StoreCategory.DEVELOPMENT, listOf("sublime-text"), 60, 0xFFFF9800, R.string.store_vendor_sublime, icon = "https://dl.flathub.org/repo/appstream/x86_64/icons/128x128/com.sublimetext.three.png"),
        StoreApp("sublime-merge", "Sublime Merge", R.string.store_sublime_merge, StoreCategory.DEVELOPMENT, listOf("sublime-merge"), 40, 0xFF00A1E0, R.string.store_vendor_sublime, flathub = "com.sublimemerge.App"),
        StoreApp("element", "Element", R.string.store_element, StoreCategory.INTERNET, listOf("element-desktop"), 400, 0xFF0DBD8B, R.string.store_vendor_element, flathub = "im.riot.Riot"),
        // From releases on GitHub (AppStore.isFromGitHub).
        StoreApp("vesktop", "Vesktop", R.string.store_vesktop, StoreCategory.INTERNET, listOf("vesktop"), 300, 0xFF5865F2, flathub = "dev.vencord.Vesktop"),
        StoreApp("localsend", "LocalSend", R.string.store_localsend, StoreCategory.UTILITY, listOf("localsend"), 60, 0xFF009688, flathub = "org.localsend.localsend_app"),
        StoreApp("feishin", "Feishin", R.string.store_feishin, StoreCategory.MEDIA, listOf("feishin"), 300, 0xFF7C4DFF, flathub = "org.jeffvli.feishin"),
        StoreApp("youtube-music", "YouTube Music", R.string.store_youtube_music, StoreCategory.MEDIA, listOf("youtube-music"), 300, 0xFFC4302B, icon = "https://raw.githubusercontent.com/pear-devs/pear-desktop/master/assets/generated/icons/png/512x512.png"),
        StoreApp("bruno", "Bruno", R.string.store_bruno, StoreCategory.DEVELOPMENT, listOf("bruno"), 350, 0xFFF4AA41, icon = "https://dl.flathub.org/repo/appstream/x86_64/icons/128x128/com.usebruno.Bruno.png"),
        StoreApp("beekeeper-studio", "Beekeeper Studio", R.string.store_beekeeper, StoreCategory.DEVELOPMENT, listOf("beekeeper-studio"), 400, 0xFFFAD83B, flathub = "io.beekeeperstudio.Studio"),
        StoreApp("pulsar", "Pulsar", R.string.store_pulsar, StoreCategory.DEVELOPMENT, listOf("pulsar"), 450, 0xFF5A4FB0, flathub = "dev.pulsar_edit.Pulsar"),
        StoreApp("drawio", "draw.io", R.string.store_drawio, StoreCategory.GRAPHICS, listOf("draw.io"), 350, 0xFFF08705, flathub = "com.jgraph.drawio.desktop"),
        StoreApp("siyuan", "SiYuan", R.string.store_siyuan, StoreCategory.OFFICE, listOf("siyuan"), 750, 0xFFD23F31, flathub = "org.b3log.siyuan"),
        StoreApp("mattermost", "Mattermost", R.string.store_mattermost, StoreCategory.INTERNET, listOf("mattermost-desktop"), 340, 0xFF1E325C, flathub = "com.mattermost.Desktop"),
        StoreApp("tabby", "Tabby", R.string.store_tabby, StoreCategory.DEVELOPMENT, listOf("tabby-terminal"), 440, 0xFF7A5CC4, icon = "https://raw.githubusercontent.com/Eugeny/tabby/master/build/icons/256x256.png"),
        StoreApp("motrix", "Motrix", R.string.store_motrix, StoreCategory.INTERNET, listOf("motrix"), 330, 0xFF5B5BF0, flathub = "net.agalwood.Motrix"),
        StoreApp("rustdesk", "RustDesk", R.string.store_rustdesk, StoreCategory.UTILITY, listOf("rustdesk"), 60, 0xFF024EFF, flathub = "com.rustdesk.RustDesk"),
        StoreApp("ferdium", "Ferdium", R.string.store_ferdium, StoreCategory.INTERNET, listOf("ferdium"), 500, 0xFF7367F0, flathub = "org.ferdium.Ferdium"),
        StoreApp("github-desktop", "GitHub Desktop", R.string.store_github_desktop, StoreCategory.DEVELOPMENT, listOf("github-desktop"), 370, 0xFF8A4FCF, flathub = "io.github.shiftey.Desktop"),
        // Command line tools, for the terminal: no launcher, no icon.
        StoreApp("ssh", "SSH", R.string.store_tool_ssh, StoreCategory.TOOLS, listOf("openssh-client"), 10, 0xFF4D5B6A),
        StoreApp("curl", "curl", R.string.store_tool_curl, StoreCategory.TOOLS, listOf("curl"), 2, 0xFF0B6E8A),
        StoreApp("wget", "Wget", R.string.store_tool_wget, StoreCategory.TOOLS, listOf("wget"), 4, 0xFF5A6E3A),
        StoreApp("dig", "dig", R.string.store_tool_dig, StoreCategory.TOOLS, listOf("bind9-dnsutils"), 10, 0xFF6A4D8F),
        StoreApp("rsync", "rsync", R.string.store_tool_rsync, StoreCategory.TOOLS, listOf("rsync"), 2, 0xFF3A6E8F),
        StoreApp("git", "Git", R.string.store_tool_git, StoreCategory.TOOLS, listOf("git"), 50, 0xFFF05033),
        StoreApp("gh", "GitHub CLI", R.string.store_tool_gh, StoreCategory.TOOLS, listOf("gh"), 40, 0xFF24292F, R.string.store_vendor_github),
        StoreApp("build-essential", "build-essential", R.string.store_tool_build_essential, StoreCategory.TOOLS, listOf("build-essential"), 250, 0xFF5C6370),
        StoreApp("pip", "pip", R.string.store_tool_pip, StoreCategory.TOOLS, listOf("python3-pip"), 60, 0xFF3776AB),
        StoreApp("nodejs", "Node.js", R.string.store_tool_nodejs, StoreCategory.TOOLS, listOf("nodejs", "npm"), 120, 0xFF5FA04E),
        StoreApp("golang", "Go", R.string.store_tool_golang, StoreCategory.TOOLS, listOf("golang-go"), 300, 0xFF00ADD8),
        StoreApp("rust", "Rust", R.string.store_tool_rust, StoreCategory.TOOLS, listOf("rustc", "cargo"), 400, 0xFFB7410E),
        StoreApp("java21", "Java 21", R.string.store_tool_java21, StoreCategory.TOOLS, listOf("default-jre"), 200, 0xFFE76F00),
        StoreApp("java25", "Java 25", R.string.store_tool_java25, StoreCategory.TOOLS, listOf("openjdk-25-jre"), 200, 0xFFC75B00),
        StoreApp("htop", "htop", R.string.store_tool_htop, StoreCategory.TOOLS, listOf("htop"), 1, 0xFF3A8F5E),
        StoreApp("fastfetch", "fastfetch", R.string.store_tool_fastfetch, StoreCategory.TOOLS, listOf("fastfetch"), 2, 0xFF4A6FA5),
        StoreApp("tmux", "tmux", R.string.store_tool_tmux, StoreCategory.TOOLS, listOf("tmux"), 2, 0xFF1BB91F),
        StoreApp("vim", "Vim", R.string.store_tool_vim, StoreCategory.TOOLS, listOf("vim"), 40, 0xFF019733),
        StoreApp("7zip", "7-Zip", R.string.store_tool_7zip, StoreCategory.TOOLS, listOf("7zip"), 6, 0xFF3B3B3B),
        StoreApp("zip", "zip + unzip", R.string.store_tool_zip, StoreCategory.TOOLS, listOf("zip", "unzip"), 1, 0xFF6E6E3A),
        StoreApp("ffmpeg", "FFmpeg", R.string.store_tool_ffmpeg, StoreCategory.TOOLS, listOf("ffmpeg"), 100, 0xFF007808),
        StoreApp("yt-dlp", "yt-dlp", R.string.store_tool_yt_dlp, StoreCategory.TOOLS, listOf("yt-dlp"), 30, 0xFFC4302B),
    )

    private val BY_ID = APPS.associateBy { it.id }

    fun get(id: String): StoreApp? = BY_ID[id]

    /** The store's banner. */
    val FEATURED = listOf("code", "libreoffice", "gimp", "krita", "blender", "kdenlive").mapNotNull(::get)

    /** The apps the home suggests, besides those from vendors and GitHub (which have their own shelves). */
    val SUGGESTED = listOf("libreoffice", "gimp", "krita", "inkscape", "kdenlive", "vlc", "thunderbird", "keepassxc").mapNotNull(::get)

    /** The first-run choice: the most used ones (the first three chosen), then other proposals. */
    val WIZARD_POPULAR = listOf("code", "libreoffice", "gimp", "chromium").mapNotNull(::get)
    val WIZARD_MORE = listOf("inkscape", "krita", "thunderbird", "kdenlive").mapNotNull(::get)
    val WIZARD_DEFAULT = listOf("code", "libreoffice", "gimp")
}

/** Where an app comes from, as its page and lists name it. */
enum class StoreSource(val label: Int) {
    DEBIAN(R.string.store_vendor_debian),
    BACKPORTS(R.string.store_source_backports),
    VENDOR(R.string.store_source_vendor),
    GITHUB(R.string.store_source_github),
    FLATHUB(R.string.store_source_flathub),
}

/**
 * What the store shows: one of its own apps ([app]), with the catalog entry for it when
 * there is one ([catalog]), or an app of a catalog alone. [key] names its apt jobs: the
 * store's id, or the package. The same app from other sources is in [alternatives]: Debian's
 * backports ("backports:<package>", at [version]) and Flathub ("flatpak:<id>").
 */
data class StoreItem(
    val key: String,
    val name: String,
    /** The catalog's summary; the store's own apps have theirs in [StoreApp.summary]. */
    val summary: String?,
    val packages: List<String>,
    val category: StoreCategory,
    val app: StoreApp? = null,
    val catalog: AppCatalog.App? = null,
    val alternatives: List<StoreItem> = emptyList(),
    val version: String? = null,
) {
    val source: StoreSource
        get() {
            val pkg = packages.first()
            return when {
                pkg.startsWith(AppStore.FLATPAK) -> StoreSource.FLATHUB
                pkg.startsWith(AppStore.BACKPORTS) -> StoreSource.BACKPORTS
                AppStore.isFromGitHub(pkg) -> StoreSource.GITHUB
                AppStore.isFromVendor(pkg) -> StoreSource.VENDOR
                else -> StoreSource.DEBIAN
            }
        }

    val flathub: Boolean get() = source == StoreSource.FLATHUB

    /** This item and its [alternatives]: the choice its page offers. */
    val sources: List<StoreItem> get() = listOf(this) + alternatives

    /** The store icon's name (see AppStore.downloadIcons); a Flathub app's is its AppStream id, the [key]. */
    val icon: String get() = app?.icon ?: if (flathub) key else packages.first().removePrefix(AppStore.BACKPORTS)

    /** Whether this source of the app is the one installed: Debian's stable package is not when backports' is. */
    fun isInstalled(installed: Set<String>): Boolean {
        val pkg = packages.first()
        return pkg in installed && (source != StoreSource.DEBIAN || AppStore.BACKPORTS + pkg !in installed)
    }

    companion object {
        fun curated(catalog: Map<String, AppCatalog.App>, flathub: Map<String, AppCatalog.App> = emptyMap()): List<StoreItem> =
            StoreCatalog.APPS.map { app ->
                val entry = catalog[app.icon] ?: catalog[app.packages.first()] ?: app.flathub?.let { flathub[it] }
                StoreItem(app.id, app.name, null, app.packages, app.category, app, entry)
            }

        /**
         * The store's apps, then the rest of Debian's catalog, then Flathub's apps Debian lacks: those
         * with screenshots first, by name. An app also on Flathub (the same AppStream id, or the
         * store's [StoreApp.flathub]) or in Debian's [backports] (package to version) has them as
         * its [alternatives].
         */
        fun all(catalog: List<AppCatalog.App>, flathub: List<AppCatalog.App> = emptyList(), backports: Map<String, String> = emptyMap()): List<StoreItem> {
            val byPackage = LinkedHashMap<String, AppCatalog.App>()
            for (app in catalog) byPackage.putIfAbsent(app.pkg, app)
            val flatpaks = flathub.associateBy { it.id.removeSuffix(".desktop") }
            val flatpakIds = flathub.associateBy { it.pkg }
            val curated = curated(byPackage, flatpakIds)
            val taken = StoreCatalog.APPS.flatMapTo(HashSet()) { it.packages }
            val order = compareBy<AppCatalog.App>({ it.screenshots.isEmpty() }, { it.name.lowercase() })
            val rest = byPackage.values.filter { it.pkg !in taken }.sortedWith(order)
                .map { StoreItem(it.pkg, it.name, it.summary, listOf(it.pkg), StoreCategory.of(it.categories), catalog = it) }
            fun flatpakItem(app: AppCatalog.App) =
                StoreItem(AppStore.FLATPAK + app.id, app.name, app.summary, listOf(AppStore.FLATPAK + app.pkg), StoreCategory.of(app.categories), catalog = app)
            fun backportsItem(item: StoreItem) = backports[item.packages.first()]?.let { version ->
                item.copy(
                    key = AppStore.BACKPORTS + item.key,
                    packages = item.packages.map { if (it in backports) AppStore.BACKPORTS + it else it },
                    version = BackportsCatalog.shortVersion(version),
                )
            }
            val paired = HashSet<String>()
            val listed = (curated + rest).map { item ->
                val flatpak = (item.app?.flathub ?: item.catalog?.id?.removeSuffix(".desktop"))?.let { flatpaks[it] ?: flatpakIds[it] }
                flatpak?.let { paired += it.id }
                val backport = if (item.source == StoreSource.DEBIAN) backportsItem(item) else null
                item.copy(alternatives = listOfNotNull(backport, flatpak?.let(::flatpakItem)))
            }
            return listed + flathub.filter { it.id !in paired }.sortedWith(order).map(::flatpakItem)
        }

        /**
         * The [items] that have every word of [query] in their name, summary, keywords or package:
         * name matches first. [string] reads the store's own summaries.
         */
        fun search(items: List<StoreItem>, query: String, string: (Int) -> String): List<StoreItem> {
            val words = query.lowercase().split(' ').filter { it.isNotBlank() }
            if (words.isEmpty()) return emptyList()
            return items.mapNotNull { item ->
                val name = item.name.lowercase()
                val text = listOf(
                    name, (item.summary ?: item.app?.let { string(it.summary) }).orEmpty().lowercase(),
                    item.catalog?.keywords.orEmpty(), item.packages.joinToString(" "),
                ).joinToString(" ")
                if (words.any { it !in text }) return@mapNotNull null
                val rank = when {
                    name.startsWith(words.first()) -> 0
                    words.all { it in name } -> 1
                    else -> 2
                }
                rank to item
            }.sortedBy { it.first }.map { it.second }.take(MAX_RESULTS)
        }

        private const val MAX_RESULTS = 200
    }
}
