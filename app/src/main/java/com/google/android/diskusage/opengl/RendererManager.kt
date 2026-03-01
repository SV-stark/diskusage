package com.google.android.diskusage.opengl

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import com.google.android.diskusage.filesystem.entity.FileSystemSuperRoot
import com.google.android.diskusage.ui.DiskUsage
import com.google.android.diskusage.ui.FileSystemState
import com.google.android.diskusage.ui.FileSystemViewCPU

class RendererManager(private val diskusage: DiskUsage) {
    private var hwRenderer = false
    private var rendererChanged = false

    private val prefs: SharedPreferences
        get() = diskusage.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun switchRenderer(root: FileSystemSuperRoot?) {
        diskusage.fileSystemState?.killRenderThread()
        finishRendererSwitch(root)
    }

    fun finishRendererSwitch(root: FileSystemSuperRoot?) {
        hwRenderer = !hwRenderer
        rendererChanged = true
        val state = diskusage.fileSystemState
        root?.let { state?.let { s -> makeView(s, it) } }
    }

    fun makeView(
        eventHandler: FileSystemState,
        root: FileSystemSuperRoot,
    ) {
        val view: View = if (hwRenderer) {
            FileSystemViewGPU(diskusage, eventHandler)
        } else {
            FileSystemViewCPU(diskusage, eventHandler)
        }
        diskusage.menu.wrapAndSetContentView(view, root)
        view.requestFocus()
    }

    fun onResume() {
        hwRenderer = prefs.getBoolean(HW_RENDERER, true)
    }

    fun onPause() {
        if (rendererChanged) {
            prefs.edit().putBoolean(HW_RENDERER, hwRenderer).apply()
        }
    }

    companion object {
        private const val HW_RENDERER = "hw_renderer"
    }
}
