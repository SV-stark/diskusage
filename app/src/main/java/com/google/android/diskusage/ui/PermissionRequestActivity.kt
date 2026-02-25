/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008 Ivan Volosyuk
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

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.google.android.diskusage.R
import com.google.android.diskusage.filesystem.mnt.MountPoint
import splitties.toast.toast
import timber.log.Timber

class PermissionRequestActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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

        val i = intent

        val key = i.getStringExtra(DiskUsage.KEY_KEY)
        if (key == null) {
            // Just close instead of crashing later
            finish()
            return
        }

        val mountPoint = MountPoint.getForKey(this, key)
        if (mountPoint == null) {
            finish()
            return
        }
        if (!mountPoint.hasApps() || isAccessGranted) {
            forwardToDiskUsage()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_usage_access_title)
            .setMessage(R.string.dialog_usage_access_desc)
            .setPositiveButton(
                android.R.string.ok
            ) { dialogInterface, i1 ->
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                startActivityForResult(intent, PERMISSION_REQUEST_USAGE_ACCESS_CODE)
            }
            .setNegativeButton(
                android.R.string.cancel
            ) { dialogInterface, i12 -> forwardToDiskUsage() }.create().show()

        requestExternalStoragePermission()
    }

    fun forwardToDiskUsage() {
        val input = intent
        val diskusage = Intent(this, DiskUsage::class.java)
        diskusage.putExtra(DiskUsage.KEY_KEY, input.getStringExtra(DiskUsage.KEY_KEY))
        diskusage.putExtra(DiskUsage.STATE_KEY, input.getBundleExtra(DiskUsage.STATE_KEY))
        startActivityForResult(diskusage, DISKUSAGE_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DISKUSAGE_REQUEST_CODE) {
            setResult(0, data)
            finish()
        } else if (requestCode == PERMISSION_REQUEST_USAGE_ACCESS_CODE) {
            forwardToDiskUsage()
        } else if (requestCode == PERMISSION_REQUEST_EXTERNAL_STORAGE_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    forwardToDiskUsage()
                } else {
                    toast(R.string.dialog_external_storage_access_error)
                }
            }
        }
    }

    private fun requestExternalStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                forwardToDiskUsage()
                return
            } else {
                try {
                    val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    i.data = Uri.parse("package:$packageName")
                    startActivityForResult(i, PERMISSION_REQUEST_EXTERNAL_STORAGE_CODE)
                    return
                } catch (e: Exception) {
                    Log.d("diskusage", "failed to obtain all files access", e)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            ) {
                forwardToDiskUsage()
            } else {
                requestPermissions(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    PERMISSION_REQUEST_EXTERNAL_STORAGE_CODE
                )
            }
        }
    }

    private val isAccessGranted: Boolean
        get() = try {
            val packageManager = packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            val appOpsManager = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            var mode = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = appOpsManager.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    applicationInfo.uid, applicationInfo.packageName
                )
            } else {
                mode = appOpsManager.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    applicationInfo.uid, applicationInfo.packageName
                )
            }
            (mode == AppOpsManager.MODE_ALLOWED)
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    companion object {
        private const val DISKUSAGE_REQUEST_CODE = 10
        private const val PERMISSION_REQUEST_USAGE_ACCESS_CODE = 11
        private const val PERMISSION_REQUEST_EXTERNAL_STORAGE_CODE = 12
    }
}
