package com.google.android.diskusage.ui

import android.content.Context
import android.os.Bundle
import android.preference.CheckBoxPreference
import android.preference.PreferenceActivity
import com.google.android.diskusage.R
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.mnt.RootMountPoint
import com.google.android.diskusage.ui.DiskUsage.FileSystemStats

class ShowHideMountPointsActivity : PreferenceActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.mount_points_ignore_list)
        preferenceScreen.isOrderingAsAdded = true
    }

    override fun onResume() {
        super.onResume()
        val mountPoints = RootMountPoint.getRootedMountPoints(this)
        val prefs = preferenceScreen
        prefs.removeAll()
        val shprefs = getSharedPreferences("ignore_list", Context.MODE_PRIVATE)
        val ignoreList = shprefs.all
        val ignores = ignoreList.keys

        for (mountPoint in mountPoints) {
            val pref = CheckBoxPreference(this)
            FileSystemEntry.setupStrings(this)
            val stats = FileSystemStats(mountPoint)
            pref.summary = stats.formatUsageInfo()
            pref.title = mountPoint.root
            pref.isChecked = !ignores.contains(mountPoint.root)
            prefs.addPreference(pref)
        }
    }

    override fun onPause() {
        super.onPause()
        val prefs = preferenceScreen
        val shprefs = getSharedPreferences("ignore_list", Context.MODE_PRIVATE)
        val editor = shprefs.edit()
        editor.clear()

        for (i in 0 until prefs.preferenceCount) {
            val pref = prefs.getPreference(i) as CheckBoxPreference
            val root = pref.title.toString()
            if (!pref.isChecked) {
                editor.putBoolean(root, true)
            }
        }
        editor.apply()
    }
}
