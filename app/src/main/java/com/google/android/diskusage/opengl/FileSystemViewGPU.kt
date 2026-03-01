package com.google.android.diskusage.opengl

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.google.android.diskusage.ui.FileSystemState
import com.google.android.diskusage.ui.FileSystemState.FileSystemView
import timber.log.Timber

@SuppressLint("ViewConstructor")
class FileSystemViewGPU(context: Context, var eventHandler: FileSystemState) :
    SurfaceView(context),
    FileSystemView,
    SurfaceHolder.Callback {
    private val thread: AbstractRenderingThread

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        Timber.d("new FileSystemViewGPU")

        //    setBackgroundColor(Color.GRAY);
        val holder = holder
        holder.setSizeFromLayout()
        holder.addCallback(this)
        eventHandler.setView(this)
        thread = RenderingThread(context, eventHandler)
        thread.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val myev = eventHandler.multitouchHandler.newMyMotionEvent(ev)
        thread.addEvent { eventHandler.onTouchEvent(myev) }
        return true
    }

    override fun runInRenderThread(r: Runnable) {
        thread.addEvent(r)
    }

    override fun requestRepaintGPU() {
        thread.addEmptyEvent()
    }

    override fun requestRepaint() {}
    override fun requestRepaint(l: Int, t: Int, r: Int, b: Int) {}

    override fun onDraw(canvas: Canvas) {}

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        thread.addEvent { eventHandler.onKeyDown(keyCode, event) }
        when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_SEARCH,
            -> return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        //    eventHandler.onLayout(changed, left, top, right, bottom, getWidth(), getHeight());
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) {
        Timber.d("Surface changed to: %s x %s", width, height)
        thread.addEvent(thread.SurfaceChangedEvent(holder, width, height))
        requestRepaintGPU()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        thread.addEvent(thread.SurfaceAvailableEvent(holder, true))
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        holder.removeCallback(this)
        thread.addEvent(thread.SurfaceAvailableEvent(holder, false))
    }

    override fun onDetachedFromWindow() {
        Timber.d("FileSystemViewGPU.onDetachedFromWindow")
        super.onDetachedFromWindow()
        thread.addEvent(thread.ExitEvent())
    }

    override fun invalidate() {
        super.invalidate()
        requestRepaintGPU()
    }

    override fun killRenderThread() {
        thread.addEvent(thread.ExitEvent())
    }
}
