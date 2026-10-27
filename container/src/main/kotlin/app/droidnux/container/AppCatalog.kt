package app.droidnux.container

import org.tukaani.xz.XZInputStream
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Debian's AppStream catalog of desktop apps (DEP-11): texts in the
 * user's language, categories and screenshots of each. Read once into a
 * small index file; the store shows from that. Icons come from
 * [AppStore.downloadIcons], by package.
 */
object AppCatalog {

    /**
     * An app of the catalog. [screenshots] are URLs, the first one the main. [description] is
     * plain text, one paragraph or list item ("• ") per line. [keywords] is lowercase, for the search.
     */
    data class App(
        val id: String,
        val pkg: String,
        val name: String,
        val summary: String,
        val categories: List<String>,
        val screenshots: List<String>,
        val keywords: String,
        val developer: String,
        val homepage: String?,
        val description: String,
    )

    // AppStream's own copy: the archive's (dists/trixie/main/dep11) waits for point releases,
    // and its media URLs are gone once the catalog is generated again.
    private const val COMPONENTS_URL = "https://appstream.debian.org/data/trixie/main/Components-arm64.yml.xz"
    private const val MEDIA_URL = "https://appstream.debian.org/media/trixie/"

    /** Bumped when the index's columns change: an older index is read again from the archive. */
    private const val FORMAT = 2
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    /** The screenshot width the store wants, and how many of an app it keeps. */
    private const val SCREENSHOT_WIDTH = 752
    private const val MAX_SCREENSHOTS = 6

    /** Stands for a line break inside an index field. */
    private const val NEWLINE = '\u001E'

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
                        out.write(
                            listOf(
                                app.id, app.pkg, app.name, app.summary, app.categories.joinToString(";"),
                                app.screenshots.joinToString(" "), app.keywords, app.developer, app.homepage.orEmpty(),
                                app.description.replace('\n', NEWLINE),
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
                if (f.size < 10) return@mapNotNull null
                App(
                    f[0], f[1], f[2], f[3], f[4].split(';').filter { it.isNotEmpty() }, f[5].split(' ').filter { it.isNotEmpty() },
                    f[6], f[7], f[8].ifEmpty { null }, f[9].replace(NEWLINE, '\n'),
                )
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
        var homepage: String? = null
        val names = HashMap<String, String>()
        val summaries = HashMap<String, String>()
        val descriptions = HashMap<String, String>()
        val developers = HashMap<String, String>()
        val keywords = HashMap<String, MutableList<String>>()
        val categories = ArrayList<String>()
        val screenshots = ArrayList<String>()

        private var key = ""
        private var subKey = ""
        private var lastUrl: String? = null
        private var shots = 0
        private var shotWidth = 0

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
            val level2 = line.startsWith("  ") && !line.startsWith("   ")
            when (key) {
                "Name", "Summary", "Description", "DeveloperName" -> if (level2 && !line.startsWith("  - ")) {
                    val map = when (key) {
                        "Name" -> names
                        "Summary" -> summaries
                        "Description" -> descriptions
                        else -> developers
                    }
                    map[line.trim().substringBefore(':')] = text(line, reader, 4)
                }
                "Developer" -> when {
                    level2 -> subKey = line.trim().substringBefore(':')
                    subKey == "name" && line.startsWith("    ") && !line.startsWith("     ") ->
                        developers[line.trim().substringBefore(':')] = text(line, reader, 6)
                }
                "Url" -> if (level2 && line.trim().startsWith("homepage:")) homepage = scalar(line.substringAfter(':').trim())
                "Keywords" -> when {
                    line.startsWith("  - ") -> keywords.getOrPut(subKey) { ArrayList() } += unescape(scalar(line.substring(4).trim()))
                    level2 -> subKey = line.trim().substringBefore(':')
                }
                "Categories" -> if (line.startsWith("- ")) categories += line.substring(2).trim()
                "Screenshots" -> {
                    if (line.startsWith("- ")) {
                        shots++
                        if (shots <= MAX_SCREENSHOTS) screenshots += ""
                        shotWidth = 0
                    }
                    if (shots == 0 || shots > MAX_SCREENSHOTS) return
                    val trimmed = line.trim().removePrefix("- ")
                    when {
                        trimmed.startsWith("url: ") -> lastUrl = scalar(trimmed.substring(5).trim())
                        trimmed.startsWith("width: ") -> {
                            val width = trimmed.substring(7).trim().toIntOrNull() ?: return
                            val url = lastUrl ?: return
                            // The thumbnail closest to the store's width, the bigger one on a tie.
                            if (screenshots.last().isEmpty() || closer(width, shotWidth)) {
                                screenshots[screenshots.size - 1] = MEDIA_URL + url
                                shotWidth = width
                            }
                            lastUrl = null
                        }
                    }
                }
            }
        }

        /** A text value starting on [line]: it goes on over the lines indented by [indent] (or follows ">-", "|-"). */
        private fun text(line: String, reader: BufferedReader, indent: Int): String {
            val prefix = " ".repeat(indent)
            val parts = ArrayList<String>()
            line.substringAfter(':').trim().takeIf { it.isNotEmpty() && it != ">-" && it != "|-" && it != ">" && it != "|" }?.let(parts::add)
            while (true) {
                reader.mark(64 * 1024)
                val next = reader.readLine() ?: break
                if (next.isNotEmpty() && !next.startsWith(prefix)) {
                    reader.reset()
                    break
                }
                if (next.isNotBlank()) parts += next.trim()
            }
            return scalar(parts.joinToString(" "))
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
                id, pkg, clean(unescape(name)), clean(unescape(pick(summaries, language).orEmpty())), categories,
                screenshots.filter { it.isNotEmpty() }, clean(words), clean(unescape(pick(developers, language).orEmpty())),
                homepage?.takeIf { it.startsWith("https://") || it.startsWith("http://") }, plain(pick(descriptions, language).orEmpty()),
            )
        }

        private fun pick(texts: Map<String, String>, language: String): String? =
            texts[language] ?: texts["C"] ?: texts["en"]

        private fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ').replace(NEWLINE, ' ').trim()
    }

    /** AppStream's description markup (p, ul, ol, li, em, code) as plain text: a line per paragraph or item. */
    internal fun plain(html: String): String = html
        .replace(Regex("<li>"), "\n• ")
        .replace(Regex("</?(p|ul|ol)>|</li>"), "\n")
        .replace(Regex("<[^>]*>"), "")
        .let(::unescape)
        .lineSequence()
        .map { it.replace(Regex("\\s+"), " ").replace('\t', ' ').replace(NEWLINE, ' ').trim() }
        .filter { it.isNotEmpty() && it != "•" }
        .joinToString("\n")

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
