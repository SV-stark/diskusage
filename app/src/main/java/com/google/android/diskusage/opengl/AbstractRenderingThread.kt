package com.google.android.diskusage.opengl

import android.view.SurfaceHolder
import timber.log.Timber
import java.util.ArrayList
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLSurface
import javax.microedition.khronos.opengles.GL10

abstract class AbstractRenderingThread : Thread() {
    abstract fun renderFrame(gl: GL10?): Boolean
    abstract fun sizeChanged(gl: GL10?, w: Int, h: Int)
    abstract fun createResources(gl: GL10?)
    abstract fun releaseResources(gl: GL10?)

    private class ExitException : RuntimeException() {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    private val events = ArrayList<Runnable>()
    
    /**
     * True when surfaceAvailable callback was received from surfaceHolder and
     * surfaceDestroyed wasn't yet received.
     * egl is initialized able to render.
     */
    private var surfaceAvailable = false

    /**
     * Window geometry received by SurfaceChangedEvent().
     */
    private var sizeInitialized = false

    /**
     * Repaint was requested due to unfinished animation on drawFrame().
     */
    private var renderLoop = true

    /**
     * Repaint was requested using requestRepaintGPU() call.
     */
    private var repaintEvent = false

    /**
     * Stop rendering thread request received.
     */
    private var stopRenderingThread = false
    private var eglTools: EglTools? = null
    var gl: GL10? = null

    override fun run() {
        eglTools = EglTools()
        gl = eglTools?.gl

        try {
            while (true) {
                runEvents()
                renderLoop = renderFrame(gl)
                eglTools?.swapBuffers()
            }
        } catch (e: ExitException) {
            Timber.e(e, "run: Rendering thread exited cleanly")
        } catch (e: InterruptedException) {
            Timber.e(e, "run: Rendering thread was interrupted")
        }
    }

    @Throws(InterruptedException::class)
    fun runEvents() {
        while (true) {
            var e: Runnable = object : Runnable { override fun run() {} }
            synchronized(events) {
                if (events.isEmpty()) {
                    if (stopRenderingThread && !surfaceAvailable) {
                        Timber.d("*** Rendering thread is about to finish. ***")
                        throw ExitException()
                    }
                    if (surfaceAvailable && sizeInitialized && !stopRenderingThread &&
                        (renderLoop || repaintEvent)
                    ) {
                        repaintEvent = false
                        return
                    }

                    (events as Object).wait()
                    return@synchronized
                }
                e = events.removeAt(0)
            }
            if (e is ControlEvent || !stopRenderingThread) {
                e.run()
            }
        }
    }

    fun addEvent(event: Runnable) {
        synchronized(events) {
            events.add(event)
            (events as Object).notify()
        }
    }

    abstract class ControlEvent : Runnable

    inner class SurfaceAvailableEvent(private val holder: SurfaceHolder, private val a: Boolean) : ControlEvent() {
        override fun run() {
            surfaceAvailable = a
            if (a) {
                eglTools?.initSurface(holder)
                createResources(gl)
            } else {
                eglTools?.destroySurface(holder)
                releaseResources(gl)
            }
        }
    }

    inner class ExitEvent : ControlEvent() {
        override fun run() {
            stopRenderingThread = true
            releaseResources(gl)
        }
    }

    inner class SurfaceChangedEvent(var holder: SurfaceHolder, var w: Int, var h: Int) : ControlEvent() {
        override fun run() {
            sizeChanged(gl, w, h)
            sizeInitialized = w > 0 && h > 0
        }
    }

    private class EglTools {
        private val egl: EGL10 = EGLContext.getEGL() as EGL10
        private val eglDisplay: javax.microedition.khronos.egl.EGLDisplay
        private val eglContext: EGLContext
        private val eglConfig: EGLConfig
        private var surface: EGLSurface? = null

        init {
            eglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            egl.eglInitialize(eglDisplay, version)

            val configSpec = intArrayOf(
                EGL10.EGL_DEPTH_SIZE, 6,
                EGL10.EGL_NONE
            )

            val matched_configs = arrayOfNulls<EGLConfig>(1)
            val num_configs = IntArray(1)
            egl.eglChooseConfig(eglDisplay, configSpec, matched_configs, 1, num_configs)
            eglConfig = matched_configs[0]!!

            eglContext = egl.eglCreateContext(
                eglDisplay, eglConfig, EGL10.EGL_NO_CONTEXT, null
            )
        }

        val gl: GL10
            get() = eglContext.gl as GL10

        fun initSurface(holder: SurfaceHolder?) {
            Timber.d("*** Init Surface ****")

            // Note: I haven't found how to avoid race condition with surfaceCreated
            // and surfaceDestroyed in SurfaceHolder.Callback and the renderer thread.
            try {
                surface = egl.eglCreateWindowSurface(eglDisplay, eglConfig, holder, null)
                egl.eglMakeCurrent(eglDisplay, surface, surface, eglContext)
            } catch (e: Exception) {
                Timber.e(e, "initSurface")
            }
        }

        fun destroySurface(holder: SurfaceHolder?) {
            Timber.d("*** Destroy Surface ***")
            try {
                egl.eglMakeCurrent(
                    eglDisplay, EGL10.EGL_NO_SURFACE,
                    EGL10.EGL_NO_SURFACE,
                    EGL10.EGL_NO_CONTEXT
                )
                egl.eglDestroySurface(eglDisplay, surface)
                egl.eglDestroyContext(eglDisplay, eglContext)
                egl.eglTerminate(eglDisplay)
            } catch (e: Exception) {
                Timber.e(e, "destroySurface")
            }
        }

        fun swapBuffers() {
            egl.eglSwapBuffers(eglDisplay, surface)
        }
    }

    fun addEmptyEvent() {
        synchronized(events) {
            repaintEvent = true
            (events as Object).notify()
        }
    }
}
