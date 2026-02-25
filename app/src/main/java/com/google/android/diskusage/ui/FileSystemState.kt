package com.google.android.diskusage.ui

import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import com.google.android.diskusage.R
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.entity.FileSystemFreeSpace
import com.google.android.diskusage.filesystem.entity.FileSystemSuperRoot
import com.google.android.diskusage.filesystem.entity.FileSystemSystemSpace
import com.google.android.diskusage.opengl.FileSystemViewGPU
import com.google.android.diskusage.opengl.RenderingThread
import splitties.toast.toast
import timber.log.Timber
import java.util.ArrayList
import java.util.Arrays

class FileSystemState(
    context: DiskUsage,
    var masterRoot: FileSystemSuperRoot
) {

    interface FileSystemView {
        /** Does nothing in GPU View.  */
        fun requestRepaint()

        /** Does nothing in GPU View.  */
        fun requestRepaint(l: Int, t: Int, r: Int, b: Int)

        /** Sends event to wake up rendering thread.  */
        fun requestRepaintGPU()

        /** Post event to main thread from other thread.  */
        fun post(r: Runnable): Boolean

        /** Run action in renderer thread.  */
        fun runInRenderThread(r: Runnable)
        fun killRenderThread()
    }

    open class MainThreadAction(protected var context: DiskUsage) {
        open fun updateTitle(position: FileSystemEntry) {
            context.setSelectedEntity(position)
        }

        open fun warnOnFileSelect() {}

        open fun view(entry: FileSystemEntry) {
            context.view(entry)
        }

        open fun finishOnBack() {
            context.finishOnBack()
        }

        open fun searchRequest() {
            context.searchRequest()
        }

        fun indirect(): MainThreadAction {
            return MainThreadActionIndirect(context)
        }

        fun direct(): MainThreadAction {
            return MainThreadAction(context)
        }
    }

    internal class MainThreadActionIndirect(context: DiskUsage) : MainThreadAction(context) {
        override fun updateTitle(position: FileSystemEntry) {
            context.handler.post { context.setSelectedEntity(position) }
        }

        override fun warnOnFileSelect() {
            context.handler.post { toast(R.string.warn_on_file_select) }
        }

        override fun view(entry: FileSystemEntry) {
            context.handler.post { context.view(entry) }
        }

        override fun finishOnBack() {
            context.handler.post { context.finishOnBack() }
        }

        override fun searchRequest() {
            context.handler.post { context.searchRequest() }
        }
    }

    private var view: FileSystemView? = null
    private var cursor: Cursor
    var mainThreadAction: MainThreadAction

    private var numSpecialEntries = 0
    private var freeSpace: FileSystemFreeSpace? = null
    private var systemSpace: FileSystemSystemSpace? = null
    private var freeSpaceZoom: Long = 0

    private var targetViewDepth: Float = 0f
    private var targetViewTop: Long = 0
    private var targetViewBottom: Long = 0
    private var targetElementWidth: Int = 0

    private var prevViewDepth: Float = 0f
    private var prevViewTop: Long = 0
    private var prevViewBottom: Long = 0
    private var prevElementWidth: Int = 0

    private var viewDepth: Float = 0f
    private var viewTop: Long = 0
    private var viewBottom: Long = 0

    private var displayTop: Long = 0
    private var displayBottom: Long = 0

    private var screenWidth = 400 // Safe values to not crash when touch events
    private var screenHeight = 400 // come before screen initialized.

    private var yscale: Float = 0f

    private var animationStartTime: Long = 0
    private val interpolator: Interpolator = DecelerateInterpolator()
    private var maxLevels = 3.2f

    private var fullZoom: Boolean = false
    private var warnOnFileSelect: Boolean = false

    private var touchDepth: Float = 0f
    private var touchPoint: Long = 0

    private var touchEntry: FileSystemEntry? = null
    private var touchX: Float = 0f
    private var touchY: Float = 0f
    private var touchMovement: Boolean = false
    private var speedX: Float = 0f
    private var speedY: Float = 0f

    private var touchZoom: Long = 0
    private var multiNumTouches: Int = 0
    private var multitouchReset: Boolean = false
    private var touchWidth: Float = 0f
    private var touchPointX: Float = 0f
    private var minDistance: Float = 0f
    private var minDistanceX: Float = 0f
    private var minElementWidth: Int = 0
    private var maxElementWidth: Int = 0

    private var stats_num_deletions = 0
    private var screenTouching: Boolean = false

    open class VersionedMultitouchHandler {
        open fun handleTouch(ev: MyMotionEvent): Boolean {
            return false
        }

        open fun setupMulti(ev: MotionEvent, myev: MyMotionEvent) {}

        fun newMyMotionEvent(ev: MotionEvent): MyMotionEvent {
            val myev = MyMotionEvent(ev)
            setupMulti(ev, myev)
            return myev
        }

        companion object {
            fun newInstance(view: FileSystemState): VersionedMultitouchHandler {
                return view.MultiTouchHandler()
            }
        }
    }

    private inner class MultiTouchHandler : VersionedMultitouchHandler() {
        var filterX: ArrayList<MotionFilter> = ArrayList()
        var filterY: ArrayList<MotionFilter> = ArrayList()

        private fun getFilterX(i: Int): MotionFilter {
            if (filterX.size <= i) filterX.add(MotionFilter())
            return filterX[i]
        }

        private fun getFilterY(i: Int): MotionFilter {
            if (filterY.size <= i) filterY.add(MotionFilter())
            return filterY[i]
        }

        override fun setupMulti(ev: MotionEvent, myev: MyMotionEvent) {
            val pointerCount = ev.pointerCount
            val xx = FloatArray(pointerCount)
            val yy = FloatArray(pointerCount)
            for (i in 0 until pointerCount) {
                xx[i] = ev.getX(i)
                yy[i] = ev.getY(i)
            }
            myev.setupMulti(pointerCount, xx, yy)
        }

        override fun handleTouch(ev: MyMotionEvent): Boolean {
            val action = ev.action
            val num = ev.pointerCount
            if (num == 1) {
                return false
            }

            if ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_DOWN) {
                multitouchReset = true
                for (i in 0 until num!!) {
                    getFilterX(i).noFilter(ev.getX(i))
                    getFilterY(i).noFilter(ev.getY(i))
                }
            }

            if (action == MotionEvent.ACTION_MOVE) {
                var xmin: Float
                var xmax: Float
                var ymin: Float
                var ymax: Float
                ymax = getFilterX(0).doFilter(ev.getY(0))
                ymin = ymax
                xmax = getFilterY(0).doFilter(ev.getX(0))
                xmin = xmax
                for (i in 1 until num!!) {
                    val x = getFilterX(i).doFilter(ev.getX(i))
                    val y = getFilterY(i).doFilter(ev.getY(i))
                    if (x < xmin) xmin = x
                    if (x > xmax) xmax = x
                    if (y < ymin) ymin = y
                    if (y > ymax) ymax = y
                }
                if (multitouchReset) {
                    multitouchReset = false
                    multiNumTouches = num
                    touchMovement = true
                    var dy = ymax - ymin
                    if (dy < minDistance) dy = minDistance
                    val avg_y = 0.5f * (ymax + ymin)
                    touchZoom = (displayBottom - displayTop) * dy.toLong() / screenHeight
                    touchPoint = displayTop + (displayBottom - displayTop) * avg_y.toLong() / screenHeight

                    val avg_x = 0.5f * (xmax + xmin)
                    var dx = xmax - xmin
                    minDistanceX = FileSystemEntry.elementWidth / 2f
                    if (dx < minDistanceX) dx = minDistanceX
                    touchWidth = dx / FileSystemEntry.elementWidth
                    touchPointX = viewDepth + avg_x / FileSystemEntry.elementWidth
                    return true
                }
                var dy = ymax - ymin
                if (dy < minDistance) dy = minDistance
                val displayBottom_Top = touchZoom * screenHeight / dy.toLong()
                val avg_y = 0.5f * (ymax + ymin)
                displayTop = touchPoint - displayBottom_Top * avg_y.toLong() / screenHeight
                displayBottom = displayTop + displayBottom_Top

                val avg_x = 0.5f * (xmax + xmin)
                var dx = xmax - xmin
                if (dx < minDistanceX) dx = minDistanceX
                FileSystemEntry.elementWidth = (dx / touchWidth).toInt()

                if (FileSystemEntry.elementWidth < minElementWidth)
                    FileSystemEntry.elementWidth = minElementWidth
                targetElementWidth = FileSystemEntry.elementWidth

                viewDepth = touchPointX - avg_x / FileSystemEntry.elementWidth
                targetViewDepth = viewDepth
                maxLevels = screenWidth / FileSystemEntry.elementWidth.toFloat()

                val dt = (displayBottom - displayTop) / 41
                if (dt < 2) {
                    displayBottom += 41 * 2
                }
                viewTop = displayTop + dt
                viewBottom = displayBottom - dt

                targetViewTop = viewTop
                targetViewBottom = viewBottom
                animationStartTime = 0
                requestRepaint()
                return true
            }
            return true
        }
    }

    var multitouchHandler: VersionedMultitouchHandler = VersionedMultitouchHandler.newInstance(this)

    fun onMotion(newTouchX: Float, newTouchY: Float, moveTime: Long) {
        val touchOffsetX = newTouchX - touchX
        val touchOffsetY = newTouchY - touchY
        speedX += touchOffsetX
        speedY += touchOffsetY
        val dt = moveTime - prevMoveTime
        if (dt > 10) {
            speedX *= 10f / dt
            speedY *= 10f / dt
            prevMoveTime = moveTime - 10
        }

        if (Math.abs(touchOffsetX) < 10 && Math.abs(touchOffsetY) < 10 && !touchMovement)
            return
        touchMovement = true

        viewDepth -= touchOffsetX / FileSystemEntry.elementWidth
        if (viewDepth * FileSystemEntry.elementWidth < -screenWidth * 0.6)
            viewDepth = -screenWidth * 0.6f / FileSystemEntry.elementWidth
        targetViewDepth = viewDepth

        val offset = (touchOffsetY / yscale).toLong()
        val allowedOverflow = (screenHeight * 0.6f / yscale).toLong()
        viewTop -= offset
        viewBottom -= offset

        if (viewTop < -allowedOverflow) {
            val oldTop = viewTop
            viewTop = -allowedOverflow
            viewBottom += viewTop - oldTop
        }

        if (viewBottom > masterRoot.sizeForRendering + allowedOverflow) {
            val oldBottom = viewBottom
            viewBottom = masterRoot.sizeForRendering + allowedOverflow
            viewTop += viewBottom - oldBottom
        }

        targetViewTop = viewTop
        targetViewBottom = viewBottom
        animationStartTime = 0
        touchX = newTouchX
        touchY = newTouchY
        requestRepaint()
    }

    private class MotionFilter {
        var cur: Float = 0f
        var cur2: Float = 0f
        var dx2: Float = 0f

        fun noFilter(value: Float): Float {
            cur = value
            cur2 = value
            dx2 = 0f
            return value
        }

        fun doFilter(val2: Float): Float {
            if (val2 > cur + dx) {
                cur += val2 - (cur + dx)
                dx2--
                if (dx2 < 0) dx2 = 0f
            } else if (val2 < cur - dx) {
                cur += val2 - (cur - dx)
                dx2--
                if (dx2 < 0) dx2 = 0f
            } else {
                dx2++
                if (dx2 > dx) dx2 = dx
            }
            if (val2 > cur2 + dx2) {
                cur2 += val2 - (cur2 + dx2)
            } else if (val2 < cur2 - dx2) {
                cur2 += val2 - (cur2 - dx2)
            }
            return cur2
        }

        companion object {
            var dx: Float = 5f
        }
    }

    private val filterX = MotionFilter()
    private val filterY = MotionFilter()

    class MyMotionEvent(ev: MotionEvent) {
        val eventTime: Long = ev.eventTime
        val x: Float = ev.x
        val y: Float = ev.y
        lateinit var xx: FloatArray
        lateinit var yy: FloatArray
        val action: Int = ev.action
        var pointerCount: Int? = null

        fun setupMulti(pointerCount: Int, xx: FloatArray, yy: FloatArray) {
            this.pointerCount = pointerCount
            this.xx = xx
            this.yy = yy
        }

        fun getX(i: Int): Float {
            return xx[i]
        }

        fun getY(i: Int): Float {
            return yy[i]
        }
    }

    fun onTouchEvent(ev: MyMotionEvent): Boolean {
        try { // finally requestRepaintGPU()
            if (sdcardIsEmpty())
                return true

            if (deletingEntry != null) {
                // setup state of multitouch to reinitialize next time
                multiNumTouches = 0
                return true
            }

            if (multitouchHandler.handleTouch(ev))
                return true

            var newTouchX = ev.x
            var newTouchY = ev.y

            val action = ev.action

            if (multiNumTouches > 1) {
                if (action == MotionEvent.ACTION_UP) {
                    multiNumTouches = 0
                    screenTouching = false
                    requestRepaint()
                }
                return true
            }

            if (action == MotionEvent.ACTION_DOWN) {
                screenTouching = true
                multiNumTouches = 1
                multitouchReset = true
                newTouchX = filterX.noFilter(newTouchX)
                newTouchY = filterY.noFilter(newTouchY)
                touchX = newTouchX
                touchY = newTouchY
                touchDepth = (FileSystemEntry.elementWidth * viewDepth + touchX) /
                        FileSystemEntry.elementWidth
                touchPoint = displayTop + (displayBottom - displayTop) * touchY.toLong() / screenHeight
                touchEntry = masterRoot.findEntry(touchDepth.toInt() + 1, touchPoint)
                if (touchEntry === masterRoot) {
                    touchEntry = null
                    Timber.d("warning: masterRoot selected in onTouchEvent")
                }
                speedX = 0f
                speedY = 0f
                prevMoveTime = ev.eventTime
            } else if (action == MotionEvent.ACTION_MOVE) {
                val moveTime = ev.eventTime
                newTouchX = filterX.doFilter(newTouchX)
                newTouchY = filterY.doFilter(newTouchY)
                onMotion(newTouchX, newTouchY, moveTime)
                return true
            } else if (action == MotionEvent.ACTION_UP) {
                screenTouching = false
                newTouchX = filterX.doFilter(newTouchX)
                newTouchY = filterY.doFilter(newTouchY)

                if (!touchMovement) {
                    if (touchEntry == null) {
                        Timber.d("touchEntry == null")
                        return true
                    }
                    if (masterRoot.depth(touchEntry!!) > touchDepth.toInt() + 1) return true
                    touchSelect(touchEntry!!, ev.eventTime)
                    return true
                }
                touchMovement = false

                run { // copy paste, fling
                    val touchOffsetX = speedX * 15
                    val touchOffsetY = speedY * 15
                    targetViewDepth -= touchOffsetX / FileSystemEntry.elementWidth
                    if (targetViewDepth * FileSystemEntry.elementWidth < -screenWidth * 0.6)
                        targetViewDepth = -screenWidth * 0.6f / FileSystemEntry.elementWidth


                    val offset = (touchOffsetY / yscale).toLong()
                    val allowedOverflow = (screenHeight * 0.6f / yscale).toLong()
                    targetViewTop -= offset
                    targetViewBottom -= offset


                    if (targetViewTop < -allowedOverflow) {
                        val oldTop = targetViewTop
                        targetViewTop = -allowedOverflow
                        targetViewBottom += targetViewTop - oldTop
                    }

                    if (targetViewBottom > masterRoot.sizeForRendering + allowedOverflow) {
                        val oldBottom = targetViewBottom
                        targetViewBottom = masterRoot.sizeForRendering + allowedOverflow
                        targetViewTop += targetViewBottom - oldBottom
                    }
                }

                if (animationStartTime != 0L) return true
                prepareMotion(ev.eventTime)
                animationDuration = 300
                requestRepaint()
            }

        } finally {
            requestRepaintGPU()
        }
        return true
    }

    init {
        this.mainThreadAction = MainThreadAction(context)

        zoomState = ZoomState.ZOOM_ALLOCATED
        targetViewBottom = masterRoot.sizeForRendering
        updateSpecialEntries()
        // FIXME: dirty hacks
        cursor = Cursor(this, masterRoot)
        touchEntry = null
        touchMovement = false
    }

    fun resetCursor() {
        // FIXME: dirty hacks
        cursor = Cursor(this, masterRoot)
        touchEntry = null
        touchMovement = false
    }

    private fun rescanFinished(newRoot: FileSystemSuperRoot) {
        masterRoot = newRoot
        updateSpecialEntries()
        cursor = Cursor(this, masterRoot)
        requestRepaint()
        requestRepaintGPU()
    }

    fun replaceRootKeepCursor(
        newRoot: FileSystemSuperRoot,
        searchQuery: String?
    ) {
        view?.runInRenderThread {
            var oldPosition = cursor.position
            var newPosition = newRoot.getEntryByName(oldPosition.path2(), false)
            if (newPosition == null) {
                newPosition = newRoot.children!![0]
            }
            val newDepth = newRoot.depth(newPosition)
            var oldDepth = masterRoot.depth(cursor.position)
            while (oldDepth > newDepth) {
                oldPosition = oldPosition.parent!!
                oldDepth--
            }
            val oldTop = masterRoot.getOffset(oldPosition)
            val oldSize = oldPosition.sizeForRendering
            val oldBottom = oldTop + oldSize
            val newTop = newRoot.getOffset(newPosition)
            val newSize = newPosition.sizeForRendering
            val newBottom = newTop + newSize
            val above = (oldTop - targetViewTop) / oldSize.toDouble()
            val bellow = (targetViewBottom - oldBottom) / oldSize.toDouble()
            val newViewTop = newTop - (above * newSize).toLong()
            val newViewBottom = (bellow * newSize).toLong() + newBottom
            prepareMotion(SystemClock.uptimeMillis())
            viewTop = newViewTop
            targetViewTop = viewTop
            viewBottom = newViewBottom
            targetViewBottom = viewBottom
            if (targetViewTop > newTop) targetViewTop = newTop
            if (targetViewBottom < newBottom) targetViewBottom = newBottom
            animationDuration = 300
            rescanFinished(newRoot)
            cursor[this@FileSystemState] = newPosition
        }
    }

    fun startZoomAnimationInRenderThread(
        newRoot: FileSystemSuperRoot?,
        animate: Boolean, keepCursor: Boolean
    ) {
        view?.runInRenderThread {
            if (newRoot != null) rescanFinished(newRoot)
            if (animate) {
                val large = masterRoot.sizeForRendering * 10
                val center = masterRoot.sizeForRendering / 2
                viewTop = center - large
                viewBottom = center + large
                viewDepth = 0f
                prepareMotion(SystemClock.uptimeMillis())
                animationDuration = 300
                targetViewTop = 0
                targetViewBottom = masterRoot.sizeForRendering
                targetViewDepth = 0f
                zoomState = ZoomState.ZOOM_ALLOCATED
                setZoomState()
            }
        }
    }

    fun defaultZoom() {
        zoomState = ZoomState.ZOOM_ALLOCATED
        setZoomState()
    }

    fun setView(view: FileSystemView) {
        this.view = view
        if (view is FileSystemViewGPU) {
            mainThreadAction = mainThreadAction.indirect()
        } else {
            mainThreadAction = mainThreadAction.direct()
        }
    }

    private fun updateSpecialEntries() {
        numSpecialEntries = 0
        freeSpace = null
        systemSpace = null
        freeSpaceZoom = 0
        val children = masterRoot.children ?: return
        if (children.isEmpty()) return
        val root = children[0]
        val rootChildren = root.children ?: return
        for (e in rootChildren) {
            if (e is FileSystemSystemSpace) {
                systemSpace = e
                numSpecialEntries++
            }
            if (e is FileSystemFreeSpace) {
                numSpecialEntries++
                freeSpace = e
            }
        }
    }

    private fun preDraw(): Boolean {
        fadeAwayEntry()

        var animation = deletingEntry != null
        val curr = SystemClock.uptimeMillis()
        if (curr > animationStartTime + animationDuration) {
            // no animation
            viewTop = targetViewTop
            viewBottom = targetViewBottom
            viewDepth = targetViewDepth
            FileSystemEntry.elementWidth = targetElementWidth
            maxLevels = screenWidth / targetElementWidth.toFloat()
        } else {
            val f = interpolator.getInterpolation((curr - animationStartTime) / animationDuration.toFloat())
            viewTop = (f * targetViewTop + (1 - f) * prevViewTop).toLong()
            viewBottom = (f * targetViewBottom + (1 - f) * prevViewBottom).toLong()
            viewDepth = (f * targetViewDepth + (1 - f) * prevViewDepth)
            FileSystemEntry.elementWidth = (f * targetElementWidth + (1 - f) * prevElementWidth).toInt()

            animation = true
        }

        val dt = (viewBottom - viewTop) / 40
        displayTop = viewTop - dt
        displayBottom = viewBottom + dt

        yscale = screenHeight / (displayBottom - displayTop).toFloat()
        return animation
    }

    private fun postDraw(animation: Boolean): Boolean {
        var needRepaint = false
        if (animation) {
            //      view.requestRepaint();
            return true
        } else if (!screenTouching) {
            if (targetViewTop < 0 || targetViewBottom > masterRoot.sizeForRendering
                || viewDepth < 0 || FileSystemEntry.elementWidth > maxElementWidth
            ) {
                prepareMotion(SystemClock.uptimeMillis())
                animationDuration = 300
                //        view.requestRepaint();
                needRepaint = true
                if (targetViewTop < 0) {
                    val oldTop = targetViewTop
                    targetViewTop = 0
                    targetViewBottom += targetViewTop - oldTop
                } else if (targetViewBottom > masterRoot.sizeForRendering) {
                    val oldBottom = targetViewBottom
                    targetViewBottom = masterRoot.sizeForRendering
                    targetViewTop += targetViewBottom - oldBottom
                }
                if (targetViewTop < 0) {
                    targetViewTop = 0
                }

                if (targetViewBottom > masterRoot.sizeForRendering) {
                    targetViewBottom = masterRoot.sizeForRendering
                }

                if (viewDepth < 0) {
                    targetViewDepth = 0f
                }
                if (targetElementWidth > maxElementWidth) {
                    targetElementWidth = maxElementWidth
                }
            }
        }
        return needRepaint
    }

    private fun paintSlowGPU(
        rt: RenderingThread,
        viewTop: Long, viewBottom: Long, viewDepth: Float, screenWidth: Int, screenHeight: Int
    ) {
        val bounds2 = Rect(0, 0, screenWidth, screenHeight)
        masterRoot.paintGPU(rt, bounds2, cursor, viewTop, viewDepth, yscale, screenHeight, numSpecialEntries)
    }

    fun onDrawGPU(rt: RenderingThread): Boolean {
        // Log.d("diskusage", "drawFrame (pre) viewTop = " + viewTop + " viewBottom = " + viewBottom);

        try {
            val animation = preDraw()
            // Log.d("diskusage", "drawFrame viewTop = " + viewTop + " viewBottom = " + viewBottom);
            paintSlowGPU(rt, displayTop, displayBottom, viewDepth, screenWidth, screenHeight)
            return postDraw(animation)
        } catch (t: Throwable) {
            Timber.d(t, "onDrawGPU: Got exception")
        }
        return false
    }

    private fun paintSlow(
        canvas: Canvas,
        viewTop: Long, viewBottom: Long, viewDepth: Float, bounds: Rect, screenHeight: Int
    ) {
        if (bounds.bottom != 0 || bounds.top != 0 || bounds.left != 0 || bounds.right != 0) {
            masterRoot.paint(canvas, bounds, cursor, viewTop, viewDepth, yscale, screenHeight, numSpecialEntries)
        } else {
            val bounds2 = Rect(0, 0, screenWidth, screenHeight)
            masterRoot.paint(canvas, bounds2, cursor, viewTop, viewDepth, yscale, screenHeight, numSpecialEntries)
        }
    }

    fun onDraw2(canvas: Canvas) {
        try {
            val animation = preDraw()
            val bounds = canvas.clipBounds
            paintSlow(canvas, displayTop, displayBottom, viewDepth, bounds, screenHeight)
            val needRepaint = postDraw(animation)
            if (needRepaint) {
                requestRepaint()
            }
        } catch (t: Throwable) {
            Timber.d(t, "onDraw2: Got exception")
        }
    }

    fun prepareMotion(time: Long) {
        //    Log.d("diskusage", "prepare motion");
        animationDuration = 900
        prevViewDepth = viewDepth
        prevViewTop = viewTop
        prevViewBottom = viewBottom
        prevElementWidth = FileSystemEntry.elementWidth
        animationStartTime = time
    }

    fun invalidate(cursor: Cursor) {
        val cursorx0 = (cursor.depth - viewDepth) * FileSystemEntry.elementWidth
        val cursory0 = (cursor.top - displayTop) * yscale
        val cursorx1 = cursorx0 + FileSystemEntry.elementWidth
        val cursory1 = cursory0 + cursor.position.sizeForRendering * yscale
        requestRepaint(cursorx0.toInt(), cursory0.toInt(), cursorx1.toInt() + 2, cursory1.toInt() + 2)
    }

    var prevMoveTime: Long = 0


    /*
   * TODO:
   * Add Message to the screen in DeleteActivity
   * Check that DeleteActivity has right title
   * multitouch on eclair
   * Fling works bad on eclair, use 10ms approximation for last movement
   */
    private fun touchSelect(entry: FileSystemEntry, eventTime: Long) {
        val prevCursor = cursor.position
        val prevDepth = cursor.depth
        cursor[this] = entry
        val currDepth = cursor.depth
        prepareMotion(eventTime)

        var isSpecialCase = false
        if (masterRoot.children != null && masterRoot.children!!.isNotEmpty()) {
             if (entry === masterRoot.children!![0]) {
                 isSpecialCase = true
             }
        }

        if (isSpecialCase || entry is FileSystemFreeSpace) {
            //      Log.d("diskusage", "special case for " + entry.name);
            toggleZoomState()
            return
        }

        zoomState = ZoomState.ZOOM_OTHER

        zoomFitLabelMoveUp(eventTime)
        zoomFitToScreen(eventTime)
        val has_children = entry.children != null && entry.children!!.isNotEmpty()
        if (!has_children) {
            //      Log.d("diskusage", "zoom file");
            fullZoom = false
            if (targetViewTop == prevViewTop
                && targetViewBottom == prevViewBottom
            ) {
                if (!warnOnFileSelect && entry !is FileSystemSystemSpace) {
                    mainThreadAction.warnOnFileSelect()
                    warnOnFileSelect = true
                }
            }
            val minRequiredDepth = cursor.depth + 1 + (if (has_children) 1 else 0) - maxLevels
            if (targetViewDepth < minRequiredDepth) {
                targetViewDepth = minRequiredDepth
            }
            return
        } else if (prevCursor === entry) {
            //      Log.d("diskusage", "zoom toggle same element");
            fullZoom = !fullZoom
        } else if (currDepth < prevDepth) {
            //      Log.d("diskusage", "zoom false");
            fullZoom = false
        } else {
            fullZoom = entry.sizeForRendering * yscale > FileSystemEntry.fontSize * 2
        }

        var maxRequiredDepth = cursor.depth - (if (cursor.depth > 0) 1 else 0).toFloat()
        var minRequiredDepth = cursor.depth + 1 + (if (has_children) 1 else 0) - maxLevels
        if (minRequiredDepth > maxRequiredDepth) {
            //      Log.d("diskusage", "zoom levels overlap, fullZoom = " + fullZoom);
            if (fullZoom) {
                maxRequiredDepth = minRequiredDepth
            } else {
                minRequiredDepth = maxRequiredDepth
            }
        }

        if (targetViewDepth < minRequiredDepth) {
            targetViewDepth = minRequiredDepth
        } else if (targetViewDepth > maxRequiredDepth) {
            targetViewDepth = maxRequiredDepth
        }
        if (fullZoom) {
            targetViewTop = cursor.top
            targetViewBottom = cursor.top + cursor.position.sizeForRendering
        } else {
        }
        if (targetViewBottom == prevViewBottom && targetViewTop == prevViewTop) {
            fullZoom = false
            targetViewTop = cursor.top + 1
            targetViewBottom = cursor.top + cursor.position.sizeForRendering - 1
            zoomFitLabelMoveUp(eventTime)
            zoomFitToScreen(eventTime)
        }
        val freeSpaceClip = getFreeSpaceZoom()
        if (targetViewBottom > freeSpaceClip) {
            targetViewBottom = freeSpaceClip
            if (targetViewTop == 0L) zoomState = ZoomState.ZOOM_ALLOCATED
        }
    }

    private fun zoomFitLabel(eventTime: Long) {
        if (cursor.position.sizeForRendering == 0L) {
            //Log.d("DiskUsage", "position is of zero size");
            return
        }

        val yscale = screenHeight / (targetViewBottom - targetViewTop).toFloat()

        if (cursor.position.sizeForRendering * yscale > FileSystemEntry.fontSize * 2 + 2) {
            //Log.d("DiskUsage", "position large enough to contain label");
        } else {
            //Log.d("DiskUsage", "zoom in");
            val new_yscale = FileSystemEntry.fontSize * 2.5f / cursor.position.sizeForRendering
            prepareMotion(eventTime)

            targetViewTop = targetViewBottom - (screenHeight / new_yscale).toLong()

            if (targetViewTop > cursor.top) {
                //Log.d("DiskUsage", "moving down to fit view after zoom in");
                // 10% from top
                val offset = cursor.top - (targetViewTop * 0.8 + targetViewBottom * 0.2).toLong()
                targetViewTop += offset
                targetViewBottom += offset

                if (targetViewTop < 0) {
                    //Log.d("DiskUsage", "at the top");
                    targetViewBottom -= targetViewTop
                    targetViewTop = 0
                }
            }
        }
    }

    private fun zoomFitLabelMoveUp(eventTime: Long) {
        if (cursor.position.sizeForRendering == 0L) {
            //Log.d("DiskUsage", "position is of zero size");
            return
        }

        zoomFitLabel(eventTime)

        if (targetViewBottom < cursor.top + cursor.position.sizeForRendering) {
            //Log.d("DiskUsage", "move up as needed");
            prepareMotion(eventTime)

            val offset = cursor.top + cursor.position.sizeForRendering - (targetViewTop * 0.2 + targetViewBottom * 0.8).toLong()
            targetViewTop += offset
            targetViewBottom += offset
            if (targetViewBottom > masterRoot.sizeForRendering) {
                val diff = targetViewBottom - masterRoot.sizeForRendering
                targetViewBottom = masterRoot.sizeForRendering
                targetViewTop -= diff
            }
        }
        requestRepaint()
    }

    private fun zoomFitToScreen(eventTime: Long) {
        if (targetViewTop < cursor.top &&
            targetViewBottom > cursor.top + cursor.position.sizeForRendering
        ) {

            // Log.d("DiskUsage", "fits in, no need for zoom out");
            return
        }

        //Log.d("DiskUsage", "zoom out");

        prepareMotion(eventTime)

        val viewRoot = cursor.position.parent!!
        targetViewTop = masterRoot.getOffset(viewRoot)
        val size = viewRoot.sizeForRendering
        targetViewBottom = targetViewTop + size
        zoomFitLabelMoveUp(eventTime)
        requestRepaint()
    }

    private fun back(eventTime: Long): Boolean {
        val newpos = cursor.position.parent
        if (newpos === masterRoot) {
            return false
        }
        cursor[this] = newpos!!

        if (masterRoot.children != null && newpos === masterRoot.children!![0]) {
            prepareMotion(eventTime)
            zoomState = ZoomState.ZOOM_FULL
            setZoomState()
            return true
        }

        val requiredDepth = cursor.depth - if (cursor.position.parent === masterRoot) 0 else 1
        if (targetViewDepth > requiredDepth) {
            prepareMotion(eventTime)
            targetViewDepth = requiredDepth.toFloat()
        }
        zoomFitToScreen(eventTime)
        return true
    }

    val isGPU: Boolean
        get() = view is FileSystemViewGPU

    private fun moveAwayCursor(entry: FileSystemEntry) {
        if (cursor.position !== entry) return
        //    FIXME: should not be needed
        //    view.requestRepaint();
        //    cursor.set(this, entry);
        try {
            cursor.up(this)
        } catch (e: RuntimeException) {
            // getPrev -> getIndexOf() can sometimes when this called from moveAwayCursor()
        }
        if (cursor.position !== entry) {
            return
        }
        cursor.left(this)

        //    if (cursor.position == entry) {
        //      cursor.position = masterRoot.children[0];
        //    }
    }

    fun removeInRenderThread(entry: FileSystemEntry) {
        view?.runInRenderThread {
            stats_num_deletions++
            fadeAwayEntryStart(entry, this@FileSystemState)
        }
        requestRepaintGPU()
        requestRepaint()
    }

    private var deletingEntry: FileSystemEntry? = null
    private var deletingAnimationStartTime: Long = 0
    private var deletingInitialSize: Long = 0

    private fun deleteDeletingEntry() {
        if (deletingEntry!!.parent === masterRoot) {
            throw RuntimeException("sdcard deletion is not available in UI")
        }
        val displayBlockSize = masterRoot.displayBlockSize
        moveAwayCursor(deletingEntry!!)
        deletingEntry!!.remove(displayBlockSize)
        val deletingEntryBlocks = deletingEntry!!.sizeInBlocks
        if (freeSpace != null) {
            freeSpace!!.setSizeInBlocks(freeSpace!!.sizeInBlocks + deletingEntryBlocks, displayBlockSize)
            masterRoot.setSizeInBlocks(masterRoot.sizeInBlocks + deletingEntryBlocks, displayBlockSize)
            masterRoot.children!![0].setSizeInBlocks(
                masterRoot.children!![0].sizeInBlocks
                        + deletingEntryBlocks, displayBlockSize
            )
            freeSpace!!.clearDrawingCache()
        }

        FileSystemEntry.deletedEntry = null
        var parent = deletingEntry!!.parent

        var freeSpaceEncoded: Long = 0
        var systemSpaceEncoded: Long = 0
        if (freeSpace != null) {
            freeSpaceEncoded = freeSpace!!.encodedSize
            freeSpace!!.encodedSize = -2
        }

        if (systemSpace != null) {
            systemSpaceEncoded = systemSpace!!.encodedSize
            systemSpace!!.encodedSize = -1
        }
        // Sort elements otherwise painting code works incorrect
        while (parent != null) {
            Arrays.sort(parent.children, FileSystemEntry.COMPARE)
            parent = parent.parent
        }

        val children = masterRoot.children
        if (children != null && children.isNotEmpty()) {
            val rootChildren = children[0].children
            if (rootChildren != null) {
                for (e in rootChildren) {
                    Timber.d("entry = " + e.name + " " + e.sizeInBlocks)
                }
            }
        }

        if (freeSpace != null) {
            freeSpace!!.encodedSize = freeSpaceEncoded
        }
        if (systemSpace != null) {
            systemSpace!!.encodedSize = systemSpaceEncoded
        }
        deletingEntry = null
        cursor[this] = cursor.position
    }

    private fun fadeAwayEntryStart(entry: FileSystemEntry, view: FileSystemState) {
        if (deletingEntry != null) {
            deleteDeletingEntry()
        }
        deletingAnimationStartTime = 0
        deletingEntry = entry
        FileSystemEntry.deletedEntry = entry
        deletingInitialSize = entry.sizeInBlocks
    }

    // Should be called from main thread
    fun requestRepaint() {
        // Does nothing in GPU View
        view?.requestRepaint()
    }

    // Should be called from main thread
    private fun requestRepaint(l: Int, t: Int, r: Int, b: Int) {
        // Does nothing in GPU View
        view?.requestRepaint(l, t, r, b)
    }

    // Should be called from main thread
    fun requestRepaintGPU() {
        // Only for GPU View
        view?.requestRepaintGPU()
    }

    // *** Called from different threads ***
    fun post(r: Runnable) {
        view?.post(r)
    }

    private fun fadeAwayEntry() {
        var entry = deletingEntry ?: return
        //    Log.d("diskusage", "deletion in progress");

        val time = SystemClock.uptimeMillis()

        if (deletingAnimationStartTime == 0L) {
            deletingAnimationStartTime = time
        }
        val dt = time - deletingAnimationStartTime
        //    Log.d("diskusage", "dt = + " + dt);
        if (dt > deletionAnimationDuration) {
            deleteDeletingEntry()
            return
        }
        requestRepaint()
        val f = interpolator.getInterpolation(dt / animationDuration.toFloat())
        //    Log.d("diskusage", "f = + " + f);
        val prevSize = entry.sizeInBlocks
        var newBlocks = ((1 - f) * deletingInitialSize).toLong()
        //    Log.d("diskusage", "newSize = + " + newSize);
        val dSize = newBlocks - prevSize

        if (dSize >= 0) return

        var parent = entry.parent
        val displayBlockSize = masterRoot.displayBlockSize

        while (parent != null) {
            parent.setSizeInBlocks(parent.sizeInBlocks + dSize, displayBlockSize)
            parent = parent.parent
        }
        if (freeSpace != null) {
            masterRoot.setSizeInBlocks(masterRoot.sizeInBlocks - dSize, displayBlockSize)
            masterRoot.children!![0].setSizeInBlocks(
                masterRoot.children!![0].sizeInBlocks
                        - dSize, displayBlockSize
            )
            freeSpace!!.setSizeInBlocks(freeSpace!!.sizeInBlocks - dSize, displayBlockSize)
        }
        // truncate children
        while (true) {
            val deltaBlocks = newBlocks - entry.sizeInBlocks
            if (deltaBlocks == 0L) return
            entry.setSizeInBlocks(entry.sizeInBlocks + deltaBlocks, displayBlockSize)
            if (entry.children == null || entry.children!!.isEmpty())
                return
            val children = entry.children!!
            var blocks: Long = 0
            val prevEntry = entry
            for (i in children.indices) {
                blocks += children[i].sizeInBlocks
                // if sum of sizes of children less then newSize continue
                if (newBlocks > blocks) continue

                // size of children larger than newSize, need to trunc last child
                val lastChildSizeChange = blocks - newBlocks
                // size of last child will be updated at the begining of while loop
                newBlocks = children[i].sizeInBlocks - lastChildSizeChange

                val newChildren = arrayOfNulls<FileSystemEntry>(i + 1)
                System.arraycopy(children, 0, newChildren, 0, i + 1)
                entry.children = newChildren.filterNotNull().toTypedArray()
                entry = children[i]
                break
            }
            if (prevEntry === entry) {
                // Entry was truncated, but not its children
                break
            }
        }
    }

    fun restore(entry: FileSystemEntry) {
        view?.runInRenderThread {
            if (deletingEntry != null) {
                deleteDeletingEntry()
            }
        }
    }

    fun sdcardIsEmpty(): Boolean {
        return cursor.position === masterRoot
    }

    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (sdcardIsEmpty())
            return false

        try { // finally requestRepaintGPU()

            if (keyCode == KeyEvent.KEYCODE_SEARCH) {
                mainThreadAction.searchRequest()
                return true
            }

            if (keyCode == KeyEvent.KEYCODE_BACK) {
                mainThreadAction.finishOnBack()
                return true
            }

            if (deletingEntry != null) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
                }
                return false
            }

            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                cursor.down(this)
                zoomFitLabelMoveUp(event.eventTime)
                zoomFitToScreen(event.eventTime)
                return true
            }

            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                cursor.up(this)
                zoomFitLabel(event.eventTime)
                zoomFitToScreen(event.eventTime)
                return true
            }

            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                back(event.eventTime)
                return true
            }

            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                cursor.right(this)
                zoomFitLabelMoveUp(event.eventTime)

                val requiredDepth = cursor.depth + 1 + (if (cursor.position.children == null) 0 else 1) - maxLevels
                if (viewDepth < requiredDepth) {
                    prepareMotion(event.eventTime)
                    targetViewDepth = requiredDepth
                }
                return true
            }

            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                val selected = cursor.position
                // FIXME: hack to disable removal of /sdcard
                val children = masterRoot.children
                if (children != null && children.isNotEmpty() && selected === children[0]) return true
                mainThreadAction.view(selected)
            }

            /*if ((keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ENTER)) {
      return back();
    }*/

            //Log.d("DiskUsage", "Key down = " + keyCode + " " + event);
        } finally {
            requestRepaintGPU()
        }
        return false
    }

    // FIXME: can be called from different thread
    fun layout(
        changed: Boolean, left: Int, top: Int, right: Int,
        bottom: Int, width: Int, height: Int
    ) {
        screenWidth = width
        screenHeight = height
        minElementWidth = screenWidth / 8
        maxElementWidth = screenWidth / 2
        // FIXME: may be too large
        MotionFilter.dx = (screenHeight + screenWidth) / 50f

        minDistance = if (screenHeight > screenWidth) screenHeight / 10f else screenWidth / 10f
        Timber.d("Screen = %s x %s", screenWidth, screenHeight)
        targetElementWidth = (screenWidth / maxLevels).toInt()
        FileSystemEntry.elementWidth = targetElementWidth
        setZoomState()
    }

    fun restoreStateInRenderThread(inState: Bundle) {
        view?.runInRenderThread {
            val cursorName = inState.getString("cursor") ?: return@runInRenderThread
            val entry = masterRoot.getEntryByName(cursorName, true) ?: return@runInRenderThread
            cursor[this@FileSystemState] = entry
            viewDepth = inState.getFloat("viewDepth")
            prevViewDepth = viewDepth
            targetViewDepth = prevViewDepth
            viewTop = inState.getLong("viewTop")
            prevViewTop = viewTop
            targetViewTop = prevViewTop
            viewBottom = inState.getLong("viewBottom")
            prevViewBottom = viewBottom
            targetViewBottom = prevViewBottom
            when (inState.getInt("zoomState")) {
                0 -> zoomState = ZoomState.ZOOM_ALLOCATED
                1 -> zoomState = ZoomState.ZOOM_FULL
                else -> zoomState = ZoomState.ZOOM_OTHER
            }
            maxLevels = inState.getFloat("maxLevels")
        }
    }

    fun selectFileInRendererThread(path: String) {
        view?.runInRenderThread {
            val e = masterRoot.getByAbsolutePath(path)
            if (e != null) {
                touchSelect(e, 50)
                touchSelect(e, 5000)
            }
        }
    }

    fun saveState(outState: Bundle) {
        outState.putString("cursor", cursor.position.path2())
        outState.putFloat("viewDepth", viewDepth)
        outState.putLong("viewTop", viewTop)
        outState.putLong("viewBottom", viewBottom)
        outState.putFloat("maxLevels", maxLevels)
        outState.putInt(
            "zoomState",
            if (zoomState == ZoomState.ZOOM_ALLOCATED) 0 else (if (zoomState == ZoomState.ZOOM_FULL) 1 else 2)
        )
    }

    private fun getFreeSpaceZoom(): Long {
        if (freeSpaceZoom != 0L) return freeSpaceZoom
        if (freeSpace == null) return masterRoot.sizeForRendering

        freeSpaceZoom = masterRoot.sizeForRendering
        val busy = masterRoot.sizeForRendering - freeSpace!!.sizeForRendering
        val message = FileSystemEntry.fontSize * 2 + 1f
        val height = screenHeight / 41f * 40f
        var required = (busy * (height / (height - message))).toLong()
        required = (required * (40f / 40.5f)).toLong()
        if (required < freeSpaceZoom * 0.9f)
            freeSpaceZoom = required
        return freeSpaceZoom
    }

    private fun setZoomState() {
        if (screenHeight == 0) return
        if (zoomState == ZoomState.ZOOM_ALLOCATED) {
            targetViewDepth = 0f
            targetViewTop = 0
            targetViewBottom = getFreeSpaceZoom()
        } else if (zoomState == ZoomState.ZOOM_FULL) {
            targetViewDepth = 0f
            targetViewTop = 0
            targetViewBottom = masterRoot.sizeForRendering
        }
    }

    private fun toggleZoomState() {
        zoomState = if (zoomState == ZoomState.ZOOM_ALLOCATED)
            ZoomState.ZOOM_FULL else ZoomState.ZOOM_ALLOCATED
        setZoomState()
    }

    private enum class ZoomState {
        ZOOM_FULL,
        ZOOM_ALLOCATED,
        ZOOM_OTHER
    }

    private var zoomState = ZoomState.ZOOM_OTHER

    fun killRenderThread() {
        view?.killRenderThread()
    }

    fun draw300ms() {
        val curr = SystemClock.uptimeMillis()
        if (curr > animationStartTime + animationDuration) {
            viewTop = targetViewTop
            viewBottom = targetViewBottom
            prepareMotion(SystemClock.uptimeMillis())
            animationDuration = 300
        }
    }

    companion object {
        private var animationDuration: Long = 900
        private const val deletionAnimationDuration: Long = 900
    }
}
