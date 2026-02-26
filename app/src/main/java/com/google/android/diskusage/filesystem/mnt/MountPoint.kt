/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008-2011 Ivan Volosyuk
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

package com.google.android.diskusage.filesystem.mnt

import android.content.Context
import com.google.android.diskusage.R
import com.google.android.diskusage.datasource.fast.PortableFileImpl
import timber.log.Timber
import java.util.ArrayList
import java.util.HashMap

open class MountPoint internal constructor(
    val title: String,
    val root: String,
    private val forceHasApps: Boolean
) {

    open val isRootRequired: Boolean
        get() = false

    open val isDeleteSupported: Boolean
        get() = forceHasApps

    open val key: String
        get() = "storage:$root"

    open fun hasApps(): Boolean {
        return forceHasApps
    }

    val checksum: Int
        get() = RootMountPoint.checksum

    companion object {
        private var init = false
        private var mountPoints: MutableList<MountPoint> = ArrayList()
        private var mountPointForKey: MutableMap<String, MountPoint> = HashMap()

        @JvmStatic
        fun getForKey(context: Context, key: String): MountPoint? {
            initMountPoints(context)
            val mountPoint = mountPointForKey[key]
            if (mountPoint != null) {
                return mountPoint
            }
            return RootMountPoint.getForKey(context, key)
        }

        @JvmStatic
        fun getMountPoints(context: Context): List<MountPoint> {
            initMountPoints(context)
            RootMountPoint.initMountPoints(context)
            return mountPoints
        }

        private fun initMountPoints(context: Context) {
            if (init) return
            init = true

            for (dir in PortableFileImpl.getExternalAppFilesDirs()) {
                val path = dir.absolutePath.replace("/Android/data/com.google.android.diskusage/files".toRegex(), "")
                Timber.d("MountPoint.initMountPoints: mountpoint %s", path)
                val internal = !dir.isExternalStorageRemovable
                val title = if (internal) context.getString(R.string.storage_card) else path
                val mountPoint = MountPoint(title, path, internal)
                mountPoints.add(mountPoint)
                mountPointForKey[mountPoint.key] = mountPoint
            }
        }

        @JvmStatic
        fun reset() {
            mountPoints = ArrayList()
            mountPointForKey = HashMap()
            init = false
            RootMountPoint.reset()
        }
    }
}
