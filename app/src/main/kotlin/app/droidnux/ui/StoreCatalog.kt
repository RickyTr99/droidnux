package app.droidnux.ui

import app.droidnux.R

enum class StoreCategory(val label: Int) {
    DEVELOPMENT(R.string.store_development),
    OFFICE(R.string.store_office),
    GRAPHICS(R.string.store_graphics),
    MEDIA(R.string.store_media),
    INTERNET(R.string.store_internet),
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
