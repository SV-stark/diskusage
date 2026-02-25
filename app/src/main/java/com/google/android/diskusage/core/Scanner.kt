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

package com.google.android.diskusage.core

import android.system.ErrnoException
import android.system.Os
import android.system.StructStat
import com.google.android.diskusage.datasource.LegacyFile
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.entity.FileSystemEntrySmall
import com.google.android.diskusage.filesystem.entity.FileSystemFile
import com.google.android.diskusage.ui.DiskUsage
import timber.log.Timber
import java.io.IOException
import java.util.ArrayList
import java.util.PriorityQueue

class Scanner(
    private val maxDepth: Int,
    private val blockSize: Long,
    allocatedBlocks: Long,
    maxHeap: Int
) : DiskUsage.ProgressGenerator {

    private val blockSizeIn512Bytes: Long = blockSize / 512
    private val sizeThreshold: Long = (allocatedBlocks shl FileSystemEntry.blockOffset) / (maxHeap / 2)
    private val maxHeapSize: Int = maxHeap

    private var createdNode: FileSystemEntry? = null
    private var createdNodeSize: Int = 0
    private var createdNodeNumFiles: Int = 0
    private var createdNodeNumDirs: Int = 0

    private var heapSize: Int = 0
    private val smallLists = PriorityQueue<SmallList>()

    var pos: Long = 0
        private set

    var lastCreatedFile: FileSystemEntry? = null
        private set

    private var dev: Long = 0

    private class SmallList(
        var parent: FileSystemEntry,
        var children: Array<FileSystemEntry>,
        var heapSize: Int,
        blocks: Long
    ) : Comparable<SmallList> {
        var spaceEfficiency: Float = blocks / heapSize.toFloat()

        override fun compareTo(other: SmallList): Int {
            return spaceEfficiency.compareTo(other.spaceEfficiency)
        }
    }

    init {
        Timber.d("Scanner: allocatedBlocks %s", allocatedBlocks)
        Timber.d("Scanner: maxHeap %s", maxHeap)
        Timber.d("Scanner: sizeThreshold = %s", sizeThreshold / (1 shl FileSystemEntry.blockOffset).toFloat())
    }

    @Throws(IOException::class)
    fun scan(file: LegacyFile): FileSystemEntry? {
        val stBlocks: Long
        try {
            val stat: StructStat = Os.stat(file.canonicalPath)
            dev = stat.st_dev
            stBlocks = stat.st_blocks
        } catch (e: ErrnoException) {
            throw IOException("Failed to find root folder", e)
        }

        scanDirectory(null, file, 0, stBlocks / blockSizeIn512Bytes)
        var extraHeap = 0

        // Restoring blocks
        for (list in smallLists) {
            val oldChildren = list.parent.children ?: emptyArray()
            val addChildren = list.children
            val newChildren = arrayOfNulls<FileSystemEntry>(oldChildren.size - 1 + addChildren.size)
            
            System.arraycopy(addChildren, 0, newChildren, 0, addChildren.size)
            var pos = addChildren.size
            for (i in oldChildren.indices) {
                val c = oldChildren[i]
                if (c !is FileSystemEntrySmall) {
                    newChildren[pos++] = c
                }
            }
            // Filter out nulls and sort
            val sortedChildren = newChildren.filterNotNull().toTypedArray()
            sortedChildren.sortWith(FileSystemEntry.COMPARE)
            list.parent.children = sortedChildren
            extraHeap += list.heapSize
        }
        Timber.d("allocated $extraHeap B of extra heap")
        Timber.d("allocated ${extraHeap + createdNodeSize} B total")
        return createdNode
    }

    private fun scanDirectory(parent: FileSystemEntry?, file: LegacyFile, depth: Int, selfBlocks: Long) {
        val name = file.name
        makeNode(parent, name)
        createdNodeNumDirs = 1
        createdNodeNumFiles = 0

        if (depth == maxDepth) {
            createdNode?.setSizeInBlocks(calculateSize(file), blockSize)
            return
        }

        var listNames: Array<String>? = null
        try {
            listNames = file.list()
        } catch (io: SecurityException) {
            Timber.d(io, "list files")
        }

        if (listNames == null) return

        val thisNode = createdNode ?: return
        var thisNodeSize = createdNodeSize
        var thisNodeNumDirs = 1
        var thisNodeNumFiles = 0

        var thisNodeSizeSmall = 0
        var thisNodeNumFilesSmall = 0
        var thisNodeNumDirsSmall = 0
        var smallBlocks: Long = 0

        val children = ArrayList<FileSystemEntry>()
        val smallChildren = ArrayList<FileSystemEntry>()
        var blocks = selfBlocks

        for (listName in listNames) {
            val childFile = file.getChild(listName)

            val stBlocks: Long
            val stSize: Long
            try {
                val res = Os.stat(childFile.canonicalPath)
                stBlocks = res.st_blocks
                stSize = res.st_size
            } catch (e: Exception) {
                continue
            }

            var dirs = 0
            var files = 1

            if (childFile.isFile) {
                makeNode(thisNode, childFile.name)
                createdNode?.initSizeInBytesAndBlocks(stSize, stBlocks / blockSizeIn512Bytes, blockSize)
                pos += createdNode?.sizeInBlocks ?: 0L
                lastCreatedFile = createdNode
            } else {
                scanDirectory(thisNode, childFile, depth + 1, stBlocks / blockSizeIn512Bytes)
                dirs = createdNodeNumDirs
                files = createdNodeNumFiles
            }

            val createdNodeBlocks = createdNode?.sizeInBlocks ?: 0L
            blocks += createdNodeBlocks

            if (this.createdNodeSize * sizeThreshold > (createdNode?.encodedSize ?: 0L)) {
                createdNode?.let { smallChildren.add(it) }
                thisNodeSizeSmall += this.createdNodeSize
                thisNodeNumFilesSmall += files
                thisNodeNumDirsSmall += dirs
                smallBlocks += createdNodeBlocks
            } else {
                createdNode?.let { children.add(it) }
                thisNodeSize += this.createdNodeSize
                thisNodeNumFiles += files
                thisNodeNumDirs += dirs
            }
        }
        thisNode.setSizeInBlocks(blocks, blockSize)

        thisNodeNumDirs += thisNodeNumDirsSmall
        thisNodeNumFiles += thisNodeNumFilesSmall

        var smallFilesEntry: FileSystemEntry? = null

        if ((thisNodeSizeSmall + thisNodeSize) * sizeThreshold <= thisNode.encodedSize || smallChildren.isEmpty()) {
            children.addAll(smallChildren)
            thisNodeSize += thisNodeSizeSmall
        } else {
            val msg = when {
                thisNodeNumDirsSmall == 0 -> "<$thisNodeNumFilesSmall files>"
                thisNodeNumFilesSmall == 0 -> "<$thisNodeNumDirsSmall dirs>"
                else -> "<$thisNodeNumDirsSmall dirs and $thisNodeNumFilesSmall files>"
            }

            makeNode(thisNode, msg)
            createdNode = FileSystemEntrySmall.makeNode(thisNode, msg, thisNodeNumFilesSmall + thisNodeNumDirsSmall)
            createdNode?.setSizeInBlocks(smallBlocks, blockSize)
            smallFilesEntry = createdNode
            createdNode?.let { children.add(it) }
            thisNodeSize += createdNodeSize
            val list = SmallList(
                thisNode,
                smallChildren.toTypedArray(),
                thisNodeSizeSmall,
                smallBlocks
            )
            smallLists.add(list)
        }

        if (children.isNotEmpty()) {
            var smallFilesEntrySize: Long = 0
            if (smallFilesEntry != null) {
                smallFilesEntrySize = smallFilesEntry.encodedSize
                smallFilesEntry.encodedSize = -1
            }
            val childrenArray = children.toTypedArray()
            childrenArray.sortWith(FileSystemEntry.COMPARE)
            thisNode.children = childrenArray
            if (smallFilesEntry != null) {
                smallFilesEntry.encodedSize = smallFilesEntrySize
            }
        }
        createdNode = thisNode
        createdNodeSize = thisNodeSize
        createdNodeNumDirs = thisNodeNumDirs
        createdNodeNumFiles = thisNodeNumFiles
    }

    private fun makeNode(parent: FileSystemEntry?, name: String) {
        createdNode = FileSystemFile.makeNode(parent, name)
        createdNodeSize = 4 + 16 + 8 + 10 + 8 + name.length * 2
        heapSize += createdNodeSize
        while (heapSize > maxHeapSize && smallLists.isNotEmpty()) {
            val removed = smallLists.remove()
            heapSize -= removed.heapSize
        }
    }

    private fun calculateSize(file: LegacyFile): Long {
        if (file.isLink) return 0

        if (file.isFile) {
            return try {
                val res = Os.stat(file.canonicalPath)
                res.st_blocks
            } catch (e: Exception) {
                0
            }
        }

        var list: Array<LegacyFile>? = null
        try {
            list = file.listFiles()
        } catch (io: SecurityException) {
            Timber.e(io, "calculateSize: list files")
        }
        
        if (list == null) return 0
        var size: Long = 1

        for (legacyFile in list) {
            size += calculateSize(legacyFile)
        }
        return size
    }
}
