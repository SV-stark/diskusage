package com.google.android.diskusage.opengl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.opengl.GLUtils
import com.google.android.diskusage.R
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.ui.FileSystemState
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.ArrayList
import javax.microedition.khronos.opengles.GL10

class RenderingThread(
    private val context: Context,
    private val eventHandler: FileSystemState
) : AbstractRenderingThread() {

    private val indicies: ShortBuffer
    private val vertexBuffer: FloatBuffer
    private val texCoords: FloatBuffer
    private val textTexCoords: FloatBuffer
    var dirSquare: Square? = null
    var fileSquare: Square? = null
    var specialSquare: Square? = null
    var smallSquare: SmallSquare? = null
    var cursorSquare: CursorFrame? = null

    var matrix: FloatArray = FloatArray(16)

    //  private float[] dirVertexes = new float[MAX_VERTEX * 3];
    //private float[] fileVertexes = new float[MAX_VERTEX * 3];
    private val textureVertexes = FloatArray(4 * 3)

    private var currentBitmapMap: BitmapMap? = null
    private var editedBitmap: Bitmap? = null
    private var editedCanvas: Canvas? = null

    var textHeight: Int = 0
    var textBaseline: Float = 0f
    var bitmaps: ArrayList<BitmapMap> = ArrayList()

    private var max_usage: Float = 0f

    fun updateFonts(context: Context) {
        val scaledDensity = context.resources.displayMetrics.scaledDensity
        // float density = context.getResources().getDisplayMetrics().density;
        val dpi = 160 * scaledDensity
        val width = context.resources.displayMetrics.widthPixels
        val height = context.resources.displayMetrics.heightPixels
        val min = Math.min(width, height)
        val minInch = min / dpi // my tablet: 5 inch height
        // my phone: 2 inc width
        Timber.d("updateFonts: Screen inch = %s", minInch)

        val defaultSize = textPaint.textSize
        textPaint.textSize = 20f

        // Atleast 4 times "Storage Card" should fit into the screen
        var textSize = 20 * min / (textPaint.measureText("Storage card") * 4)

        // 20 px font, seems confortable enough, if we end up with the font larger
        // than that, we may want to fit 2x more data.
        if (textSize > 20) {
            textSize /= 2f

            // In case we cannot fit 2x more data, we at least fit [1.0, 2.0]x more.
            if (textSize < 20) {
                textSize = 20f
            }
        }

        // For low DPI devices, font size should never go below 12 px (which seems to be default value).
        if (textSize < defaultSize) textSize = defaultSize

        // For very high DPI devices, we might want to check if the physical size of letters is sufficient
        // Let's say, 20 px font on 300 dpi devices seems readable enough: 
        if (textSize.toFloat() / dpi.toFloat() < 20.0f / 300.0f) {
            textSize = (20.0f / 300.0f * dpi).toFloat()
        }

        textPaint.textSize = textSize
        textBaseline = -textPaint.ascent() + FileSystemEntry.padding
        textHeight = (textPaint.descent() - textPaint.ascent() + 1 + 2 * FileSystemEntry.padding).toInt()
        max_usage = ((TEXTURE_SIZE - 1f) / textHeight) * (TEXTURE_SIZE - 2)
        FileSystemEntry.updateFonts(textSize)
    }

    init {
        updateFonts(context)

        val pb = ByteBuffer.allocateDirect(MAX_INDEXES * SIZEOF_SHORT)
        pb.order(ByteOrder.nativeOrder())
        indicies = pb.asShortBuffer()

        val tbb = ByteBuffer.allocateDirect(MAX_VERTEX * SIZEOF_FLOAT * 2)
        tbb.order(ByteOrder.nativeOrder())
        texCoords = tbb.asFloatBuffer()

        val vbb = ByteBuffer.allocateDirect(MAX_VERTEX * SIZEOF_FLOAT * 3)
        vbb.order(ByteOrder.nativeOrder())
        vertexBuffer = vbb.asFloatBuffer()

        val tbb2 = ByteBuffer.allocateDirect(MAX_TEXT_VERTEXES * SIZEOF_FLOAT * 2)
        tbb2.order(ByteOrder.nativeOrder())
        textTexCoords = tbb2.asFloatBuffer()
        textTexCoords.position(0)

        var vertex = 0

        for (i in 0 until MAX_RECTS) {
            indicies.put(
                shortArrayOf(
                    (vertex).toShort(), (1 + vertex).toShort(), (2 + vertex).toShort(),
                    (vertex).toShort(), (2 + vertex).toShort(), (3 + vertex).toShort()
                )
            )

            for (x in 0..3) {
                texCoords.put(vertexData[x][0])
                texCoords.put(vertexData[x][1])
            }
            vertex += 4
        }

        indicies.position(0)
        texCoords.position(0)
    }

    fun drawVertexes(
        out: FloatArray, pos: Int, x0: Float, y0: Float, x1: Float, y1: Float
    ) {
        out[pos] = x0
        out[pos + 1] = y0

        out[pos + 3] = x1
        out[pos + 4] = y0

        out[pos + 6] = x1
        out[pos + 7] = y1

        out[pos + 9] = x0
        out[pos + 10] = y1
    }

    inner class Square(resid: Int) {
        var nrects: Int = 0
        val texture_id: Int
        val vertexes: FloatArray = FloatArray(MAX_VERTEX * 3)

        init {
            texture_id = LoadTexture(getBitmap(resid))
        }

        fun draw(x0: Float, y0: Float, x1: Float, y1: Float) {
            val pos = nrects * 12
            drawVertexes(vertexes, pos, x0, y0, x1, y1)
            nrects++

            if (nrects >= MAX_RECTS) {
                flush()
            }
        }

        fun flush() {
            if (nrects == 0) return
            val gl = this@RenderingThread.gl ?: return
            gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, texCoords)
            gl.glBindTexture(GL10.GL_TEXTURE_2D, texture_id)
            vertexBuffer.put(vertexes, 0, nrects * 12)
            vertexBuffer.position(0)
            gl.glDrawElements(
                GL10.GL_TRIANGLES, nrects * 6,
                GL10.GL_UNSIGNED_SHORT, indicies
            )
            nrects = 0
        }
    }

    inner class SmallSquare(resid: Int) {
        var nrects: Int = 0
        val texture_id: Int
        val vertexes: FloatArray = FloatArray(MAX_VERTEX * 3)
        private val texSmallCoordsBuffer: FloatBuffer
        val texSmallCoords: FloatArray = FloatArray(MAX_VERTEX * 2)

        init {
            texture_id = LoadTexture(getBitmap(resid))
            val tbb = ByteBuffer.allocateDirect(
                MAX_VERTEX * SIZEOF_FLOAT * 2
            )
            tbb.order(ByteOrder.nativeOrder())
            texSmallCoordsBuffer = tbb.asFloatBuffer()
            for (i in 0 until MAX_RECTS) {
                // 0 1  2 3  4 5  6 7
                // 0 0, 1 0, 1 n, 0 n 
                texSmallCoords[i * 8 + 2] = 1f
                texSmallCoords[i * 8 + 4] = 1f
            }
        }

        fun draw(x0: Float, y0: Float, x1: Float, y1: Float) {
            val pos = nrects * 12
            drawVertexes(vertexes, pos, x0, y0, x1, y1)
            texSmallCoords[nrects * 8 + 5] = (y1 - y0) / 4
            texSmallCoords[nrects * 8 + 7] = (y1 - y0) / 4
            nrects++

            if (nrects >= MAX_RECTS) {
                flush()
            }
        }

        fun flush() {
            if (nrects == 0) return
            val gl = this@RenderingThread.gl ?: return
            texSmallCoordsBuffer.put(texSmallCoords, 0, nrects * 8)
            texSmallCoordsBuffer.position(0)
            gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, texSmallCoordsBuffer)
            gl.glBindTexture(GL10.GL_TEXTURE_2D, texture_id)
            vertexBuffer.put(vertexes, 0, nrects * 12)
            vertexBuffer.position(0)
            gl.glDrawElements(
                GL10.GL_TRIANGLES, nrects * 6,
                GL10.GL_UNSIGNED_SHORT, indicies
            )
            nrects = 0
        }
    }

    inner class CursorFrame {
        private val white: Int
        //    private val int black;
        private var dirty = false
        private val vertexes = FloatArray(4 * 4 * 3)


        init {
            white = LoadTexture(getBitmap(R.drawable.white_gradient))
            //      black = LoadTexture(getBitmap(R.drawable.black_gradient));
        }

        fun drawVertexes(
            pos: Int, x0: Float, y0: Float,
            xoff1: Float, yoff1: Float, xoff2: Float, yoff2: Float
        ) {
            vertexes[pos] = x0
            vertexes[pos + 1] = y0
            vertexes[pos + 3] = x0 + xoff1
            vertexes[pos + 4] = y0 + yoff1
            vertexes[pos + 6] = x0 + xoff1 + xoff2
            vertexes[pos + 7] = y0 + yoff1 + yoff2
            vertexes[pos + 9] = x0 + xoff2
            vertexes[pos + 10] = y0 + yoff2
        }

        fun drawFrame(x0: Float, y0: Float, x1: Float, y1: Float) {
            drawVertexes(0, x0, y0, x1 - x0, 0f, 0f, 8f)
            drawVertexes(12, x0, y1, 0f, y0 - y1, 8f, 0f)
            drawVertexes(24, x1, y0, 0f, y1 - y0, -8f, 0f)
            drawVertexes(36, x1, y1, x0 - x1, 0f, 0f, -8f)
            dirty = true
        }

        fun flush() {
            if (!dirty) return
            val gl = this@RenderingThread.gl ?: return
            dirty = false
            gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, texCoords)
            gl.glEnable(GL10.GL_BLEND)
            //      gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA);
            //      gl.glBindTexture(GL10.GL_TEXTURE_2D, black);
            gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE)
            gl.glBindTexture(GL10.GL_TEXTURE_2D, white)
            vertexBuffer.put(vertexes, 2 * 12, 2 * 12)
            vertexBuffer.position(0)
            gl.glDrawElements(
                GL10.GL_TRIANGLES, 2 * 6,
                GL10.GL_UNSIGNED_SHORT, indicies
            )
            gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, texCoords)
            vertexBuffer.put(vertexes, 0, 2 * 12)
            vertexBuffer.position(0)
            gl.glDrawElements(
                GL10.GL_TRIANGLES, 2 * 6,
                GL10.GL_UNSIGNED_SHORT, indicies
            )
            gl.glDisable(GL10.GL_BLEND)
        }
    }

    fun newTextureId(): Int {
        val gl = this.gl ?: return 0
        val ids = IntArray(1)
        gl.glGenTextures(1, ids, 0)
        return ids[0]
    }

    inner class BitmapMap : Comparable<BitmapMap> {
        var textPixelsArray: ArrayList<TextPixels> = ArrayList()
        var textureid: Int = 0
        var usage: Int = 0
        // int last_usage;
        var y: Int = 0
        var x: Int = 0
        var build_x: Int = 0
        var build_y: Int = 0
        var bitmap: Bitmap? = null
        var canvas: Canvas? = null
        var texCoords: FloatArray = FloatArray(MAX_TEXT_TEXCOORDS)
        var vertexes: FloatArray = FloatArray(MAX_TEXT_VERTEXES * 3)
        var nrect: Int = 0
        var inuse: Boolean = false

        init {
            bitmaps.add(this)
            textureid = newTextureId()
            edit()
        }


        var clearPaint: Paint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }

        fun edit() {
            if (editedBitmap == null) {
                editedBitmap = Bitmap.createBitmap(
                    TEXTURE_SIZE, TEXTURE_SIZE, Bitmap.Config.ARGB_8888
                )
                editedCanvas = Canvas(editedBitmap!!)
            } else {
                editedCanvas?.clipRect(Rect(0, 0, TEXTURE_SIZE, TEXTURE_SIZE))
            }
            editedCanvas?.drawPaint(clearPaint)
            bitmap = editedBitmap
            canvas = editedCanvas
            x = 1
            y = 0
            build_x = 1
            build_y = 0
        }

        fun reset() {
            flush()
            for (textPixels in textPixelsArray) {
                textPixels.reset()
            }
            textPixelsArray.clear()
            edit()
        }

        fun flushNoDeps() {
            val gl = this@RenderingThread.gl ?: return
            if (bitmap != null) {
                buildTexture()
            }

            gl.glBindTexture(GL10.GL_TEXTURE_2D, textureid)
            gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, textTexCoords)
            gl.glEnable(GL10.GL_BLEND)
            gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA)

            textTexCoords.put(texCoords, 0, nrect * 8)
            vertexBuffer.put(vertexes, 0, nrect * 12)
            textTexCoords.position(0)
            vertexBuffer.position(0)
            gl.glDrawElements(
                GL10.GL_TRIANGLES, nrect * 6,
                GL10.GL_UNSIGNED_SHORT, indicies
            )
            nrect = 0
            gl.glDisable(GL10.GL_BLEND)

        }

        fun flush() {
            smallSquare?.flush()
            dirSquare?.flush()
            fileSquare?.flush()
            specialSquare?.flush()
            cursorSquare?.flush()
            flushNoDeps()
        }

        fun buildTexture() {
            val gl = this@RenderingThread.gl ?: return
            if (build_x == x && build_y == y) return
            build_x = x
            build_y = y
            gl.glBindTexture(GL10.GL_TEXTURE_2D, textureid)
            GLUtils.texImage2D(GL10.GL_TEXTURE_2D, 0, bitmap, 0)
            gl.glTexEnvf(
                GL10.GL_TEXTURE_ENV, GL10.GL_TEXTURE_ENV_MODE,
                GL10.GL_REPLACE.toFloat()
            )
            gl.glTexParameterx(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_NEAREST
            )
            gl.glTexParameterx(
                GL10.GL_TEXTURE_2D,
                GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_NEAREST
            )
        }

        fun commit() {
            buildTexture()
            //      bitmap.recycle();
            bitmap = null
            canvas = null
            inuse = true
        }

        fun score(): Int {
            if (bitmap != null) return Int.MAX_VALUE
            return usage
        }

        override fun compareTo(other: BitmapMap): Int {
            val score = score()
            val another_score = other.score()
            return Integer.compare(score, another_score)
        }

        fun destroy() {
            for (textPixels in textPixelsArray) {
                textPixels.reset()
            }
        }
    }

    fun getOrInitCurrentBitmapMap(): BitmapMap {
        if (currentBitmapMap == null) {
            currentBitmapMap = BitmapMap()
        }
        return currentBitmapMap!!
    }

    fun hasReusableBitmap(): Boolean {
        // Avoid to hijack texture we just draw into.
        // We still have references in TextPixels in current stack
        // in function: TextPixels.draw()
        // and it is also bad to reuse the same texture as we don't
        // cache anything this way.
        return !bitmaps[0].inuse
    }

    fun getLeastUsedBitmap(): BitmapMap {
        val bitmapMap = bitmaps.removeAt(0)
        bitmaps.add(bitmapMap)
        bitmapMap.reset()
        return bitmapMap
    }

    fun nextBitmapMap() {
        currentBitmapMap?.commit()
        if (bitmaps.size >= 40 && hasReusableBitmap()) {
            //      Log.d("diskusage", "get least used bitmap, bitmaps = 5");
            currentBitmapMap = getLeastUsedBitmap()
            return
        }

        //    float bitmapUsage = bitmaps.get(0).last_usage / max_usage;
        //    if (bitmapUsage < 0.2 && hasReusableBitmap()) {
        ////      Log.d("diskusage", "get least used bitmap, usage = " + bitmapUsage);
        //      currentBitmapMap = getLeastUsedBitmap();
        //      return;
        //    }

        //    Log.d("diskusage", "new bitmap");
        currentBitmapMap = BitmapMap()
    }

    inner class TextPixels {
        private val message: String?
        private val offset: Int
        // Reminder of size after removing offset
        private var size: Int

        private var bitmapMap: BitmapMap? = null
        private var mapX: Int = 0
        private var mapY: Int = 0
        private var mapSize: Int = 0
        private var nextPixels: TextPixels? = null

        constructor(message: String?) {
            this.message = message
            this.size = 0
            this.offset = 0
        }

        fun reset() {
            bitmapMap = null
            nextPixels = null
            size = 0
        }

        constructor(message: String?, size: Int, offset: Int) {
            this.message = message
            this.size = size
            this.offset = offset

        }

        fun draw(rt: RenderingThread, x0: Float, y0: Float, elementWidth: Float) {
            val elementWidthInt = elementWidth.toInt()
            val textHeight = rt.textHeight
            val textBaseline = rt.textBaseline
            if (size == 0) {
                size = (textPaint.measureText(message ?: "") + 1f + 2f * FileSystemEntry.padding).toInt()
            }
            if (bitmapMap == null) {
                bitmapMap = rt.getOrInitCurrentBitmapMap()
                bitmapMap!!.textPixelsArray.add(this)
                mapX = bitmapMap!!.x + 1
                mapY = bitmapMap!!.y
                val todraw = Math.min(size, elementWidthInt + 20)
                val sizeAvailable = (TEXTURE_SIZE - 2) - mapX
                val drawing = Math.min(sizeAvailable, todraw)
                // FIXME: allow 1 additional pixel on line break
                val canvas = bitmapMap!!.canvas
                canvas?.save()
                canvas?.clipRect(
                    Rect(
                        mapX - 1, mapY,
                        mapX + drawing + 1, mapY + textHeight
                    )
                )
                if (message != null) {
                    canvas?.drawText(message, mapX - offset + padding.toFloat(), mapY + textBaseline, textPaint)
                }
                canvas?.restore()
                val newx = mapX + drawing + 1
                bitmapMap!!.x = newx
                if (newx > TEXTURE_SIZE - 20) {
                    bitmapMap!!.x = 1
                    val newy = mapY + textHeight
                    bitmapMap!!.y = newy
                    if (newy > (TEXTURE_SIZE - 1) - textHeight) rt.nextBitmapMap()
                }
                mapSize = drawing
            }
            val todraw = Math.min(size, elementWidthInt)
            val drawing = Math.min(todraw, mapSize)
            bitmapMap!!.usage += drawing
            val tex_x0 = mapX * divTexSize
            val tex_y0 = mapY * divTexSize
            val tex_x1 = (mapX + drawing) * divTexSize
            val tex_y1 = (mapY + textHeight) * divTexSize
            val nrect = bitmapMap!!.nrect
            val off = nrect * 8
            val texCoordsArray = bitmapMap!!.texCoords
            texCoordsArray[off] = tex_x0
            texCoordsArray[off + 1] = tex_y0
            texCoordsArray[off + 2] = tex_x1
            texCoordsArray[off + 3] = tex_y0
            texCoordsArray[off + 4] = tex_x1
            texCoordsArray[off + 5] = tex_y1
            texCoordsArray[off + 6] = tex_x0
            texCoordsArray[off + 7] = tex_y1
            rt.drawVertexes(
                bitmapMap!!.vertexes, nrect * 12,
                x0, y0 - textBaseline, x0 + drawing, y0 + textHeight - rt.textBaseline
            )

            val newrect = nrect + 1
            bitmapMap!!.nrect = newrect
            if (newrect >= MAX_TEXT_DRAWS_PER_TEXTURE) {
                bitmapMap!!.flush()
            }
            if (drawing != todraw) {
                if (nextPixels == null) {
                    nextPixels = TextPixels(message, size - drawing, offset + drawing)
                }
                nextPixels?.draw(rt, x0 + drawing, y0, (elementWidthInt - drawing).toFloat())
            }
        }
    }

    fun flushTexture() {
        val gl = this.gl ?: return
        vertexBuffer.put(textureVertexes, 0, 12)
        vertexBuffer.position(0)
        gl.glDrawElements(
            GL10.GL_TRIANGLES, 6,
            GL10.GL_UNSIGNED_SHORT, indicies
        )
    }

    fun getBitmap(resid: Int): Bitmap {
        val drawable = context.resources.getDrawable(resid, null)
        val bitmap = Bitmap.createBitmap(
            16, 16, Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        drawable.setBounds(0, 0, 16, 16)
        drawable.draw(canvas)
        return bitmap
    }

    private fun LoadTexture(bitmap: Bitmap): Int {
        val gl = this.gl ?: return 0
        val texture_id = newTextureId()
        //    Bitmap bitmap = getBitmap(resid);

        gl.glBindTexture(GL10.GL_TEXTURE_2D, texture_id)
        GLUtils.texImage2D(GL10.GL_TEXTURE_2D, 0, bitmap, 0)
        gl.glTexEnvf(
            GL10.GL_TEXTURE_ENV, GL10.GL_TEXTURE_ENV_MODE,
            GL10.GL_REPLACE.toFloat()
        )
        bitmap.recycle()
        gl.glTexParameterx(
            GL10.GL_TEXTURE_2D,
            GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR
        )
        gl.glTexParameterx(
            GL10.GL_TEXTURE_2D,
            GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_LINEAR
        )
        return texture_id
    }

    private fun LoadTextures(gl: GL10?) {
        dirSquare = Square(R.drawable.dirbg_new)
        fileSquare = Square(R.drawable.filebg_new)
        specialSquare = Square(R.drawable.special)
        smallSquare = SmallSquare(R.drawable.small)
        cursorSquare = CursorFrame()
    }

    fun flush() {
        smallSquare?.flush()
        dirSquare?.flush()
        fileSquare?.flush()
        specialSquare?.flush()
        cursorSquare?.flush()

        for (bitmap in bitmaps) {
            bitmap.flushNoDeps()
        }
    }

    override fun renderFrame(gl: GL10?): Boolean {
        if (gl == null) return false
        //    renderFrameStart();
        val color = Color.GRAY // context.getResources().getColor(android.R.color.background_light);
        val r = ((color shr 16 and 255) / 255.0f)
        val g = ((color shr 8 and 255) / 255.0f)
        val b = ((color and 255) / 255.0f)
        gl.glClearColor(r.toFloat(), g.toFloat(), b.toFloat(), 1.0f)
        gl.glClear(GL10.GL_COLOR_BUFFER_BIT or GL10.GL_DEPTH_BUFFER_BIT)
        gl.glLoadIdentity()
        gl.glScalef(0.5f, 0.5f, 1.0f)

        val renderRequested = eventHandler.onDrawGPU(this)
        flush()
        //    Collections.sort(bitmaps);
        //    for (BitmapMap bitmap : bitmaps) {
        //      bitmap.last_usage = bitmap.usage;
        //      bitmap.inuse = false;
        //      bitmap.usage = 0;
        //    }
        return renderRequested
    }

    override fun createResources(gl: GL10?) {
        Timber.d("***** Surface Created *****")
        // Load textures
        LoadTextures(gl)
    }

    override fun releaseResources(gl: GL10?) {
        Timber.d("***** Surface Destroyed *****")
        for (bitmap in bitmaps) {
            bitmap.destroy()
        }
        bitmaps.clear()
        currentBitmapMap = null
    }

    override fun sizeChanged(gl: GL10?, width: Int, height: Int) {
        if (gl == null) return
        Timber.d("***** Surface Size Changed *****")
        //    FileSystemEntry.elementWidth = 100;// FIXME??;
        //    FileSystemEntry.fontSize = 20; // FIXME
        eventHandler.layout(true, 0, 0, width, height, width, height)
        // Init projection

        gl.glHint(
            GL10.GL_PERSPECTIVE_CORRECTION_HINT,
            GL10.GL_FASTEST
        )
        gl.glViewport(0, 0, width, height)
        Timber.d("sizeChanged: Updated viewport = %s x %s", width, height)


        gl.glMatrixMode(GL10.GL_PROJECTION)
        gl.glLoadIdentity()
        //  0  4  8 12
        //  1  5  9 13
        //  2  6 10 14
        //  3  7 11 15
        matrix[0] = 4.0f / width
        matrix[5] = -4.0f / height
        matrix[10] = 1.0f
        matrix[15] = 1.0f
        matrix[12] = -1.0f
        matrix[13] = 1.0f

        gl.glLoadMatrixf(matrix, 0)

        gl.glMatrixMode(GL10.GL_MODELVIEW)


        gl.glEnable(GL10.GL_DITHER)
        gl.glEnable(GL10.GL_CULL_FACE)
        gl.glShadeModel(GL10.GL_SMOOTH)
        //    gl.glEnable(GL10.GL_DEPTH_TEST);
        //    gl.glDepthFunc(GL10.GL_LESS);
        gl.glFrontFace(GL10.GL_CW)

        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY)
        gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY)
        gl.glEnable(GL10.GL_TEXTURE_2D)
        gl.glVertexPointer(3, GL10.GL_FLOAT, 0, vertexBuffer)
        //    gl.glEnable(GL10.GL_DEPTH_TEST);
        eventHandler.draw300ms()
    }

    companion object {

        private val vertexData = arrayOf(
            floatArrayOf(0.1f, 0.2f, 0f),
            floatArrayOf(0.9f, 0.2f, 0f),
            floatArrayOf(0.9f, 0.9f, 0f),
            floatArrayOf(0.1f, 0.9f, 0f)
        )

        private const val TEXTURE_SIZE = 1 shl 7

        private const val MAX_RECTS = 100

        private const val MAX_INDEXES = MAX_RECTS * 6
        private const val MAX_VERTEX = MAX_RECTS * 4

        private const val SIZEOF_SHORT = 2
        private const val SIZEOF_FLOAT = 4

        private const val MAX_TEXT_DRAWS_PER_TEXTURE = 100
        private const val MAX_TEXT_VERTEXES = MAX_TEXT_DRAWS_PER_TEXTURE * 4
        private const val MAX_TEXT_TEXCOORDS = MAX_TEXT_VERTEXES * 2

        private val textPaint = Paint()
        private val padding = FileSystemEntry.padding

        private const val divTexSize = 1.0f / TEXTURE_SIZE

        init {
            textPaint.color = Color.parseColor("#FFFFFF")
            textPaint.style = Paint.Style.FILL_AND_STROKE
            textPaint.flags = textPaint.flags or Paint.ANTI_ALIAS_FLAG
            textPaint.setShadowLayer(padding.toFloat(), 1f, 1f, Color.BLACK)
            //    textBgPaint.setColor(Color.parseColor("#000000"));
            //    textBgPaint.setStyle(Paint.Style.STROKE);
            //    textBgPaint.setFlags(textPaint.getFlags() | Paint.ANTI_ALIAS_FLAG);
        }
    }
}
