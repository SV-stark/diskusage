package com.google.android.diskusage.filesystem.entity

open class FileSystemRoot protected constructor(name: String?, val rootPath: String, private val deletable: Boolean) : FileSystemEntry(null, name) {

    override fun create(): FileSystemEntry {
        return FileSystemRoot(this.name, this.rootPath, this.deletable)
    }

    override fun filter(pattern: CharSequence?, blockSize: Long): FileSystemEntry? {
        // don't match name
        return filterChildren(pattern ?: "", blockSize)
    }

    override fun isDeletable(): Boolean {
        return deletable
    }

    fun getByAbsolutePath(path: String): FileSystemEntry? {
        val rootPathWithSlash = withSlash(rootPath)
        val pathWithSlash = withSlash(path)

        if (pathWithSlash == rootPathWithSlash) {
            return getEntryByName(path, true)
        }
        if (pathWithSlash.startsWith(rootPathWithSlash)) {
            return getEntryByName(path.substring(rootPathWithSlash.length), true)
        }
        return children?.filterIsInstance<FileSystemRoot>()
            ?.firstNotNullOfOrNull { it.getByAbsolutePath(path) }
    }

    companion object {
        @JvmStatic
        fun makeNode(name: String?, rootPath: String, deletable: Boolean): FileSystemRoot {
            return FileSystemRoot(name, rootPath, deletable)
        }

        @JvmStatic
        fun withSlash(path: String): String {
            var finalPath = path
            if (finalPath.isNotEmpty() && finalPath[finalPath.length - 1] != '/') {
                finalPath += '/'
            }
            return finalPath
        }
    }
}
