package com.google.android.diskusage.filesystem.entity

class FileSystemFile private constructor(parent: FileSystemEntry?, name: String?) : FileSystemEntry(parent, name) {

    override fun isDeletable(): Boolean {
        return true
    }

    override fun create(): FileSystemEntry {
        return FileSystemFile(null, this.name)
    }

    companion object {
        @JvmStatic
        fun makeNode(parent: FileSystemEntry?, name: String?): FileSystemEntry {
            return FileSystemFile(parent, name)
        }
    }
}
