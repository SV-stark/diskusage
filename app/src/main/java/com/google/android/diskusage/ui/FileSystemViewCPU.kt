package com.google.android.diskusage.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.ui.FileSystemState.FileSystemView
import timber.log.Timber

@SuppressLint("ViewConstructor")
class FileSystemViewCPU(
    context: Context,
    private val eventHandler: FileSystemState
) : View(context), FileSystemView {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.GRAY)
        eventHandler.setView(this)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        Timber.d("onTouchEvent: Touch = %s:%s", ev.x, ev.y)
        eventHandler.onTouchEvent(
            eventHandler.multitouchHandler.newMyMotionEvent(ev)
        )
        return true
    }

    override fun requestRepaint() {
        invalidate()
    }

    override fun requestRepaintGPU() {
    }

    override fun requestRepaint(l: Int, t: Int, r: Int, b: Int) {
        invalidate(l, t, r, b)
    }

    override fun onDraw(canvas: Canvas) {
        eventHandler.onDraw2(canvas)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (eventHandler.onKeyDown(keyCode, event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        FileSystemEntry.updateFontsLegacy(context)
        eventHandler.layout(changed, left, top, right, bottom, width, height)
    }

    fun onPause() {}
    fun onResume() {}

    override fun runInRenderThread(r: Runnable) {
        r.run()
    }

    override fun killRenderThread() {
    }
}
