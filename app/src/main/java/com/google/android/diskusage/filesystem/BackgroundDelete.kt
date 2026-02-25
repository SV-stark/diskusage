package com.google.android.diskusage.filesystem

import android.app.ProgressDialog
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.lifecycleScope
import com.google.android.diskusage.R
import com.google.android.diskusage.core.Scanner
import com.google.android.diskusage.datasource.fast.LegacyFileImpl
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.entity.FileSystemPackage
import com.google.android.diskusage.filesystem.mnt.MountPoint
import com.google.android.diskusage.ui.DiskUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.resources.appStr
import splitties.toast.longToast
import splitties.toast.toast
import timber.log.Timber
import java.io.File
import java.io.IOException

class BackgroundDelete private constructor(
    private val diskUsage: DiskUsage,
    private val entry: FileSystemEntry
) {

    private var dialog: ProgressDialog? = null
    private val file: File
    private val path: String

    @Volatile
    private var cancelDeletion = false
    private var backgroundDeletion = false
    private var deletionStatus = DELETION_IN_PROGRESS
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
        ) { d, _ ->
            background()
            d.dismiss()
        }
        progressDialog.setButton(
            DialogInterface.BUTTON_NEGATIVE, diskUsage.getString(android.R.string.cancel)
        ) { d, _ ->
            cancel()
            d.dismiss()
        }
        progressDialog.setOnDismissListener { dialog = null }
        progressDialog.setOnCancelListener { dialog = null }
        progressDialog.show()
        
        diskUsage.lifecycleScope.launch {
            try {
                val status = withContext(Dispatchers.IO) {
                    deleteRecursively(file)
                }
                deletionStatus = status
                
                if (dialog != null) {
                    try {
                        dialog?.dismiss()
                    } catch (e: Exception) {
                        // ignore exception
                    }
                }
                diskUsage.fileSystemState?.removeInRenderThread(entry)
                if (deletionStatus != DELETION_SUCCESS) {
                    withContext(Dispatchers.IO) {
                        restore()
                    }
                    diskUsage.fileSystemState?.requestRepaint()
                    diskUsage.fileSystemState?.requestRepaintGPU()
                }
                notifyUser()
            } catch (e: Exception) {
                Timber.e(e, "Error during deletion")
            }
        }
    }

    private fun uninstall(pkg: FileSystemPackage) {
        val pkg_name = pkg.pkg
        val packageURI = Uri.parse("package:$pkg_name")
        val uninstallIntent = Intent(Intent.ACTION_DELETE, packageURI)
        diskUsage.startActivity(uninstallIntent)
    }

    private fun restore() {
        Timber.d("restore started for $path")
        val mountPoint = MountPoint.getForKey(diskUsage, diskUsage.key) ?: return
        val displayBlockSize = diskUsage.fileSystemState?.masterRoot?.displayBlockSize ?: 512
        try {
            val newEntry = Scanner(
                20, displayBlockSize, 0, 4
            ).scan(
                LegacyFileImpl.createRoot(mountPoint.root + "/" + path)
            )
            // FIXME: may be problems in case of two deletions
            entry.parent?.insert(newEntry!!, displayBlockSize)
            diskUsage.fileSystemState?.restore(newEntry!!)
            Timber.d(
                "restore: Restoring undeleted: %s %s",
                newEntry.name, newEntry.sizeString()
            )
        } catch (e: IOException) {
            Timber.d("Failed to restore")
        }
    }

    fun notifyUser() {
        Timber.d(
            "notifyUser: Delete: status = %s directories %s files %s",
            deletionStatus, numDeletedDirectories, numDeletedFiles
        )

        when (deletionStatus) {
            DELETION_SUCCESS -> {
                longToast(
                    appStr(
                        R.string.deleted_n_directories_and_n_files,
                        numDeletedDirectories, numDeletedFiles
                    )
                )
            }
            DELETION_CANCELED -> {
                longToast(
                    appStr(
                        R.string.deleted_n_directories_and_files_and_canceled,
                        numDeletedDirectories, numDeletedFiles
                    )
                )
            }
            else -> {
                longToast(
                    appStr(
                        R.string.deleted_n_directories_and_n_files_and_failed,
                        numDeletedDirectories, numDeletedFiles
                    )
                )
            }
        }
    }

    fun background() {
        backgroundDeletion = true
    }

    fun cancel() {
        cancelDeletion = true
    }

    fun deleteRecursively(directory: File): Int {
        if (cancelDeletion) return DELETION_CANCELED
        val isDirectory = directory.isDirectory
        if (isDirectory) {
            val files = directory.listFiles() ?: return DELETION_FAILED
            for (value in files) {
                val status = deleteRecursively(value)
                if (status != DELETION_SUCCESS) return status
            }
        }

        val success = directory.delete()
        if (success) {
            if (isDirectory)
                numDeletedDirectories++
            else
                numDeletedFiles++
            return DELETION_SUCCESS
        } else {
            return DELETION_FAILED
        }
    }

    companion object {
        private const val DELETION_SUCCESS = 0
        private const val DELETION_FAILED = 1
        private const val DELETION_CANCELED = 2
        private const val DELETION_IN_PROGRESS = 3

        @JvmStatic
        fun startDelete(diskUsage: DiskUsage, entry: FileSystemEntry) {
            try {
                BackgroundDelete(diskUsage, entry)
            } catch (e: RuntimeException) {
                // Ignore initialization aborts
            }
        }
    }
}
