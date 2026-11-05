package app.droidnux.desktop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.os.storage.StorageManager
import android.system.Os
import android.util.Log
import app.droidnux.container.GUEST_USB_DRIVES
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * The USB drives Android has mounted, as /media/<label> in the guest: links
 * into Burrow's binding of Android's mount points, kept in step with drives
 * being plugged in and removed.
 */
class RemovableDrives(private val context: Context, rootfs: File, private val scope: CoroutineScope) {

    private val media = File(rootfs, "media")
    // One update at a time: two would race on the same links.
    private val writer = Dispatchers.IO.limitedParallelism(1)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = update()
    }

    fun start() {
        update()
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        })
    }

    fun stop() {
        context.unregisterReceiver(receiver)
    }

    private fun update() {
        scope.launch(writer) { runCatching { link(mounted()) }.onFailure { Log.w(TAG, "drives: $it") } }
    }

    /** The guest path of each mounted removable volume, by label. */
    private fun mounted(): Map<String, String> {
        // The binding exists only with all-files access (Burrow.guestCommand).
        if (!Environment.isExternalStorageManager()) return emptyMap()
        val drives = linkedMapOf<String, String>()
        for (volume in context.getSystemService(StorageManager::class.java).storageVolumes) {
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

    private companion object {
        const val TAG = "DroidNux.Drives"
    }
}
