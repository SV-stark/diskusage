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

package com.google.android.diskusage.filesystem.entity

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.google.android.diskusage.R
import com.google.android.diskusage.opengl.DrawingCache
import com.google.android.diskusage.opengl.RenderingThread
import com.google.android.diskusage.ui.Cursor
import timber.log.Timber
import java.util.ArrayList
import java.util.Arrays
import java.util.Comparator
import java.util.TreeMap

open class FileSystemEntry(
    var parent: FileSystemEntry?,
    var name: String?
) {

    fun hasChildren(): Boolean {
        return children != null && children!!.isNotEmpty()
    }

    var encodedSize: Long = 0
    var children: Array<FileSystemEntry>? = null
    var drawingCache: DrawingCache? = null

    val sizeInBlocks: Long
        get() = encodedSize ushr blockOffset

    val sizeForRendering: Long
        get() = encodedSize and blockMask.inv()

    fun clearDrawingCache() {
        drawingCache?.resetSizeString()
    }

    private fun makeBytesPart(size: Long): Long {
        if (size < 1024) return size
        if (size < 1024 * 1024) return MULTIPLIER_KBYTES.toLong() or (size shr 10)
        if (size < 1024 * 1024 * 10) return MULTIPLIER_MBYTES.toLong() or (size shr 10)
        if (size < 1024 * 1024 * 200) return MULTIPLIER_MBYTES10.toLong() or (size shr 10)
        if (size < 1024L * 1024 * 1024) return MULTIPLIER_MBYTES100.toLong() or (size shr 20)
        if (size < 1024L * 1024 * 1024 * 10) return MULTIPLIER_GBYTES.toLong() or (size shr 20)
        if (size < 1024L * 1024 * 1024 * 200) return MULTIPLIER_GBYTES10.toLong() or (size shr 20)
        return MULTIPLIER_GBYTES100.toLong() or (size shr 30)
    }

    open fun isDeletable(): Boolean {
        return false
    }

    fun setSizeInBlocks(blocks: Long, blockSize: Long) {
        val bytes = blocks * blockSize
        encodedSize = (blocks shl blockOffset) or makeBytesPart(bytes)
    }

    fun initSizeInBytes(bytes: Long, blockSize: Long): FileSystemEntry {
        val blocks = (bytes + blockSize - 1) / blockSize
        encodedSize = (blocks shl blockOffset) or makeBytesPart(bytes)
        return this
    }

    fun initSizeInBytesAndBlocks(bytes: Long, blocks: Long, blockSize: Long): FileSystemEntry {
        encodedSize = (blocks shl blockOffset) or makeBytesPart(bytes)
        return this
    }

    fun setChildren(children: Array<FileSystemEntry>?, blockSize: Long): FileSystemEntry {
        this.children = children
        var blocks: Long = 0
        if (children == null) return this
        for (child in children) {
            blocks += child.sizeInBlocks
            child.parent = this
        }
        setSizeInBlocks(blocks, blockSize)
        return this
    }

    class ExcludeFilter(exclude_paths: ArrayList<String>?) {
        val childFilter: Map<String, ExcludeFilter>?

        init {
            if (exclude_paths == null) {
                this.childFilter = null
            } else {
                val filter = TreeMap<String, ArrayList<String?>>()
                for (path in exclude_paths) {
                    val parts = path.split("/".toRegex(), limit = 2).toTypedArray()
                    if (parts.size < 2) {
                        addEntry(filter, path, null)
                    } else {
                        addEntry(filter, parts[0], parts[1])
                    }
                }
                val excludeFilter = TreeMap<String, ExcludeFilter>()
                for ((key, value) in filter) {
                    var has_null = false
                    for (part in value) {
                        if (part == null) {
                            has_null = true
                            break
                        }
                    }
                    if (has_null) {
                        excludeFilter[key] = ExcludeFilter(null)
                    } else {
                        val nonNullList = ArrayList<String>()
                        for (p in value) {
                            if (p != null) nonNullList.add(p)
                        }
                        excludeFilter[key] = ExcludeFilter(nonNullList)
                    }
                }
                this.childFilter = excludeFilter
            }
        }

        companion object {
            private fun addEntry(
                filter: TreeMap<String, ArrayList<String?>>, name: String, value: String?
            ) {
                var entry: ArrayList<String?>? = filter[name]
                if (entry == null) {
                    entry = java.util.ArrayList()
                    filter[name] = entry
                }
                entry.add(value)
            }
        }
    }

    open fun create(): FileSystemEntry {
        return FileSystemEntry(null, this.name)
    }

    class SearchInterruptedException : RuntimeException() {
        companion object {
            private const val serialVersionUID = -3986013022885904101L
        }
    }

    fun copy(): FileSystemEntry {
        if (Thread.interrupted()) throw SearchInterruptedException()
        val copy = create()
        if (this.children != null) {
            val childrenCopy = arrayOfNulls<FileSystemEntry>(this.children!!.size)
            for (i in this.children!!.indices) {
                val childCopy = this.children!![i].copy()
                childrenCopy[i] = childCopy
                childCopy.parent = copy
            }
            @Suppress("UNCHECKED_CAST")
            copy.children = childrenCopy as Array<FileSystemEntry>
        }
        copy.encodedSize = this.encodedSize
        return copy
    }

    fun filterChildren(pattern: CharSequence, blockSize: Long): FileSystemEntry? {
        if (children == null) return null
        val filtered_children = ArrayList<FileSystemEntry>()

        for (child in this.children!!) {
            val childCopy = child.filter(pattern, blockSize)
            if (childCopy != null) {
                filtered_children.add(childCopy)
            }
        }
        if (filtered_children.size == 0) return null
        val childrenArray = filtered_children.toTypedArray()
        Arrays.sort(childrenArray, COMPARE)
        val copy = create()
        copy.children = childrenArray
        var size: Long = 0

        for (child in childrenArray) {
            size += child.sizeInBlocks
            child.parent = copy
        }
        copy.setSizeInBlocks(size, blockSize)
        return copy
    }

    open fun filter(pattern: CharSequence?, blockSize: Long): FileSystemEntry? {
        if (pattern != null && name!!.lowercase().contains(pattern)) {
            return copy()
        }
        return filterChildren(pattern ?: "", blockSize)
    }

    fun getIndexOf(directChild: FileSystemEntry?): Int {
        val children0 = children

        if (children0 != null) {
            for (i in children0.indices) {
                if (children0[i] === directChild) {
                    return i
                }
            }
        }

        throw RuntimeException("something broken")
    }

    val next: FileSystemEntry
        get() {
            val index = parent!!.getIndexOf(this)
            if (index + 1 == parent!!.children!!.size) return this
            return parent!!.children!![index + 1]
        }

    val prev: FileSystemEntry
        get() {
            val index = parent!!.getIndexOf(this)
            if (index == 0) return this
            return parent!!.children!![index - 1]
        }

    private fun getDrawingCache(): DrawingCache {
        if (drawingCache != null) return drawingCache!!
        val drawingCache = DrawingCache(this)
        this.drawingCache = drawingCache
        return drawingCache
    }

    fun paintGPU(
        rt: RenderingThread,
        bounds: Rect, cursor: Cursor, viewTop: Long,
        viewDepth: Float, yscale: Float, screenHeight: Int,
        numSpecialEntries: Int
    ) {
        val viewLeft = (viewDepth * elementWidth).toInt()

        val clipTop = (bounds.top / yscale).toLong() + viewTop
        val clipBottom = (bounds.bottom / yscale).toLong() + viewTop
        val clipLeft = bounds.left + viewLeft
        val clipRight = bounds.right + viewLeft
        val xoffset = -viewLeft.toFloat()
        val yoffset = -viewTop * yscale

        if (children != null) {
            paintGPU(
                sizeForRendering, children!!, rt, xoffset, yoffset, yscale, clipLeft.toLong(), clipRight.toLong(),
                clipTop, clipBottom, screenHeight
            )

            paintSpecialGPU(
                sizeForRendering, children!!, rt, xoffset, yoffset, yscale, clipLeft.toLong(), clipRight.toLong(),
                clipTop, clipBottom, screenHeight, numSpecialEntries
            )
        }

        val cursorLeft = cursor.depth * elementWidth + xoffset
        val cursorTop = (cursor.top - viewTop) * yscale
        val cursorRight = cursorLeft + elementWidth
        val cursorBottom = cursorTop + cursor.position.sizeForRendering * yscale
        rt.cursorSquare?.drawFrame(cursorLeft, cursorTop, cursorRight, cursorBottom)
    }

    fun paint(
        canvas: Canvas, bounds: Rect, cursor: Cursor, viewTop: Long,
        viewDepth: Float, yscale: Float, screenHeight: Int, numSpecialEntries: Int
    ) {
        val viewLeft = (viewDepth * elementWidth).toInt()

        val clipTop = (bounds.top / yscale).toLong() + viewTop
        val clipBottom = (bounds.bottom / yscale).toLong() + viewTop
        val clipLeft = bounds.left + viewLeft
        val clipRight = bounds.right + viewLeft
        val xoffset = -viewLeft.toFloat()
        val yoffset = -viewTop * yscale

        if (children != null) {
            paint(
                sizeForRendering, children!!, canvas, xoffset, yoffset, yscale, clipLeft.toLong(), clipRight.toLong(),
                clipTop, clipBottom, screenHeight
            )

            paintSpecial(
                sizeForRendering, children!!, canvas, xoffset, yoffset, yscale, clipLeft.toLong(), clipRight.toLong(),
                clipTop, clipBottom, screenHeight, numSpecialEntries
            )
        }

        val cursorLeft = cursor.depth * elementWidth + xoffset
        val cursorTop = (cursor.top - viewTop) * yscale
        val cursorRight = cursorLeft + elementWidth
        val cursorBottom = cursorTop + cursor.position.sizeForRendering * yscale
        canvas.drawRect(cursorLeft, cursorTop, cursorRight, cursorBottom, cursor_fg)
    }

    fun sizeString(): String {
        return calcSizeStringFromEncoded(encodedSize)
    }

    fun toTitleString(): String {
        val sizeString0 = sizeString()
        return if (children != null && children!!.isNotEmpty()) {
            String.format(dir_name_size_num_dirs!!, name, sizeString0, children!!.size)
        } else if (sizeInBlocks == 0L) {
            String.format(dir_empty!!, name)
        } else {
            String.format(dir_name_size!!, name, sizeString0)
        }
    }

    fun path2(): String {
        val pathElements = ArrayList<String?>()
        var current: FileSystemEntry? = this
        while (current != null) {
            pathElements.add(current.name)
            current = current.parent
        }
        if (pathElements.size > 0) pathElements.removeAt(pathElements.size - 1)
        if (pathElements.size > 0) pathElements.removeAt(pathElements.size - 1)
        val path = StringBuilder()
        var sep = ""
        for (i in pathElements.indices.reversed()) {
            path.append(sep)
            path.append(pathElements[i])
            sep = "/"
        }
        return path.toString()
    }

    fun absolutePath(): String = when (this) {
        is FileSystemRoot -> rootPath
        else -> "${parent!!.absolutePath()}/$name"
    }

    fun depth(entry: FileSystemEntry): Int {
        var d = 0
        var currentEntry: FileSystemEntry? = entry
        val root = this

        while (currentEntry != null && currentEntry !== root) {
            currentEntry = currentEntry.parent
            d++
        }
        return d
    }

    fun findEntry(maxDepth: Int, offset: Long): FileSystemEntry {
        var currOffset: Long = 0
        var entry = this
        var children0 = children

        for (depth in 0 until maxDepth) {
            if (children0 == null) break
            val nchildren = children0!!.size
            for (c in 0 until nchildren) {
                val e = children0!![c]
                val size = e.sizeForRendering
                if (currOffset + size < offset) {
                    currOffset += size
                    continue
                }

                entry = e
                children0 = e.children
                if (children0 == null) return entry
                break
            }
        }
        return entry
    }

    fun getOffset(cursor: FileSystemEntry): Long {
        var offset: Long = 0
        var dir: FileSystemEntry?
        val root = this
        var currentCursor: FileSystemEntry? = cursor

        while (currentCursor != null && currentCursor !== root) {
            dir = currentCursor.parent
            if (dir == null) break
            val children = dir.children

            if (children != null) {
                for (e in children) {
                    if (e === currentCursor) break
                    offset += e.sizeForRendering
                }
            }
            currentCursor = dir
        }
        return offset
    }

    fun remove(blockSize: Long) {
        val children0 = parent!!.children
        if (children0 != null) {
            val len = children0.size
            for (i in 0 until len) {
                if (children0[i] !== this) continue

                val newChildren = arrayOfNulls<FileSystemEntry>(len - 1)
                System.arraycopy(children0, 0, newChildren, 0, i)
                System.arraycopy(children0, i + 1, newChildren, i, len - i - 1)
                @Suppress("UNCHECKED_CAST")
                parent!!.children = newChildren as Array<FileSystemEntry>

                var parent0 = parent

                val blocks = sizeInBlocks

                while (parent0 != null) {
                    parent0.setSizeInBlocks(parent0.sizeInBlocks - blocks, blockSize)
                    parent0.clearDrawingCache()
                    parent0.children?.let { Arrays.sort(it, COMPARE) }
                    parent0 = parent0.parent
                }
                return
            }
        }
    }

    fun insert(newEntry: FileSystemEntry, blockSize: Long) {
        val children0 = arrayOfNulls<FileSystemEntry>((children?.size ?: 0) + 1)
        if (children != null) {
            System.arraycopy(children!!, 0, children0, 0, children!!.size)
        }
        children0[(children?.size ?: 0)] = newEntry
        @Suppress("UNCHECKED_CAST")
        children = children0 as Array<FileSystemEntry>
        newEntry.parent = this
        var parent0: FileSystemEntry? = this
        val blocks = newEntry.sizeInBlocks

        while (parent0 != null) {
            parent0.children?.let { Arrays.sort(it, COMPARE) }
            parent0.setSizeInBlocks(parent0.sizeInBlocks + blocks, blockSize)
            parent0.clearDrawingCache()
            parent0 = parent0.parent
        }
    }

    fun getEntryByName(path: String, exactMatch: Boolean): FileSystemEntry? {
        Timber.d("getEntryByName: getEntryForName = %s", path)
        val pathElements = path.split("/".toRegex()).toTypedArray()
        var entry = this

        outer@ for (name in pathElements) {
            val children = entry.children ?: return null
            for (child in children) {
                entry = child
                if (name == entry.name) {
                    continue@outer
                }
            }
            return null
        }
        return entry
    }

    open fun getNumFiles(): Int {
        if (children == null) return 1

        var numFiles = 0
        var hasFile = false
        for (entry in children!!) {
            if (entry.children == null) hasFile = true
            numFiles += entry.getNumFiles()
        }
        if (hasFile) numFiles++
        return numFiles
    }

    companion object {
        private val bg = Paint()
        private val bg_emptySpace = Paint()
        private val cursor_fg = Paint()
        private val fg_rect = Paint()
        val fg2: Paint = Paint()
        private val fill_bg = Paint()
        private val textPaintFolder = Paint()
        private val textPaintFile = Paint()
        var ascent: Float = 0f
        var descent: Float = 0f
        private var n_bytes: String? = null
        private var n_kilobytes: String? = null
        private var n_megabytes: String? = null
        private var n_megabytes10: String? = null
        private var n_megabytes100: String? = null
        private var n_gigabytes: String? = null
        private var n_gigabytes10: String? = null
        private var n_gigabytes100: String? = null
        private var dir_name_size_num_dirs: String? = null
        private var dir_empty: String? = null
        private var dir_name_size: String? = null
        @JvmField
        var deletedEntry: FileSystemEntry? = null

        var fontSize: Float = 0f
        @JvmField
        var elementWidth: Int = 0

        init {
            bg.color = Color.parseColor("#060118")
            bg_emptySpace.color = Color.parseColor("#063A43")
            bg.style = Paint.Style.FILL
            fg_rect.color = Color.WHITE
            fg_rect.style = Paint.Style.STROKE
            fg_rect.flags = fg_rect.flags or Paint.ANTI_ALIAS_FLAG
            fg2.color = Color.parseColor("#18C5E7")
            fg2.style = Paint.Style.STROKE
            fg2.flags = fg2.flags or Paint.ANTI_ALIAS_FLAG
            fill_bg.color = Color.WHITE
            fill_bg.style = Paint.Style.FILL
            cursor_fg.color = Color.YELLOW
            cursor_fg.style = Paint.Style.STROKE

            textPaintFolder.color = Color.WHITE
            textPaintFolder.style = Paint.Style.FILL_AND_STROKE
            textPaintFolder.flags = textPaintFolder.flags or Paint.ANTI_ALIAS_FLAG

            textPaintFile.color = Color.parseColor("#18C5E7")
            textPaintFile.style = Paint.Style.FILL_AND_STROKE
            textPaintFile.flags = textPaintFile.flags or Paint.ANTI_ALIAS_FLAG
        }

        private const val MULTIPLIER_SHIFT = 18

        private const val MULTIPLIER_MASK = 7 shl MULTIPLIER_SHIFT
        private const val MULTIPLIER_BYTES = 0
        private const val MULTIPLIER_KBYTES = 1 shl MULTIPLIER_SHIFT
        private const val MULTIPLIER_MBYTES = 2 shl MULTIPLIER_SHIFT
        private const val MULTIPLIER_MBYTES10 = 3 shl MULTIPLIER_SHIFT
        private const val MULTIPLIER_MBYTES100 = 4 shl MULTIPLIER_SHIFT
        private const val MULTIPLIER_GBYTES = 5 shl MULTIPLIER_SHIFT
        private const val MULTIPLIER_GBYTES10 = 6 shl MULTIPLIER_SHIFT
        private const val MULTIPLIER_GBYTES100 = 7 shl MULTIPLIER_SHIFT
        private const val SIZE_MASK = (1 shl MULTIPLIER_SHIFT) - 1

        const val blockOffset: Int = 24
        const val blockMask: Long = (1L shl blockOffset) - 1

        @JvmStatic
        fun calcSizeStringFromEncoded(encodedSize: Long): String {
            val size = SIZE_MASK and encodedSize.toInt()
            when (MULTIPLIER_MASK and encodedSize.toInt()) {
                MULTIPLIER_BYTES -> return String.format(n_bytes!!, size)
                MULTIPLIER_KBYTES -> return String.format(n_kilobytes!!, size)
                MULTIPLIER_MBYTES -> return java.lang.String.format(n_megabytes!!, size * (1f / 1024))
                MULTIPLIER_MBYTES10 -> return java.lang.String.format(n_megabytes10!!, size * (1f / 1024))
                MULTIPLIER_MBYTES100 -> return String.format(n_megabytes100!!, size)
                MULTIPLIER_GBYTES -> return java.lang.String.format(n_gigabytes!!, size * (1f / 1024))
                MULTIPLIER_GBYTES10 -> return java.lang.String.format(n_gigabytes10!!, size * (1f / 1024))
                MULTIPLIER_GBYTES100 -> return String.format(n_gigabytes100!!, size)
            }
            return ""
        }

        @JvmStatic
        fun makeNode(parent: FileSystemEntry?, name: String?): FileSystemEntry {
            return FileSystemEntry(parent, name)
        }

        class Compare : Comparator<FileSystemEntry> {
            override fun compare(aa: FileSystemEntry, bb: FileSystemEntry): Int {
                if (aa.encodedSize == bb.encodedSize) {
                    return 0
                }
                return if (aa.encodedSize < bb.encodedSize) 1 else -1
            }
        }

        @JvmField
        val COMPARE: Compare = Compare()

        private fun paintSpecialGPU(
            parent_size: Long, entriesParam: Array<FileSystemEntry>,
            rt: RenderingThread, xoffsetParam: Float, yoffsetParam: Float, yscale: Float,
            clipLeftParam: Long, clipRightParam: Long, clipTopParam: Long, clipBottomParam: Long,
            screenHeight: Int, numSpecial: Int
        ) {
            var parentSize = parent_size
            val entries = entriesParam[0].children ?: return
            val xoffset = xoffsetParam + elementWidth
            val clipLeft = clipLeftParam - elementWidth

            val children = entries
            val len = children.size
            var child_clipTop = clipTopParam
            var child_clipBottom = clipBottomParam
            val child_xoffset = xoffset + elementWidth
            var yoffset = yoffsetParam

            for (i in 0 until len - numSpecial) {
                val c = children[i]
                val csize = c.sizeForRendering
                parentSize -= csize

                val top = yoffset
                val bottom = top + csize * yscale

                if (child_clipBottom < 0) {
                    return
                }
                child_clipTop -= csize
                child_clipBottom -= csize
                yoffset = bottom
            }

            for (i in len - numSpecial until len) {
                val c = children[i]
                val csize = c.sizeForRendering
                parentSize -= csize

                val top = yoffset
                val bottom = top + csize * yscale

                if (child_clipTop > csize) {
                    child_clipTop -= csize
                    child_clipBottom -= csize
                    yoffset = bottom
                    continue
                }

                if (child_clipBottom < 0) {
                    return
                }

                if (clipLeft < elementWidth) {
                    val fontSize0 = fontSize

                    rt.specialSquare?.draw(xoffset, top, child_xoffset, bottom)

                    if (bottom - top > fontSize0 * 2) {
                        var pos = (top + bottom) * 0.5f
                        if (pos < fontSize0) {
                            if (bottom > 2 * fontSize0) {
                                pos = fontSize0
                            } else {
                                pos = bottom - fontSize0
                            }
                        } else if (pos > screenHeight.toFloat() - fontSize0) {
                            if (top < screenHeight.toFloat() - 2 * fontSize0) {
                                pos = screenHeight.toFloat() - fontSize0
                            } else {
                                pos = top + fontSize0
                            }
                        }
                        val pos1 = pos - descent
                        val pos2 = pos - ascent

                        val cache = c.getDrawingCache()
                        cache.drawText(rt, xoffset + 2, pos1, elementWidth - 5)
                        cache.drawSize(rt, xoffset + 2, pos2, elementWidth - 5)
                    } else if (bottom - top > fontSize0) {
                        val cache = c.getDrawingCache()
                        cache.drawText(rt, xoffset + 2, (top + bottom - ascent - descent) / 2, elementWidth - 5)
                    }
                }

                child_clipTop -= csize
                child_clipBottom -= csize
                yoffset = bottom
            }
        }

        private fun paintSpecial(
            parent_size: Long, entriesParam: Array<FileSystemEntry>,
            canvas: Canvas, xoffsetParam: Float, yoffsetParam: Float, yscale: Float,
            clipLeftParam: Long, clipRightParam: Long, clipTopParam: Long, clipBottomParam: Long,
            screenHeight: Int, numSpecial: Int
        ) {
            var parentSize = parent_size
            val entries = entriesParam[0].children ?: return
            val xoffset = xoffsetParam + elementWidth
            val clipLeft = clipLeftParam - elementWidth

            val children = entries
            val len = children.size
            var child_clipTop = clipTopParam
            var child_clipBottom = clipBottomParam
            val child_xoffset = xoffset + elementWidth
            var yoffset = yoffsetParam

            for (i in 0 until len - numSpecial) {
                val c = children[i]
                val csize = c.sizeForRendering
                parentSize -= csize

                val top = yoffset
                val bottom = top + csize * yscale

                if (child_clipBottom < 0) {
                    return
                }
                child_clipTop -= csize
                child_clipBottom -= csize
                yoffset = bottom
            }

            for (i in len - numSpecial until len) {
                val c = children[i]
                val csize = c.sizeForRendering
                parentSize -= csize

                val top = yoffset
                val bottom = top + csize * yscale

                if (child_clipTop > csize) {
                    child_clipTop -= csize
                    child_clipBottom -= csize
                    yoffset = bottom
                    continue
                }

                if (child_clipBottom < 0) {
                    return
                }

                if (clipLeft < elementWidth) {
                    val fontSize0 = fontSize

                    canvas.drawRect(xoffset, top, child_xoffset, bottom, bg_emptySpace)
                    canvas.drawRect(xoffset, top, child_xoffset, bottom, fg_rect)

                    if (bottom - top > fontSize0 * 2) {
                        var pos = (top + bottom) * 0.5f
                        if (pos < fontSize0) {
                            if (bottom > 2 * fontSize0) {
                                pos = fontSize0
                            } else {
                                pos = bottom - fontSize0
                            }
                        } else if (pos > screenHeight.toFloat() - fontSize0) {
                            if (top < screenHeight.toFloat() - 2 * fontSize0) {
                                pos = screenHeight.toFloat() - fontSize0
                            } else {
                                pos = top + fontSize0
                            }
                        }
                        val pos1 = pos - descent
                        val pos2 = pos - ascent

                        val cache = c.getDrawingCache()
                        val sizeString = cache.sizeString
                        val cliplen = fg2.breakText(c.name, true, (elementWidth - 4).toFloat(), null)
                        val clippedName = c.name?.substring(0, cliplen) ?: ""
                        canvas.drawText(clippedName, xoffset + 2, pos1, textPaintFolder)
                        canvas.drawText(sizeString ?: "", xoffset + 2, pos2, textPaintFolder)
                    } else if (bottom - top > fontSize0) {
                        val cliplen = fg2.breakText(c.name, true, (elementWidth - 4).toFloat(), null)
                        val clippedName = c.name?.substring(0, cliplen) ?: ""
                        canvas.drawText(
                            clippedName, xoffset + 2,
                            (top + bottom - ascent - descent) / 2,
                            if (c.children == null) textPaintFile else textPaintFolder
                        )
                    }
                }

                child_clipTop -= csize
                child_clipBottom -= csize
                yoffset = bottom
            }
        }

        private fun paintGPU(
            parent_size: Long, entries: Array<FileSystemEntry>,
            rt: RenderingThread, xoffsetParam: Float, yoffsetParam: Float, yscale: Float,
            clipLeftParam: Long, clipRightParam: Long, clipTopParam: Long, clipBottomParam: Long,
            screenHeight: Int
        ) {
            var parentSize = parent_size
            val child_clipLeft = clipLeftParam - elementWidth
            val child_clipRight = clipRightParam - elementWidth
            var child_clipTop = clipTopParam
            var child_clipBottom = clipBottomParam
            val child_xoffset = xoffsetParam + elementWidth
            var yoffset = yoffsetParam

            for (c in entries) {
                val csize = c.sizeForRendering
                parentSize -= csize

                val top = yoffset
                var bottom = top + csize * yscale

                if (child_clipTop > csize) {
                    child_clipTop -= csize
                    child_clipBottom -= csize
                    yoffset = bottom
                    continue
                }

                if (child_clipBottom < 0) {
                    return
                }

                val cchildren = c.children

                if (cchildren != null)
                    paintGPU(
                        c.sizeForRendering, cchildren, rt,
                        child_xoffset, yoffset, yscale,
                        child_clipLeft, child_clipRight, child_clipTop, child_clipBottom, screenHeight
                    )

                if (bottom - top < 4 && deletedEntry !== c) {
                    bottom += parentSize * yscale
                    rt.smallSquare?.draw(xoffsetParam, top, child_xoffset, bottom)
                    return
                }

                if (clipLeftParam < elementWidth) {
                    val fontSize0 = fontSize

                    val isFile = c.children == null
                    val square = if (isFile) rt.fileSquare else rt.dirSquare
                    square?.draw(xoffsetParam, top, child_xoffset, bottom)

                    if (bottom - top > fontSize0 * 2) {
                        var pos = (top + bottom) * 0.5f
                        if (pos < fontSize0) {
                            if (bottom > 2 * fontSize0) {
                                pos = fontSize0
                            } else {
                                pos = bottom - fontSize0
                            }
                        } else if (pos > screenHeight.toFloat() - fontSize0) {
                            if (top < screenHeight.toFloat() - 2 * fontSize0) {
                                pos = screenHeight.toFloat() - fontSize0
                            } else {
                                pos = top + fontSize0
                            }
                        }
                        val pos1 = pos - descent
                        val pos2 = pos - ascent

                        val cache = c.getDrawingCache()
                        cache.drawText(rt, xoffsetParam + 2, pos1, elementWidth - 5)
                        cache.drawSize(rt, xoffsetParam + 2, pos2, elementWidth - 5)
                    } else if (bottom - top > fontSize0) {
                        val cache = c.getDrawingCache()
                        cache.drawText(rt, xoffsetParam + 2, (top + bottom - ascent - descent) / 2, elementWidth - 5)
                    }
                }

                child_clipTop -= csize
                child_clipBottom -= csize
                yoffset = bottom
            }
        }

        private fun paint(
            parent_size: Long, entries: Array<FileSystemEntry>,
            canvas: Canvas, xoffsetParam: Float, yoffsetParam: Float, yscale: Float,
            clipLeftParam: Long, clipRightParam: Long, clipTopParam: Long, clipBottomParam: Long,
            screenHeight: Int
        ) {
            var parentSize = parent_size
            val child_clipLeft = clipLeftParam - elementWidth
            val child_clipRight = clipRightParam - elementWidth
            var child_clipTop = clipTopParam
            var child_clipBottom = clipBottomParam
            val child_xoffset = xoffsetParam + elementWidth
            var yoffset = yoffsetParam

            for (c in entries) {
                val csize = c.sizeForRendering
                parentSize -= csize

                val top = yoffset
                var bottom = top + csize * yscale

                if (child_clipTop > csize) {
                    child_clipTop -= csize
                    child_clipBottom -= csize
                    yoffset = bottom
                    continue
                }

                if (child_clipBottom < 0) {
                    return
                }

                val cchildren = c.children

                if (cchildren != null)
                    paint(
                        c.sizeForRendering, cchildren, canvas,
                        child_xoffset, yoffset, yscale,
                        child_clipLeft, child_clipRight, child_clipTop, child_clipBottom, screenHeight
                    )

                if (bottom - top < 4 && deletedEntry !== c) {
                    bottom += parentSize * yscale
                    canvas.drawRect(xoffsetParam, top, child_xoffset, bottom, fill_bg)
                    canvas.drawRect(xoffsetParam, top, child_xoffset, bottom, fg_rect)
                    return
                }

                if (clipLeftParam < elementWidth) {
                    val fontSize0 = fontSize

                    canvas.drawRect(xoffsetParam, top, child_xoffset, bottom, bg)
                    canvas.drawRect(xoffsetParam, top, child_xoffset, bottom, fg_rect)

                    if (bottom - top > fontSize0 * 2) {
                        var pos = (top + bottom) * 0.5f
                        if (pos < fontSize0) {
                            if (bottom > 2 * fontSize0) {
                                pos = fontSize0
                            } else {
                                pos = bottom - fontSize0
                            }
                        } else if (pos > screenHeight.toFloat() - fontSize0) {
                            if (top < screenHeight.toFloat() - 2 * fontSize0) {
                                pos = screenHeight.toFloat() - fontSize0
                            } else {
                                pos = top + fontSize0
                            }
                        }
                        val pos1 = pos - descent
                        val pos2 = pos - ascent

                        val cache = c.getDrawingCache()
                        val sizeString = cache.sizeString
                        val cliplen = fg2.breakText(c.name, true, (elementWidth - 4).toFloat(), null)
                        val clippedName = c.name?.substring(0, cliplen) ?: ""
                        val paint = if (c.children == null) textPaintFile else textPaintFolder
                        canvas.drawText(clippedName, xoffsetParam + 2, pos1, paint)
                        canvas.drawText(sizeString ?: "", xoffsetParam + 2, pos2, paint)
                    } else if (bottom - top > fontSize0) {
                        val cliplen = fg2.breakText(c.name, true, (elementWidth - 4).toFloat(), null)
                        val clippedName = c.name?.substring(0, cliplen) ?: ""
                        val paint = if (c.children == null) textPaintFile else textPaintFolder
                        canvas.drawText(clippedName, xoffsetParam + 2, (top + bottom - ascent - descent) / 2, paint)
                    }
                }

                child_clipTop -= csize
                child_clipBottom -= csize
                yoffset = bottom
            }
        }

        @JvmStatic
        fun calcSizeString(szParam: Float): String {
            var sz = szParam
            if (sz < 1024 * 1024 * 10) {
                if (sz < 1024 * 1024) {
                    if (sz < 1024) {
                        if (sz < 0) sz = 0f
                        return String.format(n_bytes!!, sz.toInt())
                    }
                    return String.format(n_kilobytes!!, (sz * (1f / 1024)).toInt())
                }
                return java.lang.String.format(n_megabytes!!, sz * (1f / 1024 / 1024))
            }
            if (sz < 1024 * 1024 * 200) {
                return java.lang.String.format(n_megabytes10!!, sz * (1f / 1024 / 1024))
            }
            return String.format(n_megabytes100!!, (sz * (1f / 1024 / 1024)).toInt())
        }

        const val padding: Int = 4

        @JvmStatic
        fun setupStrings(context: Context) {
            if (n_bytes != null) return
            n_bytes = context.getString(R.string.n_bytes)
            n_kilobytes = context.getString(R.string.n_kilobytes)
            n_megabytes = context.getString(R.string.n_megabytes)
            n_megabytes10 = context.getString(R.string.n_megabytes10)
            n_megabytes100 = context.getString(R.string.n_megabytes100)
            n_gigabytes = context.getString(R.string.n_gigabytes)
            n_gigabytes10 = context.getString(R.string.n_gigabytes10)
            n_gigabytes100 = context.getString(R.string.n_gigabytes100)
            dir_name_size_num_dirs = context.getString(R.string.dir_name_size_num_dirs)
            dir_empty = context.getString(R.string.dir_empty)
            dir_name_size = context.getString(R.string.dir_name_size)
        }

        @JvmStatic
        fun updateFontsLegacy(context: Context) {
            var textSize = context.resources.displayMetrics.scaledDensity * 12 + 0.5f
            if (textSize < 10) textSize = 10f
            updateFonts(textSize)
        }

        @JvmStatic
        fun updateFonts(textSize: Float) {
            textPaintFile.textSize = textSize
            textPaintFolder.textSize = textSize
            ascent = textPaintFolder.ascent()
            descent = textPaintFolder.descent()
            fontSize = descent - ascent
        }
    }

    private fun validate0() {
        if (parent != null) {
            parent!!.getIndexOf(this)
            validateRecursive()
            parent!!.validate0()
            return
        }
        validateRecursive()
    }

    private fun validateRecursive() {
        if (children == null) return
        for (child in children!!) {
            if (child.parent !== this)
                throw RuntimeException("corrupted: " + this.path2() + " <> " + child.name)
            child.validateRecursive()
        }
    }
}
