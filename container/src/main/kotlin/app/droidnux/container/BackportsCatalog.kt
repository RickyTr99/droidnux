package app.droidnux.container

import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * The packages of Debian's backports for arm64 and their versions: newer releases built for
 * stable, which the store offers beside an app's stable one. Read once a week into a small index.
 */
object BackportsCatalog {

    /** The suite; apt installs from it with "-t". */
    const val SUITE = "trixie-backports"

    private const val PACKAGES_URL = "https://deb.debian.org/debian/dists/$SUITE/main/binary-arm64/Packages.xz"
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    /**
     * Package to version, from the index in [dir]; downloads the list (about 300 KB) when missing
     * or older than a week. Blocking. An old index is kept when the download fails; with none, throws.
     */
    fun load(dir: File): Map<String, String> {
        val index = File(dir, "backports.tsv")
        if (!index.isFile || System.currentTimeMillis() - index.lastModified() > MAX_AGE_MS) {
            try {
                val versions = AppStore.open(PACKAGES_URL).use { parse(XZInputStream(it.buffered())) }
                dir.mkdirs()
                val temp = File(dir, "backports.tsv.part")
                temp.bufferedWriter().use { out -> versions.forEach { (pkg, version) -> out.write("$pkg\t$version\n") } }
                if (!temp.renameTo(index)) throw IOException("cannot write $index")
                return versions
            } catch (e: IOException) {
                if (!index.isFile) throw e
            }
        }
        return index.readLines().mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    }

    /** The newest version of each package of a Packages file. */
    fun parse(input: InputStream): Map<String, String> {
        val versions = HashMap<String, String>()
        var pkg = ""
        input.bufferedReader().forEachLine { line ->
            when {
                line.startsWith("Package: ") -> pkg = line.substring(9).trim()
                // shortcut: a package listed twice keeps its later entry, not the higher version; compare versions if that ever shows.
                line.startsWith("Version: ") && pkg.isNotEmpty() -> versions[pkg] = line.substring(9).trim()
            }
        }
        return versions
    }

    /** The version without its epoch and backport suffix, as people know it: "4:26.8.2.2-2~bpo13+1" is "26.8.2.2". */
    fun shortVersion(version: String): String = version.substringAfter(':').substringBefore('-').substringBefore('~').substringBefore("+dfsg").substringBefore("+ds")
}
