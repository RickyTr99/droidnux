package app.droidnux.container

import android.system.ErrnoException
import android.system.Os
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

sealed interface InstallProgress {
    data class Downloading(val bytes: Long, val total: Long) : InstallProgress
    data object Verifying : InstallProgress
    data class Extracting(val bytes: Long, val total: Long) : InstallProgress
}

class InstallException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Verified downloads and safe tar extraction, shared by the rootfs and the components. */
internal object Archive {

    private const val BUFFER_SIZE = 256 * 1024
    private const val PROGRESS_STEP = 512 * 1024L

    enum class Compression { GZIP, XZ }

    /**
     * Downloads [url] into [file] unless it is already there, and checks it
     * against [sha256]. A file that fails the check is deleted.
     */
    suspend fun download(url: String, sha256: String, sizeBytes: Long, file: File, onProgress: (InstallProgress) -> Unit) {
        file.parentFile?.mkdirs()
        if (file.isFile && file.length() == sizeBytes) {
            onProgress(InstallProgress.Verifying)
            if (sha256(file) == sha256) return
        }

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw InstallException("Download failed: HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: sizeBytes
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastReport = 0L
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (done - lastReport >= PROGRESS_STEP || done == total) {
                            lastReport = done
                            onProgress(InstallProgress.Downloading(done, total))
                        }
                    }
                }
            }
            val actual = digest.digest().toHex()
            if (actual != sha256) {
                file.delete()
                throw InstallException("Checksum mismatch: expected $sha256, got $actual")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Unpacks a tar [archive] into [root]. Entries can never land outside
     * [root], not even through symlinks the archive itself creates.
     */
    suspend fun extract(archive: File, compression: Compression, root: File, onProgress: (InstallProgress) -> Unit) {
        val rootPath = root.canonicalPath
        val total = archive.length()
        // Directory modes are applied last, so read-only directories can
        // still be filled while extracting.
        val directoryModes = mutableListOf<Pair<File, Int>>()
        var lastReport = 0L

        val counting = CountingInputStream(archive.inputStream().buffered(BUFFER_SIZE))
        val decompressed = when (compression) {
            Compression.GZIP -> GZIPInputStream(counting, BUFFER_SIZE)
            Compression.XZ -> XZCompressorInputStream(counting)
        }
        TarArchiveInputStream(decompressed).use { tar ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = tar.nextEntry ?: break
                val name = normalize(entry.name) ?: continue
                val dest = File(root, name)
                val parent = dest.parentFile!!
                // Refuse anything that would land outside the root through a
                // symlink planted by an earlier entry. Checked before any
                // directory is created.
                val checked = if (entry.isDirectory) dest else parent
                if (!checked.isInside(rootPath)) {
                    throw InstallException("Archive entry escapes $root: ${entry.name}")
                }
                parent.mkdirs()

                when {
                    entry.isDirectory -> {
                        dest.mkdirs()
                        directoryModes += dest to (entry.mode and 0x1FF or 0x1C0) // keep u+rwx
                    }
                    entry.isSymbolicLink -> {
                        dest.deleteIfLink()
                        Os.symlink(entry.linkName, dest.path)
                    }
                    entry.isLink -> {
                        // Hard links are often denied in app storage: copy instead.
                        val target = normalize(entry.linkName)?.let { File(root, it) }
                        if (target != null && target.isFile) {
                            target.copyTo(dest, overwrite = true)
                            chmod(dest, entry.fileMode())
                        }
                    }
                    entry.isFile -> {
                        dest.deleteIfLink()
                        dest.outputStream().use { tar.copyTo(it, BUFFER_SIZE) }
                        chmod(dest, entry.fileMode())
                    }
                    // Device nodes, FIFOs: not usable without root, skip.
                    else -> Unit
                }

                if (counting.count - lastReport >= PROGRESS_STEP) {
                    lastReport = counting.count
                    onProgress(InstallProgress.Extracting(counting.count, total))
                }
            }
        }
        for ((dir, mode) in directoryModes.asReversed()) chmod(dir, mode)
        onProgress(InstallProgress.Extracting(total, total))
    }

    /** Permission bits without setuid/setgid/sticky, always owner-readable and writable. */
    private fun TarArchiveEntry.fileMode() = (mode and 0x1FF) or 0x180

    /** Relative path inside the archive, or null for the root / unsafe names. */
    private fun normalize(name: String): String? {
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun chmod(file: File, mode: Int) {
        try {
            Os.chmod(file.path, mode)
        } catch (e: ErrnoException) {
            throw InstallException("chmod failed on $file", e)
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    fun File.readTextOrNull() = if (isFile) readText() else null

    /** Removes a file or symlink (never following it) at this path. */
    fun File.deleteIfLink() {
        try {
            Os.lstat(path)
            Os.remove(path)
        } catch (_: ErrnoException) {
        }
    }

    private fun File.isInside(rootPath: String): Boolean {
        val path = canonicalPath
        return path == rootPath || path.startsWith("$rootPath/")
    }

    /**
     * Deletes a tree without ever following symlinks (a rootfs is full of
     * absolute links that would resolve to host paths), making read-only
     * directories writable on the way down.
     */
    fun File.deleteRecursivelyForce() {
        val start = toPath()
        if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                runCatching { Os.chmod(dir.toString(), 0x1C0) }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}

private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) count += it }

    override fun skip(n: Long): Long = super.skip(n).also { count += it }
}
