package com.google.android.diskusage.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.ui.home.HomeScreen
import com.google.android.diskusage.ui.home.HomeViewModel
import com.google.android.diskusage.ui.home.StorageVolume
import com.google.android.diskusage.ui.theme.DiskUsageTheme
import com.google.android.diskusage.utils.ThemeHelper

class SelectActivity : ComponentActivity() {

    // Bundles passed back from DiskUsage when user presses Back
    private val bundles: MutableMap<String, Bundle?> = mutableMapOf()

    private val viewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileSystemEntry.setupStrings(this)

        // Restore any saved treemap states from a previous session
        savedInstanceState?.getStringArray(BUNDLE_KEYS)?.forEach { key ->
            bundles[key] = savedInstanceState.getBundle(key)
        }

        // Enable edge-to-edge rendering
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val isAmoled = ThemeHelper.isAmoledTheme(this)

        setContent {
            DiskUsageTheme(amoled = isAmoled) {
                val volumes by viewModel.volumes.collectAsState()
                HomeScreen(
                    volumes           = volumes,
                    onVolumeSelected  = { volume -> openDiskUsage(volume) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Reload mount points in case device state changed
        viewModel.refresh()
    }

    private fun openDiskUsage(volume: StorageVolume) {
        val mp  = volume.mountPoint
        val key = mp.key
        val intent = Intent(this, PermissionRequestActivity::class.java).apply {
            putExtra(DiskUsage.KEY_KEY, key)
            bundles[key]?.let { putExtra(DiskUsage.STATE_KEY, it) }
        }
        startActivityForResult(intent, REQUEST_DISK_USAGE)
    }

    @Deprecated("Uses old API; kept for state pass-back compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_DISK_USAGE || data == null) return
        val key   = data.getStringExtra(DiskUsage.KEY_KEY) ?: return
        val state = data.getBundleExtra(DiskUsage.STATE_KEY)
        bundles[key] = state
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val keys = bundles.keys.toTypedArray()
        outState.putStringArray(BUNDLE_KEYS, keys)
        for ((key, bundle) in bundles) {
            if (bundle != null) outState.putBundle(key, bundle)
        }
    }

    companion object {
        private const val BUNDLE_KEYS       = "keys"
        private const val REQUEST_DISK_USAGE = 0
    }
}
