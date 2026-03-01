package com.google.android.diskusage.ui.home

import com.google.android.diskusage.filesystem.mnt.MountPoint

/**
 * Holds pre-computed storage statistics for a single mount point,
 * ready to be displayed in the home screen UI.
 */
data class StorageVolume(
    val mountPoint: MountPoint,
    val title: String,
    val totalBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long,
    val isInternal: Boolean,
) {
    /** 0.0–1.0 fraction of storage used. Returns 0 if totalBytes == 0. */
    val usedFraction: Float
        get() = if (totalBytes == 0L) 0f else usedBytes.toFloat() / totalBytes.toFloat()
}
