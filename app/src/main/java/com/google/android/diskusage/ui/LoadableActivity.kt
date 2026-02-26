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

package com.google.android.diskusage.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.diskusage.R
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.entity.FileSystemPackage
import com.google.android.diskusage.filesystem.entity.FileSystemSuperRoot
import com.google.android.diskusage.ui.common.ScanProgressDialog
import splitties.toast.toast
import timber.log.Timber
import java.io.IOException
import java.util.TreeMap

abstract class LoadableActivity : AppCompatActivity() {
    var pkg_removed: FileSystemPackage? = null
    @Suppress("MemberVisibilityCanBePrivate")
    internal val handler = Handler(Looper.getMainLooper())

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileSystemEntry.setupStrings(this)
    }

    abstract val key: String

    @Throws(IOException::class, InterruptedException::class)
    abstract fun scan(): FileSystemSuperRoot

    val persistentState: PersistentActivityState
        get() {
            val currentKey = key

            var state = persistentActivityState[currentKey]
            if (state != null) return state
            state = PersistentActivityState()
            persistentActivityState[currentKey] = state
            return state
        }

    fun loadFiles(
        runAfterLoad: ((FileSystemSuperRoot, Boolean) -> Unit)?, force: Boolean
    ) {
        val scanRunning: Boolean
        val state = persistentState
        Timber.d("LoadableActivity.loadFiles(), afterLoad = %s", runAfterLoad)

        if (force) {
            state.root = null
        }

        if (state.root != null) {
            runAfterLoad?.invoke(state.root!!, true)
            return
        }

        scanRunning = state.afterLoad != null
        state.afterLoad = runAfterLoad
        Timber.d("loadFiles: Created new progress dialog")
        state.loading = ScanProgressDialog(this)

        val thisLoading = state.loading
        state.loading?.setOnCancelListener {
            state.loading = null
            finish()
        }
        thisLoading?.setCancelable(true)
        //    thisLoading.setIndeterminate(true);
        thisLoading?.max = 1
        thisLoading?.setMessage(getString(R.string.scaning_directories))
        thisLoading?.show()

        if (scanRunning) return

        lifecycleScope.launch {
            var error: String? = null
            try {
                Timber.d("loadFiles: Running scan for %s", this@LoadableActivity.key)
                val newRoot = withContext(Dispatchers.IO) { scan() }

                if (state.loading == null) {
                    Timber.d("loadFiles: No dialog, doesn't run afterLoad")
                    state.afterLoad = null
                    if (newRoot.children != null && newRoot.children!!.isNotEmpty() && newRoot.children!![0].children != null) {
                        Timber.d("loadFiles: No dialog, updating root still")
                        state.root = newRoot
                    }
                    return@launch
                }
                if (state.loading?.isShowing == true) state.loading?.dismiss()
                state.loading = null
                val afterLoadCopy = state.afterLoad
                state.afterLoad = null
                Timber.d("loadFiles: Dismissed dialog")

                if (newRoot.children == null || newRoot.children!!.isEmpty() || newRoot.children!![0].children == null) {
                    Timber.d("loadFiles: Empty card")
                    handleEmptySDCard(afterLoadCopy)
                    return@launch
                }
                state.root = newRoot
                pkg_removed = null
                Timber.d("loadFiles: Run afterLoad = %s", afterLoadCopy)
                afterLoadCopy?.invoke(state.root!!, false)
                return@launch
            } catch (e: OutOfMemoryError) {
                state.root = null
                state.afterLoad = null
                Timber.d("loadFiles: Out of memory!")
                if (state.loading == null) return@launch
                state.loading?.dismiss()
                handleOutOfMemory(this@LoadableActivity)
                return@launch
            } catch (e: CancellationException) {
                // Natural cancellation, let it bubble up
                throw e
            } catch (e: Exception) {
                error = e.javaClass.name + ":" + e.message
                Timber.e(e, "loadFiles: Native error")
            } catch (e: StackOverflowError) {
                error = "Filesystem is damaged."
            }
            val finalError = error
            state.root = null
            state.afterLoad = null
            Timber.d("loadFiles: Exception in scan!")
            if (state.loading == null) return@launch
            state.loading?.dismiss()
            AlertDialog.Builder(this@LoadableActivity)
                .setTitle(finalError)
                .setOnCancelListener { finish() }
                .show()
        }
    }

    override fun onPause() {
        val state = persistentState
        if (state.loading != null) {
            if (state.loading?.isShowing == true) state.loading?.dismiss()
            Timber.d("onPause: Removed progress dialog")
            state.loading = null
        }
        super.onPause()
    }

    private fun handleEmptySDCard(
        afterLoad: ((FileSystemSuperRoot, Boolean) -> Unit)?
    ) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.empty_or_missing_sdcard))
            .setPositiveButton(
                getString(R.string.button_rescan)
            ) { dialog, which ->
                if (afterLoad == null) throw RuntimeException("LoadableActivity.handleEmptySDCard(): afterLoad is empty")
                loadFiles(afterLoad, true)
            }
            .setOnCancelListener { finish() }.create().show()
    }

    class PersistentActivityState {
        var loading: ScanProgressDialog? = null
        var root: FileSystemSuperRoot? = null
        var afterLoad: ((FileSystemSuperRoot, Boolean) -> Unit)? = null
    }

    companion object {
        private val persistentActivityState: MutableMap<String, PersistentActivityState> = TreeMap()

        fun resetStoredStates() {
            persistentActivityState.clear()
        }

        // Cleans up cached filesystem states to free memory
        // Only cleans up states that are not currently being used for loading
        fun forceCleanup(): Boolean {
            var success = false
            for (state in persistentActivityState.values) {
                if (state.afterLoad == null && state.root != null) {
                    state.root = null
                    success = true
                }
            }
            return success
        }

        private fun handleOutOfMemory(activity: Activity) {
            try {
                // Can fail if the main window is already closed.
                AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.out_of_memory))
                    .setOnCancelListener { activity.finish() }.create().show()
            } catch (t: Throwable) {
                toast("DiskUsage is out of memory. Sorry.")
            }
        }
    }
}
