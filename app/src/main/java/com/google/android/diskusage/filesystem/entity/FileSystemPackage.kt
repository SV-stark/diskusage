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

package com.google.android.diskusage.filesystem.entity

import android.content.pm.ApplicationInfo
import timber.log.Timber
import java.util.ArrayList
import java.util.Arrays

class FileSystemPackage(
    name: String?,
    val pkg: String,
    codeSizeParam: Long,
    dataSizeParam: Long,
    var cacheSize: Long,
    val flags: Int,
) : FileSystemEntry(null, name) {

    var codeSize: Long = codeSizeParam
    var dataSize: Long = dataSizeParam - cacheSize
    var publicChildren: ArrayList<FileSystemRoot> = ArrayList()

    init {
        if (flags and ApplicationInfo.FLAG_SYSTEM != 0 && flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0) {
            this.codeSize = 0
        }

        // Apps on external storage don't have separate code size tracked
        if (flags and ApplicationInfo.FLAG_EXTERNAL_STORAGE != 0) {
            this.codeSize = 0
        }
    }

    enum class ChildType {
        CODE,
        DATA,
        CACHE,
    }

    fun applyFilter(blockSize: Long) {
        clearDrawingCache()
        var blocks: Long = 0
        val entries = ArrayList<FileSystemEntry>(publicChildren)
        entries.add(
            makeNode(null, "apk")
                .initSizeInBytes(codeSize, blockSize),
        )
        entries.add(
            makeNode(null, "data")
                .initSizeInBytes(dataSize, blockSize),
        )
        entries.add(
            makeNode(null, "cache")
                .initSizeInBytes(cacheSize, blockSize),
        )

        for (e in entries) {
            blocks += e.sizeInBlocks
        }
        setSizeInBlocks(blocks, blockSize)

        for (e in entries) {
            e.parent = this
        }
        val childrenArray = entries.toTypedArray()
        Arrays.sort(childrenArray, COMPARE)
        @Suppress("UNCHECKED_CAST")
        children = childrenArray as Array<FileSystemEntry>
    }

    override fun create(): FileSystemEntry {
        return FileSystemPackage(
            this.name,
            this.pkg,
            this.codeSize,
            this.dataSize,
            this.cacheSize,
            this.flags,
        )
    }

    fun addPublicChild(child: FileSystemRoot, type: ChildType, blockSize: Long) {
        publicChildren.add(child)
        when (type) {
            ChildType.CODE -> {
                codeSize -= child.sizeInBlocks * blockSize
                if (codeSize < 0) {
                    Timber.d("addPublicChild: Code size negative %s for %s", codeSize, pkg)
                    codeSize = 0
                }
            }
            ChildType.DATA -> {
                dataSize -= child.sizeInBlocks * blockSize
                if (dataSize < 0) {
                    Timber.d("addPublicChild: Data size negative %s for %s", dataSize, pkg)
                    dataSize = 0
                }
            }
            ChildType.CACHE -> {
                cacheSize -= child.sizeInBlocks * blockSize
                if (cacheSize < 0) {
                    Timber.d("addPublicChild: Cache size negative %s for %s", cacheSize, pkg)
                    cacheSize = 0
                }
            }
        }
    }
}
