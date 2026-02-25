package com.google.android.diskusage.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.google.android.diskusage.R
import com.google.android.diskusage.databinding.ActivityCommonBinding
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.mnt.MountPoint
import com.google.android.diskusage.filesystem.mnt.RootMountPoint
import com.google.android.diskusage.utils.DeviceHelper
import com.google.android.diskusage.utils.IOHelper
import timber.log.Timber
import java.util.ArrayList
import java.util.TreeMap

class SelectActivity : AppCompatActivity() {
    private var dialog: AlertDialog? = null
    private val bundles: MutableMap<String, Bundle> = TreeMap()
    private val actionList = ArrayList<Runnable>()
    private var expandRootMountPoints = false
    private val handler = Handler(Looper.getMainLooper())

    private abstract inner class AbstractUsageAction : Runnable {
        fun runAction(key: String, viewer: Class<*>) {
            val i = Intent(this@SelectActivity, viewer)
            i.putExtra(DiskUsage.KEY_KEY, key)
            val bundle = bundles[key]
            if (bundle != null) {
                i.putExtra(DiskUsage.STATE_KEY, bundle)
            }
            startActivityForResult(i, 0)
        }
    }

    private inner class DiskUsageAction(private val mountPoint: MountPoint) : AbstractUsageAction() {
        override fun run() {
            runAction(mountPoint.key, PermissionRequestActivity::class.java)
        }
    }

    private inner class ShowHideAction : Runnable {
        override fun run() {
            val i = Intent(this@SelectActivity, ShowHideMountPointsActivity::class.java)
            startActivity(i)
        }
    }

    private val checkForMountsUpdates = object : Runnable {
        override fun run() {
            var reload = false
            try {
                val reader = IOHelper.getProcMountsReader()
                var checksum = 0
                reader.useLines { lines ->
                    for (line in lines) {
                        checksum += line.length
                    }
                }
                if (checksum != RootMountPoint.checksum) {
                    Timber.d("%s vs %s", checksum, RootMountPoint.checksum)
                    reload = true
                }
            } catch (ignored: Throwable) {
            }

            if (reload) {
                dialog?.hide()
                MountPoint.reset()
                makeDialog()
            }
            handler.postDelayed(this, 2000)
        }
    }

    fun makeDialog() {
        val options = ArrayList<String>()
        actionList.clear()

        for (mountPoint in MountPoint.getMountPoints(this)) {
            options.add(mountPoint.title)
            actionList.add(DiskUsageAction(mountPoint))
        }

        if (DeviceHelper.isDeviceRooted()) {
            val prefs = getSharedPreferences("ignore_list", Context.MODE_PRIVATE)
            val ignoreList = prefs.all
            if (ignoreList.isNotEmpty()) {
                val ignores = ignoreList.keys
                for (mountPoint in RootMountPoint.getRootedMountPoints(this)) {
                    if (ignores.contains(mountPoint.root)) continue
                    options.add(mountPoint.root)
                    actionList.add(DiskUsageAction(mountPoint))
                }
                options.add("[Show/hide]")
                actionList.add(ShowHideAction())
            } else if (expandRootMountPoints) {
                for (mountPoint in RootMountPoint.getRootedMountPoints(this)) {
                    options.add(mountPoint.root)
                    actionList.add(DiskUsageAction(mountPoint))
                }
                options.add("[Show/hide]")
                actionList.add(ShowHideAction())
            } else {
                options.add("[Root required]")
                actionList.add(Runnable {
                    expandRootMountPoints = true
                    makeDialog()
                })
            }
        }

        val optionsArray = options.toTypedArray()

        dialog = AlertDialog.Builder(this)
            .setItems(optionsArray) { _, which -> actionList[which].run() }
            .setTitle(R.string.ask_view)
            .setOnCancelListener { finish() }
            .create()

        dialog?.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.google.android.diskusage.utils.ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        FileSystemEntry.setupStrings(this)
        val binding = ActivityCommonBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (com.google.android.diskusage.utils.ThemeHelper.isAmoledTheme(this)) {
            window.decorView.setBackgroundColor(android.graphics.Color.BLACK)
        }
    }

    override fun onResume() {
        super.onResume()
        makeDialog()
        handler.post(checkForMountsUpdates)
    }

    override fun onPause() {
        if (dialog?.isShowing == true) dialog?.dismiss()
        handler.removeCallbacks(checkForMountsUpdates)
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (data == null) return
        val state = data.getBundleExtra(DiskUsage.STATE_KEY)
        val key = data.getStringExtra(DiskUsage.KEY_KEY)
        if (key != null && state != null) {
            bundles[key] = state
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        for ((key, value) in bundles) {
            outState.putBundle(key, value)
        }
        val keys = bundles.keys.toTypedArray()
        outState.putStringArray(BUNDLE_KEYS, keys)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val keys = savedInstanceState.getStringArray(BUNDLE_KEYS)
        if (keys != null) {
            for (key in keys) {
                val bundle = savedInstanceState.getBundle(key)
                if (bundle != null) {
                    bundles[key] = bundle
                }
            }
        }
    }

    companion object {
        private const val BUNDLE_KEYS = "keys"
    }
}
