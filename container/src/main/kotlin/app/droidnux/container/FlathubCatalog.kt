package app.droidnux.container

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Flathub's AppStream catalog of the apps built for arm64, as [AppCatalog.App]s whose id is the
 * AppStream one ("org.kde.kmplot.desktop" for some, which also names the icon) and whose package
 * is the Flatpak id ("org.kde.kmplot"). Read once a week into a small index, like
 * Debian's ([AppCatalog]); icons are downloaded one by one ([iconUrl]).
 */
object FlathubCatalog {

    private const val CATALOG_URL = "https://dl.flathub.org/repo/appstream/aarch64/appstream.xml.gz"
    private const val ICONS_URL = "https://dl.flathub.org/repo/appstream/aarch64/icons/128x128/"

    /** The 128-pixel icon of the app [id]. */
    fun iconUrl(id: String) = "$ICONS_URL$id.png"

    /**
     * The catalog in [language], from the index in [dir]; downloads it (about 10 MB) when
     * missing, older than a week or in another language. Blocking. An old index is kept
     * when the download fails; with none, throws.
     */
    fun load(dir: File, language: String): List<AppCatalog.App> = AppCatalog.loadIndex(File(dir, "flathub.tsv"), language) {
        AppStore.open(CATALOG_URL).use { parse(GZIPInputStream(it.buffered()), language) }
    }

    /** The desktop apps of an AppStream XML catalog, texts in [language] (else the untranslated ones). */
    fun parse(input: InputStream, language: String): List<AppCatalog.App> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val apps = ArrayList<AppCatalog.App>()
        var app: Component? = null
        val path = ArrayList<String>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> {
                    val tag = parser.name
                    path += tag
                    if (tag == "component" && path.size == 2) {
                        app = Component(parser.getAttributeValue(null, "type").orEmpty())
                        continue
                    }
                    val c = app ?: continue
                    val lang = parser.getAttributeValue(null, "xml:lang") ?: "C"
                    when {
                        // Text-only elements, read whole.
                        path.size == 3 && tag in TEXTS -> {
                            val text = parser.nextText().trim()
                            path.removeAt(path.size - 1)
                            when (tag) {
                                "id" -> c.id = text
                                "name" -> c.names[lang] = text
                                "summary" -> c.summaries[lang] = text
                                "developer_name" -> c.developers.putIfAbsent(lang, text)
                                "url" -> if (parser.getAttributeValue(null, "type") == "homepage") c.homepage = text
                            }
                        }
                        path.size == 3 && tag == "bundle" && parser.getAttributeValue(null, "type") == "flatpak" -> {
                            // "app/org.kde.kmplot/aarch64/stable"
                            c.bundle = parser.nextText().trim().split('/').getOrNull(1)?.takeIf(AppStore::isFlatpakId)
                            path.removeAt(path.size - 1)
                        }
                        path.size == 3 && tag == "description" -> {
                            for ((l, text) in markup(parser, lang)) c.descriptions[l] = text
                            path.removeAt(path.size - 1)
                        }
                        tag == "name" && path.size == 4 && path[2] == "developer" -> {
                            c.developers[lang] = parser.nextText().trim()
                            path.removeAt(path.size - 1)
                        }
                        tag == "category" && path.size == 4 -> {
                            c.categories += parser.nextText().trim()
                            path.removeAt(path.size - 1)
                        }
                        tag == "keyword" && path.size == 4 -> {
                            c.keywords += parser.nextText().trim().lowercase()
                            path.removeAt(path.size - 1)
                        }
                        tag == "screenshot" && path.size == 4 -> c.shot = null to 0
                        tag == "image" && path.size == 5 && parser.getAttributeValue(null, "type") == "thumbnail" -> {
                            val width = parser.getAttributeValue(null, "width")?.toIntOrNull() ?: 0
                            val url = parser.nextText().trim()
                            path.removeAt(path.size - 1)
                            // The thumbnail closest to the store's width, the bigger one on a tie.
                            val (_, best) = c.shot ?: (null to 0)
                            if (c.shot?.first == null || AppCatalog.closer(width, best)) c.shot = url to width
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = path.removeAt(path.size - 1)
                    val c = app ?: continue
                    if (tag == "screenshot" && path.size == 3) {
                        c.shot?.first?.let { if (c.screenshots.size < AppCatalog.MAX_SCREENSHOTS) c.screenshots += it }
                        c.shot = null
                    } else if (tag == "component" && path.size == 1) {
                        c.app(language)?.let(apps::add)
                        app = null
                    }
                }
            }
        }
        return apps
    }

    private val TEXTS = setOf("id", "name", "summary", "developer_name", "url")

    /**
     * The markup of the element the parser is in (p, ul, li, em, code) up to its end, as plain
     * text by language: older catalogs translate each paragraph inside one description
     * (<p xml:lang="it">), the element's own language [lang] stands for the rest.
     */
    private fun markup(parser: XmlPullParser, lang: String): Map<String, String> {
        val out = LinkedHashMap<String, StringBuilder>()
        val langs = ArrayList<String>().apply { add(lang) }
        while (langs.isNotEmpty()) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    langs += parser.getAttributeValue(null, "xml:lang") ?: langs.last()
                    out.getOrPut(langs.last()) { StringBuilder() }.append('<').append(parser.name).append('>')
                }
                XmlPullParser.END_TAG -> {
                    val l = langs.removeAt(langs.size - 1)
                    if (langs.isNotEmpty()) out.getOrPut(l) { StringBuilder() }.append("</").append(parser.name).append('>')
                }
                XmlPullParser.TEXT ->
                    out.getOrPut(langs.last()) { StringBuilder() }.append(parser.text.replace("&", "&amp;").replace("<", "&lt;"))
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return out.mapValues { AppCatalog.plain(it.value.toString()) }.filterValues { it.isNotEmpty() }
    }

    private class Component(val type: String) {
        var id = ""
        var bundle: String? = null
        var homepage: String? = null
        val names = HashMap<String, String>()
        val summaries = HashMap<String, String>()
        val descriptions = HashMap<String, String>()
        val developers = HashMap<String, String>()
        val categories = ArrayList<String>()
        val keywords = ArrayList<String>()
        val screenshots = ArrayList<String>()
        var shot: Pair<String?, Int>? = null

        fun app(language: String): AppCatalog.App? {
            if (type != "desktop-application" && type != "desktop" || id.isEmpty()) return null
            val name = AppCatalog.pick(names, language) ?: return null
            return AppCatalog.App(
                id, bundle ?: id, AppCatalog.clean(name), AppCatalog.clean(AppCatalog.pick(summaries, language).orEmpty()), categories, screenshots,
                AppCatalog.clean(keywords.distinct().joinToString(" ")), AppCatalog.clean(AppCatalog.pick(developers, language).orEmpty()),
                homepage?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
                AppCatalog.pick(descriptions, language).orEmpty(),
            )
        }
    }
}
