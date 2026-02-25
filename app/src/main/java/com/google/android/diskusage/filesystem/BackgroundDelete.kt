/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008 Ivan Volosyuk
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

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
import splitties.resources.appStr
import splitties.toast.longToast
import splitties.toast.toast
import timber.log.Timber
import java.io.File
import java.io.IOException

class BackgroundDelete private constructor(
    private val diskUsage: DiskUsage,
    private val entry: FileSystemEntry
) : Thread() {

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
                // To safely exit init early without violating field initialization:
                throw RuntimeException("Deletion canceled to prevent entire storage erasure")
            }
        }

        if (!file.exists()) {
            longToast(appStr(R.string.path_doesnt_exist, path))
            diskUsage.fileSystemState.removeInRenderThread(entry)
            throw RuntimeException("Path doesn't exist")
        }

        if (file.isFile) {
            if (file.delete()) {
                toast(R.string.file_deleted)
                diskUsage.fileSystemState.removeInRenderThread(entry)
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
        start()
    }

    private fun uninstall(pkg: FileSystemPackage) {
        val pkg_name = pkg.pkg
        val packageURI = Uri.parse("package:$pkg_name")
        val uninstallIntent = Intent(Intent.ACTION_DELETE, packageURI)
        diskUsage.startActivity(uninstallIntent)
    }

    override fun run() {
        deletionStatus = deleteRecursively(file)
        // FIXME: use notification object when backgrounded
        diskUsage.handler.post {
            if (dialog != null) {
                try {
                    dialog?.dismiss()
                } catch (e: Exception) {
                    // ignore exception
                }
            }
            diskUsage.fileSystemState.removeInRenderThread(entry)
            if (deletionStatus != DELETION_SUCCESS) {
                restore()
                diskUsage.fileSystemState.requestRepaint()
                diskUsage.fileSystemState.requestRepaintGPU()
            }
            notifyUser()
        }
    }

    fun restore() {
        Timber.d("restore started for $path")
        val mountPoint = MountPoint.getForKey(diskUsage, diskUsage.key!!)!!
        val displayBlockSize = diskUsage.fileSystemState.masterRoot.displayBlockSize
        try {
            val newEntry = Scanner(
                // FIXME: hacked allocatedBlocks and heap size
                20, displayBlockSize, 0, 4
            ).scan(
                // Original: DataSource.get().createLegacyScanFile
                LegacyFileImpl.createRoot(mountPoint.root + "/" + path)
            )
            // FIXME: may be problems in case of two deletions
            entry.parent?.insert(newEntry!!, displayBlockSize)
            diskUsage.fileSystemState.restore(newEntry!!)
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
