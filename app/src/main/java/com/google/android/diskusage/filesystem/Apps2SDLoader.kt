package com.google.android.diskusage.filesystem
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.storage.StorageManager
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.entity.FileSystemPackage
import com.google.android.diskusage.ui.DiskUsage
import timber.log.Timber
import java.io.IOException
import java.util.ArrayList
import java.util.Arrays

class Apps2SDLoader(private val diskUsage: DiskUsage) {
    private var lastAppName: CharSequence = ""
    private var switchToSecondary = true
    private var numLoadedPackages = 0

    @Throws(Throwable::class)
    fun load(blockSize: Long): Array<FileSystemEntry> {
        val storageStatsManager = diskUsage.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val entries = ArrayList<FileSystemEntry>()
        val packageManager = diskUsage.applicationContext.packageManager

        // FIX: Getting installed applications is much faster than queryUsageStats
        val installedApps = packageManager.getInstalledApplications(0)
        val packages = installedApps.map { it.packageName }.toSet()

        val handler = diskUsage.handler
        val progressUpdater = object : Runnable {
            override fun run() {
                val dialog = diskUsage.persistantState.loading
                if (dialog != null) {
                    if (switchToSecondary) {
                        dialog.switchToSecondary()
                        switchToSecondary = false
                    }
                    dialog.setMax(packages.size.toLong())
                    val appName: CharSequence
                    synchronized(this@Apps2SDLoader) {
                        appName = lastAppName
                    }
                    dialog.setProgress(numLoadedPackages.toLong(), appName)
                }
                diskUsage.handler.postDelayed(this, 50)
            }
        }
        handler.post(progressUpdater)

        for (pkg in packages) {
            Timber.d("app: $pkg")
            try {
                val metadata = packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
                val appName = metadata.loadLabel(packageManager).toString()
                synchronized(this.lastAppName) {
                    lastAppName = appName
                }
                val stats = storageStatsManager.queryStatsForPackage(
                    StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle()
                )
                Timber.d("stats: ${stats.appBytes} ${stats.dataBytes}")
                val p = FileSystemPackage(
                    appName,
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

        val result = entries.toTypedArray<FileSystemEntry>()
        Arrays.sort(result, FileSystemEntry.COMPARE)
        handler.removeCallbacks(progressUpdater)
        return result
    }
}
