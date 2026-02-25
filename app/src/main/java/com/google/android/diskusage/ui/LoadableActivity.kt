package com.google.android.diskusage.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.google.android.diskusage.R
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.entity.FileSystemPackage
import com.google.android.diskusage.filesystem.entity.FileSystemSuperRoot
import com.google.android.diskusage.ui.DiskUsage.AfterLoad
import com.google.android.diskusage.ui.common.ScanProgressDialog
import splitties.toast.toast
import timber.log.Timber
import java.io.IOException
import java.util.TreeMap

abstract class LoadableActivity : AppCompatActivity() {
    var pkg_removed: FileSystemPackage? = null

    class PersistantActivityState {
        var loading: ScanProgressDialog? = null
        var root: FileSystemSuperRoot? = null
        var afterLoad: AfterLoad? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileSystemEntry.setupStrings(this)
    }

    abstract val key: String

    @Throws(IOException::class, InterruptedException::class)
    abstract fun scan(): FileSystemSuperRoot

    val persistantState: PersistantActivityState
        get() {
            val key = this.key
            var state = persistantActivityState[key]
            if (state != null) return state
            state = PersistantActivityState()
            persistantActivityState[key] = state
            return state
        }

    fun LoadFiles(activity: LoadableActivity, runAfterLoad: AfterLoad, force: Boolean) {
        val state = persistantState
        Timber.d("LoadableActivity.LoadFiles(), afterLoad = %s", runAfterLoad)

        if (force) {
            state.root = null
        }

        if (state.root != null) {
            runAfterLoad.run(state.root, true)
            return
        }

        val scanRunning = state.afterLoad != null
        state.afterLoad = runAfterLoad
        Timber.d("LoadFiles: Created new progress dialog")
        state.loading = ScanProgressDialog(activity)

        val thisLoading = state.loading!!
        thisLoading.setOnCancelListener {
            state.loading = null
            activity.finish()
        }
        thisLoading.setCancelable(true)
        thisLoading.max = 1
        thisLoading.setMessage(activity.getString(R.string.scaning_directories))
        thisLoading.show()

        if (scanRunning) return
        val handler = Handler(Looper.getMainLooper())

        Thread {
            var error: String? = null
            try {
                Timber.d("LoadFiles: Running scan for %s", key)
                val newRoot = scan()

                handler.post {
                    if (state.loading == null) {
                        Timber.d("LoadFiles: No dialog, doesn't run afterLoad")
                        state.afterLoad = null
                        if (newRoot.children[0].children != null) {
                            Timber.d("LoadFiles: No dialog, updating root still")
                            state.root = newRoot
                        }
                        return@post
                    }
                    if (state.loading?.isShowing == true) state.loading?.dismiss()
                    state.loading = null
                    val afterLoadCopy = state.afterLoad
                    state.afterLoad = null
                    Timber.d("LoadFiles: Dismissed dialog")

                    if (newRoot.children[0].children == null) {
                        Timber.d("LoadFiles: Empty card")
                        handleEmptySDCard(activity, runAfterLoad)
                        return@post
                    }
                    state.root = newRoot
                    pkg_removed = null
                    Timber.d("LoadFiles: Run afterLoad = %s", afterLoadCopy)
                    afterLoadCopy?.run(state.root, false)
                }
                return@Thread
            } catch (e: OutOfMemoryError) {
                state.root = null
                state.afterLoad = null
                Timber.d("LoadFiles: Out of memory!")
                handler.post {
                    if (state.loading == null) return@post
                    state.loading?.dismiss()
                    handleOutOfMemory(activity)
                }
                return@Thread
            } catch (e: InterruptedException) {
                error = e.javaClass.name + ":" + e.message
                Timber.e(e, "LoadFiles: Native error")
            } catch (e: IOException) {
                error = e.javaClass.name + ":" + e.message
                Timber.e(e, "LoadFiles: Native error")
            } catch (e: RuntimeException) {
                error = e.javaClass.name + ":" + e.message
                Timber.e(e, "LoadFiles: Native error")
            } catch (e: StackOverflowError) {
                error = "Filesystem is damaged."
            }
            val finalError = error
            state.root = null
            state.afterLoad = null
            Timber.d("LoadFiles: Exception in scan!")
            handler.post {
                if (state.loading == null) return@post
                state.loading?.dismiss()
                AlertDialog.Builder(activity)
                    .setTitle(finalError)
                    .setOnCancelListener { activity.finish() }
                    .show()
            }
        }.start()
    }

    override fun onPause() {
        val state = persistantState
        if (state.loading != null) {
            if (state.loading?.isShowing == true) state.loading?.dismiss()
            Timber.d("onPause: Removed progress dialog")
            state.loading = null
        }
        super.onPause()
    }

    private fun handleEmptySDCard(activity: LoadableActivity, afterLoad: AfterLoad?) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.empty_or_missing_sdcard))
            .setPositiveButton(activity.getString(R.string.button_rescan)) { _, _ ->
                if (afterLoad == null) throw RuntimeException("LoadableActivity.handleEmptySDCard(): afterLoad is empty")
                LoadFiles(activity, afterLoad, true)
            }
            .setOnCancelListener { activity.finish() }
            .create().show()
    }

    companion object {
        private val persistantActivityState: MutableMap<String, PersistantActivityState> = TreeMap()

        @JvmStatic
        fun resetStoredStates() {
            persistantActivityState.clear()
        }

        @JvmStatic
        fun forceCleanup(): Boolean {
            var success = false
            for (state in persistantActivityState.values) {
                if (state.afterLoad == null && state.root != null) {
                    state.root = null
                    success = true
                }
            }
            return success
        }

        private fun handleOutOfMemory(activity: Activity) {
            try {
                AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.out_of_memory))
                    .setOnCancelListener { activity.finish() }
                    .create().show()
            } catch (t: Throwable) {
                toast("DiskUsage is out of memory. Sorry.")
            }
        }
    }
}
