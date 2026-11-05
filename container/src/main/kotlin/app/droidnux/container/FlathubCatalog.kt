package app.droidnux.container

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Flathub's AppStream catalog of the apps built for arm64, as [AppCatalog.App]s whose id and
 * package are the Flatpak id ("org.kde.krita"). Read once a week into a small index, like
 * Debian's ([AppCatalog]); icons are downloaded one by one ([iconUrl]).
 */
object FlathubCatalog {

    private const val CATALOG_URL = "https://dl.flathub.org/repo/appstream/aarch64/appstream.xml.gz"
    private const val ICONS_URL = "https://dl.flathub.org/repo/appstream/aarch64/icons/128x128/"

    private const val FORMAT = 1
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
    private const val SCREENSHOT_WIDTH = 752
    private const val MAX_SCREENSHOTS = 6
    private const val NEWLINE = '\u001E'

    /** The 128-pixel icon of the app [id]. */
    fun iconUrl(id: String) = "$ICONS_URL$id.png"

    /**
     * The catalog in [language], from the index in [dir]; downloads it (about 10 MB) when
     * missing, older than a week or in another language. Blocking. An old index is kept
     * when the download fails; with none, throws.
     */
    fun load(dir: File, language: String): List<AppCatalog.App> {
        val index = File(dir, "flathub.tsv")
        val header = "$FORMAT\t$language"
        val current = index.isFile && index.bufferedReader().use { it.readLine() } == header
        if (!current || System.currentTimeMillis() - index.lastModified() > MAX_AGE_MS) {
            try {
                val apps = AppStore.open(CATALOG_URL).use { parse(GZIPInputStream(it.buffered()), language) }
                dir.mkdirs()
                val temp = File(dir, "flathub.tsv.part")
                temp.bufferedWriter().use { out ->
                    out.write(header)
                    out.newLine()
                    for (app in apps) {
                        out.write(
                            listOf(
                                app.id, app.name, app.summary, app.categories.joinToString(";"), app.screenshots.joinToString(" "),
                                app.keywords, app.developer, app.homepage.orEmpty(), app.description.replace('\n', NEWLINE),
                            ).joinToString("\t"),
                        )
                        out.newLine()
                    }
                }
                if (!temp.renameTo(index)) throw IOException("cannot write $index")
                return apps
            } catch (e: IOException) {
                if (!current) throw e
            }
        }
        return index.bufferedReader().useLines { lines ->
            lines.drop(1).mapNotNull { line ->
                val f = line.split('\t')
                if (f.size < 9) return@mapNotNull null
                AppCatalog.App(
                    f[0], f[0], f[1], f[2], f[3].split(';').filter { it.isNotEmpty() }, f[4].split(' ').filter { it.isNotEmpty() },
                    f[5], f[6], f[7].ifEmpty { null }, f[8].replace(NEWLINE, '\n'),
                )
            }.toList()
        }
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
                            if (c.shot?.first == null || closer(width, best)) c.shot = url to width
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = path.removeAt(path.size - 1)
                    val c = app ?: continue
                    if (tag == "screenshot" && path.size == 3) {
                        c.shot?.first?.let { if (c.screenshots.size < MAX_SCREENSHOTS) c.screenshots += it }
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

    private fun closer(width: Int, than: Int): Boolean {
        val d = kotlin.math.abs(width - SCREENSHOT_WIDTH)
        val e = kotlin.math.abs(than - SCREENSHOT_WIDTH)
        return d < e || (d == e && width > than)
    }

    private class Component(val type: String) {
        var id = ""
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
            val name = pick(names, language) ?: return null
            return AppCatalog.App(
                id, id, clean(name), clean(pick(summaries, language).orEmpty()), categories, screenshots,
                clean(keywords.distinct().joinToString(" ")), clean(pick(developers, language).orEmpty()),
                homepage?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
                pick(descriptions, language).orEmpty(),
            )
        }

        private fun pick(texts: Map<String, String>, language: String): String? = texts[language] ?: texts["C"] ?: texts["en"]

        private fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ').replace(NEWLINE, ' ').trim()
    }
}
