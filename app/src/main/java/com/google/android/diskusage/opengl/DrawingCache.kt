package com.google.android.diskusage.opengl

import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.opengl.RenderingThread.TextPixels

class DrawingCache(private val entry: FileSystemEntry) {
    var sizeString: String? = null
        get() {
            if (field != null) {
                return field
            }
            val sizeString = entry.sizeString()
            this.sizeString = sizeString
            return sizeString
        }
        private set
    var textPixels: TextPixels? = null
    var sizePixels: TextPixels? = null

    fun resetSizeString() {
        sizeString = null
        sizePixels = null
    }

    fun drawText(rt: RenderingThread?, x0: Float, y0: Float, elementWidth: Int) {
        if (textPixels == null) {
            textPixels = rt?.TextPixels(entry.name)
        }
        textPixels?.draw(rt!!, x0, y0, elementWidth.toFloat())
    }

    fun drawSize(rt: RenderingThread?, x0: Float, y0: Float, elementWidth: Int) {
        if (sizePixels == null) {
            sizePixels = rt?.TextPixels(sizeString)
        }
        sizePixels?.draw(rt!!, x0, y0, elementWidth.toFloat())
    }
}
