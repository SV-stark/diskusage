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

import android.content.Context
import com.google.android.diskusage.datasource.fast.NativeScannerStream
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.entity.FileSystemEntrySmall
import com.google.android.diskusage.filesystem.entity.FileSystemFile
import com.google.android.diskusage.filesystem.mnt.MountPoint
import com.google.android.diskusage.ui.DiskUsage.ProgressGenerator
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.util.PriorityQueue

class NativeScanner(
    private val context: Context,
    private val blockSize: Long,
    allocatedBlocks: Long,
    maxHeap: Int
) : ProgressGenerator {

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

    private var stream: InputStream? = null
    private val bufsize = 65536
    private var offset = 0
    private var allocated = 0
    private val buffer = ByteArray(bufsize)

    init {
        Timber.d("NativeScanner: allocatedBlocks %s", allocatedBlocks)
        Timber.d("NativeScanner: maxHeap %s", maxHeap)
        Timber.d("NativeScanner: sizeThreshold = %s", sizeThreshold / (1 shl FileSystemEntry.blockOffset).toFloat())
    }

    private fun move() {
        if (offset == 0) throw RuntimeException("Error: too large entity size")
        System.arraycopy(buffer, offset, buffer, 0, allocated - offset)
        allocated -= offset
        offset = 0
    }

    @Throws(IOException::class)
    fun read() {
        if (allocated == bufsize) {
            move()
        }
        val res = stream?.read(buffer, allocated, Math.min(bufsize - allocated, 256)) ?: -1
        if (res <= 0) {
            throw RuntimeException("Error: no more data")
        }
        allocated += res
    }

    @Throws(IOException::class)
    fun getByte(): Byte {
        while (true) {
            if (offset < allocated) {
                return buffer[offset++]
            }
            read()
        }
    }

    @Throws(IOException::class)
    fun getLong(): Long {
        var res: Long = 0
        var b: Byte
        while (getByte().also { b = it }.toInt() != 0) {
            if (b < '0'.code.toByte() || b > '9'.code.toByte()) throw RuntimeException("Error: number format error")
            res = res * 10 + (b - '0'.code.toByte())
        }
        return res
    }

    @Throws(IOException::class)
    fun getString(): String {
        var startPos = offset

        while (true) {
            for (i in startPos until allocated) {
                if (buffer[i].toInt() == 0) {
                    val res = String(buffer, offset, i - offset, Charsets.UTF_8)
                    offset = i + 1
                    return res
                }
            }
            val startOffset = startPos - offset
            read()
            startPos = offset + startOffset
        }
    }

    internal enum class Type {
        NONE, DIR, FILE
    }

    @Throws(IOException::class)
    internal fun getType(): Type {
        val c = getByte().toInt()
        return when (c.toChar()) {
            'D' -> Type.DIR
            'F' -> Type.FILE
            'Z' -> Type.NONE
            else -> throw RuntimeException("Error: incorrect entity type")
        }
    }

    private fun print(msg: String, list: SmallList) {
        val hiddenPath = java.lang.StringBuilder()
        var p = list.parent
        while (p != null) {
            hiddenPath.insert(0, p.name + "/")
            p = p.parent
        }
        Timber.d("%s %s = %s %s", msg, hiddenPath, list.heapSize, list.spaceEfficiency)
    }

    @Throws(IOException::class, InterruptedException::class)
    fun scan(mountPoint: MountPoint): FileSystemEntry? {
        stream = NativeScannerStream.Factory.create(mountPoint.root, mountPoint.isRootRequired)

        val type = getType()
        if (type != Type.DIR) throw RuntimeException("Error: no mount point")
        scanDirectory(null, getString(), 0)
        Timber.d("scan: Allocated %s B of heap", createdNodeSize)

        var extraHeap = 0

        // Restoring blocks
        for (list in smallLists) {
            print("restored", list)

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
            val sortedChildren = newChildren.filterNotNull().toTypedArray()
            sortedChildren.sortWith(FileSystemEntry.COMPARE)
            list.parent.children = sortedChildren
            extraHeap += list.heapSize
        }
        Timber.d("allocated $extraHeap B of extra heap")
        Timber.d("allocated ${extraHeap + createdNodeSize} B total")
        if (offset != allocated) throw RuntimeException("Error: extra data, ${allocated - offset} bytes")
        stream?.close()
        return createdNode
    }

    private class SoftStack {
        internal enum class State {
            PRE_LOOP, LOOP, POST_LOOP
        }

        var state: State = State.PRE_LOOP

        var parent: FileSystemEntry? = null
        var name: String? = null
        var depth: Int = 0

        var dirBlockSize: Long = 0
        var thisNode: FileSystemEntry? = null
        var thisNodeSize: Int = 0
        var thisNodeNumDirs: Int = 0
        var thisNodeNumFiles: Int = 0

        var thisNodeSizeSmall: Int = 0
        var thisNodeNumFilesSmall: Int = 0
        var thisNodeNumDirsSmall: Int = 0
        var smallBlocks: Long = 0

        var children: ArrayList<FileSystemEntry>? = null
        var smallChildren: ArrayList<FileSystemEntry>? = null
        var blocks: Long = 0
        var childType: Type? = null
        var dirs: Int = 0
        var files: Int = 0
        var prev: SoftStack? = null
    }

    @Throws(IOException::class)
    private fun scanDirectorySoftStack(parent_: FileSystemEntry?, name_: String?, depth_: Int) {
        var s: SoftStack? = SoftStack()
        s!!.parent = parent_
        s!!.name = name_
        s!!.depth = depth_
        s!!.state = SoftStack.State.PRE_LOOP

        restart@ while (true) {
            when (s!!.state) {
                SoftStack.State.PRE_LOOP -> {
                    s!!.dirBlockSize = getLong() / blockSizeIn512Bytes
                    getLong() // side-effects
                    makeNode(s!!.parent, s!!.name)
                    createdNodeNumDirs = 1
                    createdNodeNumFiles = 0

                    s!!.thisNode = createdNode
                    lastCreatedFile = createdNode
                    s!!.thisNodeSize = createdNodeSize
                    s!!.thisNodeNumDirs = 1
                    s!!.thisNodeNumFiles = 0

                    s!!.thisNodeSizeSmall = 0
                    s!!.thisNodeNumFilesSmall = 0
                    s!!.thisNodeNumDirsSmall = 0
                    s!!.smallBlocks = 0

                    s!!.children = ArrayList()
                    s!!.smallChildren = ArrayList()

                    s!!.blocks = 0
                    s!!.state = SoftStack.State.LOOP
                    // Fallthrough
                }
                SoftStack.State.LOOP -> {
                    while (true) {
                        s!!.childType = getType()
                        if (s!!.childType == Type.NONE) {
                            s!!.state = SoftStack.State.POST_LOOP
                            break
                        }

                        s!!.dirs = 0
                        s!!.files = 1
                        if (s!!.childType == Type.FILE) {
                            makeNode(s!!.thisNode, getString())
                            val childBlocks = getLong() / blockSizeIn512Bytes
                            val childBytes = getLong()
                            if (childBlocks == 0L) continue
                            createdNode?.initSizeInBytesAndBlocks(childBytes, childBlocks, blockSize)
                            pos += createdNode?.sizeInBlocks ?: 0L
                            lastCreatedFile = createdNode
                        } else {
                            // directory
                            val newS = SoftStack()
                            newS.prev = s
                            newS.parent = s!!.thisNode
                            newS.name = getString()
                            newS.depth = s!!.depth + 1
                            newS.state = SoftStack.State.PRE_LOOP
                            s = newS
                            continue@restart
                        }

                        val createdNodeBlocks = createdNode?.sizeInBlocks ?: 0L
                        s!!.blocks += createdNodeBlocks

                        if (this.createdNodeSize * sizeThreshold > (createdNode?.encodedSize ?: 0L)) {
                            createdNode?.let { s!!.smallChildren!!.add(it) }
                            s!!.thisNodeSizeSmall += this.createdNodeSize
                            s!!.thisNodeNumFilesSmall += s!!.files
                            s!!.thisNodeNumDirsSmall += s!!.dirs
                            s!!.smallBlocks += createdNodeBlocks
                        } else {
                            createdNode?.let { s!!.children!!.add(it) }
                            s!!.thisNodeSize += this.createdNodeSize
                            s!!.thisNodeNumFiles += s!!.files
                            s!!.thisNodeNumDirs += s!!.dirs
                        }
                    }
                }
                SoftStack.State.POST_LOOP -> {
                    s!!.thisNode?.setSizeInBlocks(s!!.blocks + s!!.dirBlockSize, blockSize)

                    s!!.thisNodeNumDirs += s!!.thisNodeNumDirsSmall
                    s!!.thisNodeNumFiles += s!!.thisNodeNumFilesSmall

                    var smallFilesEntry: FileSystemEntry? = null

                    if ((s!!.thisNodeSizeSmall + s!!.thisNodeSize) * sizeThreshold <= (s!!.thisNode?.encodedSize ?: 0L) || s!!.smallChildren!!.isEmpty()) {
                        s!!.children!!.addAll(s!!.smallChildren!!)
                        s!!.thisNodeSize += s!!.thisNodeSizeSmall
                    } else {
                        val msg = when {
                            s!!.thisNodeNumDirsSmall == 0 -> "<${s!!.thisNodeNumFilesSmall} files>"
                            s!!.thisNodeNumFilesSmall == 0 -> "<${s!!.thisNodeNumDirsSmall} dirs>"
                            else -> "<${s!!.thisNodeNumDirsSmall} dirs and ${s!!.thisNodeNumFilesSmall} files>"
                        }

                        makeNode(s!!.thisNode, msg)
                        createdNode = FileSystemEntrySmall.makeNode(s!!.thisNode, msg, s!!.thisNodeNumFilesSmall + s!!.thisNodeNumDirsSmall)
                        createdNode?.setSizeInBlocks(s!!.smallBlocks, blockSize)
                        smallFilesEntry = createdNode
                        createdNode?.let { s!!.children!!.add(it) }
                        s!!.thisNodeSize += createdNodeSize
                        val list = SmallList(
                            s!!.thisNode!!,
                            s!!.smallChildren!!.toTypedArray(),
                            s!!.thisNodeSizeSmall,
                            s!!.smallBlocks
                        )
                        smallLists.add(list)
                    }

                    if (s!!.children!!.isNotEmpty()) {
                        var smallFilesEntrySize: Long = 0
                        if (smallFilesEntry != null) {
                            smallFilesEntrySize = smallFilesEntry.encodedSize
                            smallFilesEntry.encodedSize = -1
                        }
                        val childrenArray = s!!.children!!.toTypedArray()
                        childrenArray.sortWith(FileSystemEntry.COMPARE)
                        s!!.thisNode?.children = childrenArray
                        if (smallFilesEntry != null) {
                            smallFilesEntry.encodedSize = smallFilesEntrySize
                        }
                    }
                    createdNode = s!!.thisNode
                    createdNodeSize = s!!.thisNodeSize
                    createdNodeNumDirs = s!!.thisNodeNumDirs
                    createdNodeNumFiles = s!!.thisNodeNumFiles

                    s = s!!.prev
                    if (s == null) return
                    s!!.dirs = createdNodeNumDirs
                    s!!.files = createdNodeNumFiles
                    
                    val createdNodeBlocks = createdNode?.sizeInBlocks ?: 0L
                    s!!.blocks += createdNodeBlocks

                    if (this.createdNodeSize * sizeThreshold > (createdNode?.encodedSize ?: 0L)) {
                        createdNode?.let { s!!.smallChildren!!.add(it) }
                        s!!.thisNodeSizeSmall += this.createdNodeSize
                        s!!.thisNodeNumFilesSmall += s!!.files
                        s!!.thisNodeNumDirsSmall += s!!.dirs
                        s!!.smallBlocks += createdNodeBlocks
                    } else {
                        createdNode?.let { s!!.children!!.add(it) }
                        s!!.thisNodeSize += this.createdNodeSize
                        s!!.thisNodeNumFiles += s!!.files
                        s!!.thisNodeNumDirs += s!!.dirs
                    }
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun scanDirectory(parent: FileSystemEntry?, name: String?, depth: Int) {
        if (depth > 10) {
            scanDirectorySoftStack(parent, name, depth)
            return
        }
        val dirBlockSize = getLong() / blockSizeIn512Bytes
        getLong()
        makeNode(parent, name)
        createdNodeNumDirs = 1
        createdNodeNumFiles = 0

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

        var blocks: Long = 0

        while (true) {
            val childType = getType()
            if (childType == Type.NONE) break

            var dirs = 0
            var files = 1

            if (childType == Type.FILE) {
                makeNode(thisNode, getString())
                val childBlocks = getLong() / blockSizeIn512Bytes
                val childBytes = getLong()
                if (childBlocks == 0L) continue
                createdNode?.initSizeInBytesAndBlocks(childBytes, childBlocks, blockSize)
                pos += createdNode?.sizeInBlocks ?: 0L
                lastCreatedFile = createdNode
            } else {
                scanDirectory(thisNode, getString(), depth + 1)
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
        thisNode.setSizeInBlocks(blocks + dirBlockSize, blockSize)

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

    private fun makeNode(parent: FileSystemEntry?, name: String?) {
        createdNode = FileSystemFile.makeNode(parent, name)
        createdNodeSize = 4 + 16 + 8 + 10 + 8 + (name?.length ?: 0) * 2
        heapSize += createdNodeSize
        while (heapSize > maxHeapSize && smallLists.isNotEmpty()) {
            val removed = smallLists.remove()
            heapSize -= removed.heapSize
            print("killed", removed)
        }
    }
}
