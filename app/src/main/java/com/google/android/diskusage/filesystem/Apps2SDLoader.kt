package com.google.android.diskusage.filesystem
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.storage.StorageManager
import androidx.lifecycle.lifecycleScope
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.entity.FileSystemPackage
import com.google.android.diskusage.ui.DiskUsage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import java.util.Arrays

class Apps2SDLoader(private val diskUsage: DiskUsage) {
    // @Volatile ensures single-writer/single-reader visibility without a lock.
    // The IO thread writes; the progress coroutine on the main thread reads.
    @Volatile private var lastAppName: CharSequence = ""
    private var switchToSecondary = true
    private var numLoadedPackages = 0

    @Throws(Throwable::class)
    fun load(blockSize: Long): Array<FileSystemEntry> {
        val storageStatsManager = diskUsage.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val entries = mutableListOf<FileSystemEntry>()
        val packageManager = diskUsage.applicationContext.packageManager

        // FIX: Getting installed applications is much faster than queryUsageStats
        val packages = packageManager.getInstalledApplications(0).map { it.packageName }.toSet()

        val progressJob: Job = diskUsage.lifecycleScope.launch {
            while (isActive) {
                val dialog = diskUsage.persistentState.loading
                if (dialog != null) {
                    if (switchToSecondary) {
                        dialog.switchToSecondary()
                        switchToSecondary = false
                    }
                    dialog.max = packages.size.toLong()
                    dialog.setProgress(numLoadedPackages.toLong(), lastAppName)
                }
                delay(50)
            }
        }

        for (pkg in packages) {
            Timber.d("app: $pkg")
            try {
                val metadata = packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
                // Volatile write — visible to progress coroutine on next read
                lastAppName = metadata.loadLabel(packageManager).toString()
                val stats = storageStatsManager.queryStatsForPackage(
                    StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle()
                )
                Timber.d("stats: ${stats.appBytes} ${stats.dataBytes}")
                val p = FileSystemPackage(
                    lastAppName.toString(),
                    pkg,
                    stats.appBytes,
                    stats.dataBytes,
                    stats.cacheBytes,
                    metadata.flags
                )
                p.applyFilter(blockSize)
                entries.add(p)
                numLoadedPackages++
            } catch (e: PackageManager.NameNotFoundException) {
                Timber.d(e, "Failed to get package")
            } catch (e: IOException) {
                Timber.d(e, "Failed to get package stats")
            } catch (e: SecurityException) {
                Timber.d(e, "Failed to get package stats security")
            }
        }

        val result = entries.toTypedArray()
        Arrays.sort(result, FileSystemEntry.COMPARE)
        progressJob.cancel()
        return result
    }
}
