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

open class FileSystemSpecial : FileSystemEntry {

    constructor(name: String?, size: Long, blockSize: Long) : super(null, name) {
        initSizeInBytes(size, blockSize)
    }

    constructor(name: String?, children: Array<FileSystemEntry>?, blockSize: Int) : super(null, name) {
        this.setChildren(children, blockSize.toLong())
    }

    override fun filter(pattern: CharSequence?, blockSize: Long): FileSystemEntry? {
        return filterChildren(pattern ?: "", blockSize)
    }

    override fun create(): FileSystemEntry {
        // dummy values for size
        return FileSystemSpecial(this.name, 0, 512)
    }
}
