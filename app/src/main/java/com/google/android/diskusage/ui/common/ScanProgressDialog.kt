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

package com.google.android.diskusage.ui.common

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.StyleSpan
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import java.text.NumberFormat

class ScanProgressDialog(context: Context?) : AlertDialog(context) {
    private var details: CharSequence? = null
    private lateinit var progressBar: ProgressBar
    private lateinit var detailsTextView: TextView
    private lateinit var percentTextView: TextView

    var progress: Long = 0
    var max: Long = 0
    private lateinit var progressPercentFormat: NumberFormat

    private var depth = 0
    private var warned = false

    private fun path(entry: FileSystemEntry?): String {
        val pathElements = ArrayList<String?>()
        var current = entry
        while (current != null) {
            pathElements.add(current.name)
            current = current.parent
        }

        depth = pathElements.size
        if (depth < 2) return ""
        pathElements.removeAt(depth - 1)
        pathElements.reverse()

        return TextUtils.join("/", pathElements)
    }

    var prevPathChars: CharArray = CharArray(0)

    private fun makePathString(path: String): String {
        //    Log.d("diskusage", "path = " + path);
        val pathChars = path.toCharArray()
        val prevPathChars = this.prevPathChars
        val len = pathChars.size.coerceAtMost(prevPathChars.size)
        var diff: Int
        val textPaint = detailsTextView.paint

        diff = 0
        while (diff < len) {
            if (pathChars[diff] == prevPathChars[diff]) {
                diff++
                continue
            }
            break
        }

        val winWidth = detailsTextView.width.toFloat()
        val extraTextWidth = textPaint.measureText("/.../G")
        val width = winWidth - extraTextWidth
        if (width < extraTextWidth) return path

        var firstSep = -2
        var lastSep = -2

        try {
            if (textPaint.measureText(path, 0, diff) < width) {
                this.prevPathChars = pathChars
                return path
            }

            lastSep = path.lastIndexOf('/', diff)
            firstSep = path.indexOf("/")

            if (lastSep == -1 || firstSep == -1) return path

            var firstPart = textPaint.measureText(path, 0, firstSep)
            var lastPart = textPaint.measureText(path, lastSep, diff)
            if (firstPart + lastPart > width) {
                // need to break first and last string
                do {
                    if (firstPart > lastPart * 3) {
                        firstSep /= 2
                        firstPart = textPaint.measureText(path, 0, firstSep)
                    } else {
                        lastSep = (lastSep + diff) / 2
                        lastPart = textPaint.measureText(path, lastSep, diff)
                    }
                } while (firstPart + lastPart > width)

                this.prevPathChars = pathChars
                return path.substring(0, firstSep) + "..." + path.substring(lastSep)
            }

            while (true) {
                var success = false

                val newLastSep = path.lastIndexOf('/', lastSep - 1)
                if (newLastSep != -1 && newLastSep >= firstSep) {
                    val newLastPart = textPaint.measureText(path, newLastSep, diff)
                    if (firstPart + newLastPart < width) {
                        success = true
                        lastPart = newLastPart
                        lastSep = newLastSep
                    }
                }

                val newFirstSep = path.indexOf('/', firstSep + 1)
                if (newFirstSep != -1 && newFirstSep <= lastSep) {
                    val newFirstPart = textPaint.measureText(path, 0, newFirstSep)
                    if (newFirstPart + lastPart < width) {
                        success = true
                        firstPart = newFirstPart
                        firstSep = newFirstSep
                    }
                }

                if (!success) {
                    this.prevPathChars = pathChars
                    if (firstSep >= lastSep) {
                        return path
                    }
                    return path.substring(0, firstSep) + "/.../" + path.substring(lastSep + 1)
                }
            }
        } catch (e: RuntimeException) {
            throw RuntimeException(
                "path = " + path + "[" + firstSep + ":" + lastSep + "]" +
                    " win =" + winWidth + " extra=" + extraTextWidth + " diff=" + diff,
                e,
            )
        }
    }

    fun onProgressChanged() {
        /* Update the number and percent */
        val percent = progress.toDouble() / max.toDouble() * basePercent + (1 - basePercent)
        progressBar.progress = (percent * 10000).toInt()
        detailsTextView.text = details // progressNumber.setText(String.format(format, progress, max));
        val tmp = SpannableString(progressPercentFormat.format(percent))
        tmp.setSpan(
            StyleSpan(android.graphics.Typeface.BOLD),
            0,
            tmp.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        percentTextView.text = tmp
        //    Log.d("diskusage", "details: " + details);
        //    Log.d("diskusage", "depth = " + depth);
        if (depth > 40 && !warned) {
            warned = true
            setMessage("Cyclic dirs? Broken filesystem?")
        }
    }

    fun setProgress(progress: Long, entry: FileSystemEntry?) {
        this.progress = progress
        this.details = makePathString(path(entry))
        //    Log.d("diskusage", "makePath = " + this.details);
        onProgressChanged()
    }

    var basePercent: Double = 1.0

    fun switchToSecondary() {
        basePercent = 1 - progress.toDouble() / max.toDouble()
    }

    fun setProgress(progress: Long, details: CharSequence?) {
        this.progress = progress
        this.details = details
        onProgressChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val context = context
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        detailsTextView = TextView(context).apply {
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        layout.addView(detailsTextView)
        val progressRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 0)
        }
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            max = 10000
        }
        progressRow.addView(progressBar)
        percentTextView = TextView(context).apply {
            setPadding(20, 0, 0, 0)
        }
        progressRow.addView(percentTextView)
        layout.addView(progressRow)

        progressPercentFormat = NumberFormat.getPercentInstance()
        progressPercentFormat.maximumFractionDigits = 0
        setView(layout)
        onProgressChanged()
        super.onCreate(savedInstanceState)
    }
}
