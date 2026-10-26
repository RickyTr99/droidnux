package app.droidnux.ui

import app.droidnux.R

enum class StoreCategory(val label: Int) {
    DEVELOPMENT(R.string.store_development),
    OFFICE(R.string.store_office),
    GRAPHICS(R.string.store_graphics),
    MEDIA(R.string.store_media),
    INTERNET(R.string.store_internet),
    TOOLS(R.string.store_tools),
}

/**
 * An app of the store: [packages] are installed together, and the launcher
 * of the first one (or [launcher]) opens it. [sizeMb] is roughly what it adds
 * to a desktop install, [color] its tile until an icon is there. [icon] names its
 * store icon (see AppStore.downloadIcons).
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
    val icon: String = packages.first(),
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
        // Command line tools, for the terminal: no launcher, no icon.
        StoreApp("ssh", "SSH", R.string.store_tool_ssh, StoreCategory.TOOLS, listOf("openssh-client"), 10, 0xFF4D5B6A),
        StoreApp("curl", "curl", R.string.store_tool_curl, StoreCategory.TOOLS, listOf("curl"), 2, 0xFF0B6E8A),
        StoreApp("wget", "Wget", R.string.store_tool_wget, StoreCategory.TOOLS, listOf("wget"), 4, 0xFF5A6E3A),
        StoreApp("dig", "dig", R.string.store_tool_dig, StoreCategory.TOOLS, listOf("bind9-dnsutils"), 10, 0xFF6A4D8F),
        StoreApp("rsync", "rsync", R.string.store_tool_rsync, StoreCategory.TOOLS, listOf("rsync"), 2, 0xFF3A6E8F),
        StoreApp("git", "Git", R.string.store_tool_git, StoreCategory.TOOLS, listOf("git"), 50, 0xFFF05033),
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

    /** At the top of the store. */
    val FEATURED = listOf("code", "libreoffice", "gimp").mapNotNull(::get)

    /** The first-run choice: the most used ones (the first three chosen), then other proposals. */
    val WIZARD_POPULAR = listOf("code", "libreoffice", "gimp", "chromium").mapNotNull(::get)
    val WIZARD_MORE = listOf("inkscape", "krita", "thunderbird", "kdenlive").mapNotNull(::get)
    val WIZARD_DEFAULT = listOf("code", "libreoffice", "gimp")
}
