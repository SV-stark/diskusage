package com.google.android.diskusage.filesystem

import android.app.ProgressDialog
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import com.google.android.diskusage.R
import com.google.android.diskusage.core.Scanner
import com.google.android.diskusage.datasource.fast.LegacyFileImpl
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.entity.FileSystemPackage
import com.google.android.diskusage.filesystem.mnt.MountPoint
import com.google.android.diskusage.ui.DiskUsage
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.resources.appStr
import splitties.toast.longToast
import splitties.toast.toast
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * Handles background deletion of files/directories.
 *
 * The actual IO deletion runs in GlobalScope (not lifecycleScope) so that
 * the file deletion cannot be cancelled if the user rotates the screen or
 * temporarily pauses the activity. UI updates (dialog dismissal, repaints)
 * always run on the Main dispatcher via withContext.
 */
class BackgroundDelete private constructor(
    private val diskUsage: DiskUsage,
    private val entry: FileSystemEntry
) {

    private var dialog: ProgressDialog? = null
    private val file: File
    private val path: String

    @Volatile
    private var cancelDeletion = false
    private var numDeletedDirectories = 0
    private var numDeletedFiles = 0

    init {
        path = entry.path2()
        val deleteRoot = entry.absolutePath()
        file = File(deleteRoot)

        for (mountPoint in MountPoint.getMountPoints(diskUsage)) {
            if ((mountPoint.root + "/").startsWith("$deleteRoot/")) {
                longToast("This delete operation will erase entire storage - canceled.")
                throw RuntimeException("Deletion canceled to prevent entire storage erasure")
            }
        }

        if (!file.exists()) {
            longToast(appStr(R.string.path_doesnt_exist, path))
            diskUsage.fileSystemState?.removeInRenderThread(entry)
            throw RuntimeException("Path doesn't exist")
        }

        if (file.isFile) {
            if (file.delete()) {
                toast(R.string.file_deleted)
                diskUsage.fileSystemState?.removeInRenderThread(entry)
            } else {
                toast(R.string.error_file_wasnt_deleted)
            }
            throw RuntimeException("File deleted")
        }

        val progressDialog = ProgressDialog(diskUsage)
        dialog = progressDialog
        progressDialog.setMessage(appStr(R.string.deleting_path, path))
        progressDialog.isIndeterminate = true
        progressDialog.setButton(
            DialogInterface.BUTTON_POSITIVE, diskUsage.getString(R.string.button_background)
        ) { d, _ -> d.dismiss() }
        progressDialog.setButton(
            DialogInterface.BUTTON_NEGATIVE, diskUsage.getString(android.R.string.cancel)
        ) { d, _ ->
            cancelDeletion = true
            d.dismiss()
        }
        progressDialog.setOnDismissListener { dialog = null }
        progressDialog.setOnCancelListener { dialog = null }
        progressDialog.show()

        startDeletion()
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun startDeletion() {
        // GlobalScope ensures deletion survives activity pause/rotation.
        // All UI side-effects are marshalled back to Dispatchers.Main.
        GlobalScope.launch(Dispatchers.IO) {
            val status = deleteRecursively(file)

            withContext(Dispatchers.Main) {
                try { dialog?.dismiss() } catch (_: Exception) {}

                diskUsage.fileSystemState?.removeInRenderThread(entry)

                if (status != DELETION_SUCCESS) {
                    withContext(Dispatchers.IO) { restore() }
                    diskUsage.fileSystemState?.requestRepaint()
                    diskUsage.fileSystemState?.requestRepaintGPU()
                }

                notifyUser(status)
            }
        }
    }

    private fun uninstall(pkg: FileSystemPackage) {
        val packageURI = Uri.parse("package:${pkg.pkg}")
        diskUsage.startActivity(Intent(Intent.ACTION_DELETE, packageURI))
    }

    private fun restore() {
        Timber.d("restore started for $path")
        val mountPoint = MountPoint.getForKey(diskUsage, diskUsage.key) ?: return
        val displayBlockSize = diskUsage.fileSystemState?.masterRoot?.displayBlockSize ?: 512
        try {
            val newEntry = Scanner(20, displayBlockSize, 0, 4).scan(
                LegacyFileImpl.createRoot(mountPoint.root + "/" + path)
            )
            newEntry?.let { entry ->
                entry.parent?.insert(entry, displayBlockSize)
                diskUsage.fileSystemState?.restore(entry)
                Timber.d("restore: Restoring undeleted: %s %s", entry.name, entry.sizeString())
            }
        } catch (e: IOException) {
            Timber.d("Failed to restore")
        }
    }

    private fun notifyUser(status: Int) {
        Timber.d("notifyUser: Delete: status=%s dirs=%s files=%s", status, numDeletedDirectories, numDeletedFiles)
        when (status) {
            DELETION_SUCCESS -> longToast(
                appStr(R.string.deleted_n_directories_and_n_files, numDeletedDirectories, numDeletedFiles)
            )
            DELETION_CANCELED -> longToast(
                appStr(R.string.deleted_n_directories_and_files_and_canceled, numDeletedDirectories, numDeletedFiles)
            )
            else -> longToast(
                appStr(R.string.deleted_n_directories_and_n_files_and_failed, numDeletedDirectories, numDeletedFiles)
            )
        }
    }

    private fun deleteRecursively(directory: File): Int {
        if (cancelDeletion) return DELETION_CANCELED
        val isDirectory = directory.isDirectory
        if (isDirectory) {
            val files = directory.listFiles() ?: return DELETION_FAILED
            for (child in files) {
                val status = deleteRecursively(child)
                if (status != DELETION_SUCCESS) return status
            }
        }
        return if (directory.delete()) {
            if (isDirectory) numDeletedDirectories++ else numDeletedFiles++
            DELETION_SUCCESS
        } else {
            DELETION_FAILED
        }
    }

    companion object {
        private const val DELETION_SUCCESS = 0
        private const val DELETION_FAILED = 1
        private const val DELETION_CANCELED = 2

        @JvmStatic
        fun startDelete(diskUsage: DiskUsage, entry: FileSystemEntry) {
            try {
                BackgroundDelete(diskUsage, entry)
            } catch (_: RuntimeException) {
                // Ignore initialization aborts (file already gone, path safety check, etc.)
            }
        }
    }
}
