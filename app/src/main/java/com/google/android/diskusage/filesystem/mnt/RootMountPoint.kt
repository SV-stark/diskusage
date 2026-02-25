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
import com.google.android.diskusage.utils.IOHelper
import timber.log.Timber
import java.util.ArrayList
import java.util.HashMap

class RootMountPoint internal constructor(root: String, fsType: String) : MountPoint(root, root, false) {

    override val isRootRequired: Boolean
        get() = true

    override fun hasApps(): Boolean {
        return false
    }

    override val isDeleteSupported: Boolean
        get() = false

    override val key: String
        get() = "rooted:$root"

    companion object {
        private var rootedMountPoints: MutableList<MountPoint> = ArrayList()
        private var rootedMountPointForKey: MutableMap<String, MountPoint> = HashMap()
        private var init = false
        @JvmField
        var checksum: Int = 0

        @JvmStatic
        fun getRootedMountPoints(context: Context): List<MountPoint> {
            initMountPoints(context)
            return rootedMountPoints
        }

        @JvmStatic
        fun getForKey(context: Context, key: String): MountPoint? {
            initMountPoints(context)
            return rootedMountPointForKey[key]
        }

        @JvmStatic
        fun initMountPoints(context: Context) {
            if (init) return
            init = true

            try {
                checksum = 0
                val reader = IOHelper.getProcMountsReader()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    checksum += line!!.length
                    Timber.d("initMountPoints: Line: %s", line)
                    val parts = line!!.split(" +".toRegex()).toTypedArray()
                    if (parts.size < 3) continue
                    val mountPoint = parts[1]
                    Timber.d("initMountPoints: Mount point: $mountPoint")
                    val fsType = parts[2]

                    if (!mountPoint.startsWith("/mnt/asec/")) {
                        val m = RootMountPoint(mountPoint, fsType)
                        rootedMountPoints.add(m)
                        rootedMountPointForKey[m.key] = m
                    }
                }
                reader.close()
            } catch (e: Exception) {
                Timber.e(e, "initMountPoints: Failed to get mount points")
            }
        }

        @JvmStatic
        fun reset() {
            rootedMountPoints = ArrayList()
            rootedMountPointForKey = HashMap()
            init = false
        }
    }
}
