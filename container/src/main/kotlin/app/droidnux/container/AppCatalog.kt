package app.droidnux.container

import org.tukaani.xz.XZInputStream
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Debian's AppStream catalog of desktop apps (DEP-11): name, summary,
 * categories and screenshot of each, in the user's language. Read once
 * into a small index file; the store shows from that. Icons come from
 * [AppStore.downloadIcons], by package.
 */
object AppCatalog {

    /** An app of the catalog. [screenshot] is a URL, or null. [keywords] is lowercase, for the search. */
    data class App(
        val id: String,
        val pkg: String,
        val name: String,
        val summary: String,
        val categories: List<String>,
        val screenshot: String?,
        val keywords: String,
    )

    // AppStream's own copy: the archive's (dists/trixie/main/dep11) waits for point releases,
    // and its media URLs are gone once the catalog is generated again.
    private const val COMPONENTS_URL = "https://appstream.debian.org/data/trixie/main/Components-arm64.yml.xz"
    private const val MEDIA_URL = "https://appstream.debian.org/media/trixie/"

    /** Bumped when the index's columns change: an older index is read again from the archive. */
    private const val FORMAT = 1
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    /** The screenshot width the store's banner wants. */
    private const val SCREENSHOT_WIDTH = 752

    /**
     * The catalog in [language], from the index in [dir]; downloads it (about 5 MB) when
     * missing, older than a week or in another language. Blocking. An old index is
     * kept when the download fails; with none, throws.
     */
    fun load(dir: File, language: String): List<App> {
        val index = File(dir, "catalog.tsv")
        val header = "$FORMAT\t$language"
        val current = index.isFile && index.bufferedReader().use { it.readLine() } == header
        if (!current || System.currentTimeMillis() - index.lastModified() > MAX_AGE_MS) {
            try {
                val apps = AppStore.open(COMPONENTS_URL).use { parse(XZInputStream(it.buffered()), language) }
                dir.mkdirs()
                val temp = File(dir, "catalog.tsv.part")
                temp.bufferedWriter().use { out ->
                    out.write(header)
                    out.newLine()
                    for (app in apps) {
                        out.write(listOf(app.id, app.pkg, app.name, app.summary, app.categories.joinToString(";"),
                            app.screenshot.orEmpty(), app.keywords).joinToString("\t"))
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
                if (f.size < 7) return@mapNotNull null
                App(f[0], f[1], f[2], f[3], f[4].split(';').filter { it.isNotEmpty() }, f[5].ifEmpty { null }, f[6])
            }.toList()
        }
    }

    /**
     * The desktop apps of a DEP-11 YAML stream, texts in [language] (else the untranslated ones).
     * Not a YAML parser: DEP-11 is machine-written, one key per line, two spaces per level.
     */
    fun parse(input: InputStream, language: String): List<App> {
        val apps = ArrayList<App>()
        val reader = input.bufferedReader()
        var doc = Doc()
        while (true) {
            val line = reader.readLine()
            if (line == null || line == "---") {
                doc.app(language)?.let(apps::add)
                if (line == null) break
                doc = Doc()
                continue
            }
            doc.line(line, reader)
        }
        return apps
    }

    /** One component of the stream, its fields as they come. */
    private class Doc {
        var type = ""
        var id = ""
        var pkg = ""
        var extends = false
        val names = HashMap<String, String>()
        val summaries = HashMap<String, String>()
        val keywords = HashMap<String, MutableList<String>>()
        val categories = ArrayList<String>()
        var screenshot: String? = null
        var screenshotWidth = 0

        private var key = ""
        private var subKey = ""
        private var screenshots = 0
        private var lastUrl: String? = null

        fun line(line: String, reader: BufferedReader) {
            if (line.isEmpty()) return
            if (line[0] != ' ' && line[0] != '-') {
                key = line.substringBefore(':')
                subKey = ""
                val value = scalar(line.substringAfter(':').trim())
                when (key) {
                    "Type" -> type = value
                    "ID" -> id = value
                    "Package" -> pkg = value
                    "Extends" -> extends = true
                }
                return
            }
            when (key) {
                "Name", "Summary" -> if (line.startsWith("  ") && !line.startsWith("   ")) {
                    val lang = line.trim().substringBefore(':')
                    var value = line.substringAfter(':').trim()
                    // A long text goes on over the lines indented deeper (or follows ">-").
                    reader.mark(64 * 1024)
                    while (true) {
                        val next = reader.readLine() ?: break
                        if (!next.startsWith("    ")) { reader.reset(); break }
                        value = if (value == ">-" || value == "|-" || value.isEmpty()) next.trim() else "$value ${next.trim()}"
                        reader.mark(64 * 1024)
                    }
                    (if (key == "Name") names else summaries)[lang] = unescape(scalar(value))
                }
                "Keywords" -> when {
                    line.startsWith("  - ") -> keywords.getOrPut(subKey) { ArrayList() } += unescape(scalar(line.substring(4).trim()))
                    line.startsWith("  ") && !line.startsWith("   ") -> subKey = line.trim().substringBefore(':')
                }
                "Categories" -> if (line.startsWith("- ")) categories += line.substring(2).trim()
                "Screenshots" -> {
                    if (line.startsWith("- ")) screenshots++
                    if (screenshots != 1) return
                    val trimmed = line.trim().removePrefix("- ")
                    when {
                        trimmed.startsWith("url: ") -> lastUrl = scalar(trimmed.substring(5).trim())
                        trimmed.startsWith("width: ") -> {
                            val width = trimmed.substring(7).trim().toIntOrNull() ?: return
                            val url = lastUrl ?: return
                            // The thumbnail closest to the banner's width, the bigger one on a tie.
                            if (screenshot == null || closer(width, screenshotWidth)) {
                                screenshot = MEDIA_URL + url
                                screenshotWidth = width
                            }
                            lastUrl = null
                        }
                    }
                }
            }
        }

        private fun closer(width: Int, than: Int): Boolean {
            val d = kotlin.math.abs(width - SCREENSHOT_WIDTH)
            val e = kotlin.math.abs(than - SCREENSHOT_WIDTH)
            return d < e || (d == e && width > than)
        }

        fun app(language: String): App? {
            if (type != "desktop-application" || pkg.isEmpty() || extends || id.isEmpty()) return null
            val name = pick(names, language) ?: return null
            val words = (keywords["C"].orEmpty() + keywords[language].orEmpty()).map { it.lowercase() }.distinct().joinToString(" ")
            return App(
                id, pkg, clean(name), clean(pick(summaries, language).orEmpty()), categories, screenshot,
                clean(words).lowercase(),
            )
        }

        private fun pick(texts: Map<String, String>, language: String): String? =
            texts[language] ?: texts["C"] ?: texts["en"]

        private fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ').trim()
    }

    /** A YAML scalar without its quotes. */
    private fun scalar(value: String): String = when {
        value.length >= 2 && value.startsWith('\'') && value.endsWith('\'') -> value.substring(1, value.length - 1).replace("''", "'")
        value.length >= 2 && value.startsWith('"') && value.endsWith('"') ->
            value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        else -> value
    }

    private fun unescape(text: String) = text.replace("&apos;", "'").replace("&quot;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
