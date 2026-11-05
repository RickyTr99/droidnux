package app.droidnux.desktop

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.system.Os
import android.util.Log
import app.droidnux.container.DesktopEnvironment
import app.droidnux.container.GUEST_USB_DRIVES
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element

/**
 * The USB drives Android has mounted, as /media/<label> in the guest: links
 * into Burrow's binding of Android's mount points, kept in step with drives
 * being plugged in and removed, and in the places of Dolphin's sidebar.
 */
class RemovableDrives(
    private val context: Context,
    private val rootfs: File,
    private val user: String,
    private val scope: CoroutineScope,
) {

    private val storage = context.getSystemService(StorageManager::class.java)
    private val media = File(rootfs, "media")
    // One update at a time: two would race on the same links.
    private val writer = Dispatchers.IO.limitedParallelism(1)

    // USB drives are mounted invisible to apps, and Android sends no ACTION_MEDIA_* for them.
    private val callback by lazy {
        object : StorageManager.StorageVolumeCallback() {
            override fun onStateChanged(volume: StorageVolume) = update()
        }
    }

    // shortcut: Android 10 has no volume callback, its drives are those of the session's start.
    fun start() {
        update()
        if (Build.VERSION.SDK_INT >= 30) storage.registerStorageVolumeCallback(context.mainExecutor, callback)
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= 30) storage.unregisterStorageVolumeCallback(callback)
    }

    private fun update() {
        scope.launch(writer) {
            val drives = mounted()
            runCatching { link(drives) }.onFailure { Log.w(TAG, "drives: $it") }
            runCatching { place(drives) }.onFailure { Log.w(TAG, "places: $it") }
        }
    }

    /** The guest path of each mounted removable volume, by label. */
    private fun mounted(): Map<String, String> {
        // The binding exists only with all-files access (Burrow.guestCommand).
        if (!Environment.isExternalStorageManager()) return emptyMap()
        val drives = linkedMapOf<String, String>()
        for (volume in storage.storageVolumes) {
            val uuid = volume.uuid ?: continue
            if (!volume.isRemovable) continue
            if (volume.state != Environment.MEDIA_MOUNTED && volume.state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
            val base = volume.getDescription(context).replace('/', '_').trim().ifEmpty { uuid }
            var label = base
            var n = 2
            while (label in drives) label = "$base (${n++})"
            drives[label] = "$GUEST_USB_DRIVES/$uuid"
        }
        return drives
    }

    // Only our own links are touched, never followed: they point into the guest's view of the host.
    private fun link(drives: Map<String, String>) {
        media.mkdirs()
        for (name in media.list().orEmpty()) {
            val path = File(media, name).path
            val target = runCatching { Os.readlink(path) }.getOrNull() ?: continue
            if (target.startsWith("$GUEST_USB_DRIVES/") && drives[name] != target) Os.remove(path)
        }
        for ((label, target) in drives) {
            val path = File(media, label).path
            // A name the guest already uses (EEXIST) keeps its file; the other drives still get theirs.
            if (runCatching { Os.readlink(path) }.getOrNull() != target)
                runCatching { Os.symlink(target, path) }.onFailure { Log.w(TAG, "$label: $it") }
        }
        Log.i(TAG, "drives: ${drives.keys}")
    }

    /**
     * Each drive as a place, under the user's own (Solid's devices need
     * udisks): Dolphin reloads the file when it changes.
     */
    private fun place(drives: Map<String, String>) {
        val file = DesktopEnvironment.userPlaces(rootfs, user) ?: return
        val wanted = drives.keys.associateBy { "file:///media/" + Uri.encode(it) }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val root = doc.documentElement
        val bookmarks = root.getElementsByTagName("bookmark").let { list -> (0 until list.length).map { list.item(it) as Element } }
        val ours = bookmarks.filter { it.getElementsByTagName("ID").item(0)?.textContent?.startsWith(PLACE_ID) == true }
        val stale = ours.filter { it.getAttribute("href") !in wanted }
        val missing = wanted.keys - ours.map { it.getAttribute("href") }.toSet()
        if (stale.isEmpty() && missing.isEmpty()) return
        stale.forEach { root.removeChild(it) }
        for (href in missing) {
            root.appendChild(doc.createElement("bookmark").apply {
                setAttribute("href", href)
                appendChild(doc.createElement("title").apply { textContent = wanted.getValue(href) })
                appendChild(doc.createElement("info").apply {
                    appendChild(doc.createElement("metadata").apply {
                        setAttribute("owner", "http://freedesktop.org")
                        appendChild(doc.createElement("bookmark:icon").apply { setAttribute("name", "drive-removable-media-usb") })
                    })
                    appendChild(doc.createElement("metadata").apply {
                        setAttribute("owner", "http://www.kde.org")
                        appendChild(doc.createElement("ID").apply { textContent = PLACE_ID + href.substringAfterLast('/') })
                    })
                })
            })
        }
        // Whole or not at all: Dolphin may read it at any moment.
        val temporary = File(file.path + ".droidnux")
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(doc), StreamResult(temporary))
        Os.rename(temporary.path, file.path)
    }

    private companion object {
        const val TAG = "DroidNux.Drives"
        /** The KDE ID of the places made here. */
        const val PLACE_ID = "droidnux-usb/"
    }
}
