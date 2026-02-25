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

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.google.android.diskusage.R
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.mnt.MountPoint
import com.google.android.diskusage.filesystem.mnt.RootMountPoint
import com.google.android.diskusage.utils.DeviceHelper
import com.google.android.diskusage.utils.IOHelper
import timber.log.Timber
import java.io.BufferedReader
import java.util.ArrayList
import java.util.TreeMap

class SelectActivity : ComponentActivity() {
    private var dialog: AlertDialog? = null
    var bundles: MutableMap<String, Bundle?> = TreeMap()
    var actionList: ArrayList<Runnable> = ArrayList()
    private var expandRootMountPoints = false

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

    var mountsUpdateJob: Job? = null


    fun makeDialog() {
        val options = ArrayList<String>()
        actionList.clear()

        //    PortableFile[] fileDirs = DataSource.get().getExternalFilesDirs(this);
        for (mountPoint in MountPoint.getMountPoints(this)) {
            options.add(mountPoint.title)
            actionList.add(DiskUsageAction(mountPoint))
        }

        if (DeviceHelper.isDeviceRooted) {
            val prefs = getSharedPreferences("ignore_list", Context.MODE_PRIVATE)
            val ignoreList = prefs.all
            if (ignoreList.keys.isNotEmpty()) {
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
            .setItems(
                optionsArray
            ) { dialog, which -> actionList[which].run() }
            .setTitle(R.string.ask_view)
            .setOnCancelListener { dialog -> finish() }.create()
        /*try {
      if (debugDataSourceBridge != null) {
        dialog.getListView().setOnItemLongClickListener(
            new OnItemLongClickListener() {
          @Override
          public boolean onItemLongClick(
              AdapterView<?> arg0, View arg1, int arg2, long arg3) {
            debugUnhidden = true;
            dialog.hide();
            makeDialog();
            return true;
          }
        });
      }
    } catch (Throwable t) {
      // api 3
    }*/
        dialog?.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileSystemEntry.setupStrings(this)
        
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize())
                }
            }
        }
        //    ActionBar bar = getActionBar();
        //    bar.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM | ActionBar.DISPLAY_USE_LOGO);
    }

    override fun onResume() {
        super.onResume()
        makeDialog()
        
        mountsUpdateJob = lifecycleScope.launch {
            while (kotlinx.coroutines.isActive) {
                var reload = false
                try {
                    val reader = IOHelper.procMountsReader
                    var line: String?
                    var checksum = 0
                    while ((reader.readLine().also { line = it }) != null) {
                        checksum += line!!.length
                    }
                    reader.close()
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
                delay(2000)
            }
        }
    }

    override fun onPause() {
        if (dialog?.isShowing == true) dialog?.dismiss()
        mountsUpdateJob?.cancel()
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (data == null) return
        val state = data.getBundleExtra(DiskUsage.STATE_KEY)
        val key = data.getStringExtra(DiskUsage.KEY_KEY)
        if (key != null) {
            bundles[key] = state
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        for ((key, value) in bundles) {
            if (value != null) {
                outState.putBundle(key, value)
            }
        }
        val keys = bundles.keys.toTypedArray()
        outState.putStringArray(BUNDLE_KEYS, keys)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val keys = savedInstanceState.getStringArray(BUNDLE_KEYS)
        if (keys != null) {
            for (key in keys) {
                bundles[key] = savedInstanceState.getBundle(key)
            }
        }
    }

    companion object {
        private const val BUNDLE_KEYS = "keys"
    }
}
