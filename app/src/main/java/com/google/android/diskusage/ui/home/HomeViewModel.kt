package com.google.android.diskusage.ui.home

import android.app.Application
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.diskusage.filesystem.mnt.MountPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val _volumes = MutableStateFlow<List<StorageVolume>>(emptyList())
    val volumes: StateFlow<List<StorageVolume>> = _volumes.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _volumes.value = loadVolumes()
        }
    }

    private suspend fun loadVolumes(): List<StorageVolume> = withContext(Dispatchers.IO) {
        val context = getApplication<Application>()
        MountPoint.reset()
        val mountPoints = MountPoint.getMountPoints(context)
        mountPoints.mapNotNull { mp ->
            try {
                val stat = StatFs(mp.root)
                val blockSize = stat.blockSizeLong
                val totalBytes = stat.blockCountLong * blockSize
                val freeBytes = stat.availableBlocksLong * blockSize
                val usedBytes = totalBytes - freeBytes
                // "internal" if the title is the generic "Storage card" label
                val isInternal = mp.hasApps()
                StorageVolume(
                    mountPoint = mp,
                    title = mp.title,
                    totalBytes = totalBytes,
                    usedBytes = usedBytes,
                    freeBytes = freeBytes,
                    isInternal = isInternal,
                )
            } catch (e: Exception) {
                Timber.w(e, "HomeViewModel: failed to stat ${mp.root}")
                null
            }
        }
    }
}
