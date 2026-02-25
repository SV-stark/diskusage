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

package com.google.android.diskusage.ui

import com.google.android.diskusage.filesystem.entity.FileSystemEntry

class Cursor internal constructor(
    state: FileSystemState,
    var root: FileSystemEntry
) {
    var position: FileSystemEntry
    var top: Long
    var depth: Int

    init {
        val children = root.children
        if (children == null || children.isEmpty()) {
            throw RuntimeException("no place for position")
        }
        position = children[0]
        depth = 0
        top = 0
        updateTitle(state)
    }

    fun updateTitle(state: FileSystemState) {
        state.mainThreadAction.updateTitle(position)
    }


    fun down(view: FileSystemState) {
        val newCursor = position.next
        if (newCursor === position) return
        view.invalidate(this)
        top += position.sizeForRendering
        position = newCursor
        view.invalidate(this)
        updateTitle(view)
    }

    fun up(view: FileSystemState) {
        val newCursor = position.prev
        if (newCursor === position) return
        view.invalidate(this)
        top -= newCursor.sizeForRendering
        position = newCursor
        view.invalidate(this)
        updateTitle(view)
    }

    fun right(state: FileSystemState) {
        val children = position.children ?: return
        if (children.isEmpty()) return
        state.invalidate(this)
        position = children[0]
        depth++
        // Log.d("Sample", "position depth = " + depth);
        state.invalidate(this)
        updateTitle(state)
    }

    fun left(state: FileSystemState) {
        if (position.parent === root) return
        state.invalidate(this)
        position = position.parent!! // Position can't have null parent here except if equal to root.
        top = root.getOffset(position)
        depth--
        // Log.d("Sample", "position depth = " + depth);
        state.invalidate(this)
        updateTitle(state)
    }

    operator fun set(state: FileSystemState, newpos: FileSystemEntry) {
        if (newpos === root) throw RuntimeException("will break zoomOut()")
        state.invalidate(this)
        position = newpos
        depth = root.depth(position) - 1
        // Log.d("Sample", "position depth = " + depth);
        top = root.getOffset(position)
        state.invalidate(this)
        updateTitle(state)
    }

    fun refresh(view: FileSystemState) {
        set(view, position)
    }
}
