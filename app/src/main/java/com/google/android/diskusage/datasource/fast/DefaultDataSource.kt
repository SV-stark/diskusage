package com.google.android.diskusage.datasource.fast

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import com.google.android.diskusage.datasource.DataSource
import com.google.android.diskusage.datasource.LegacyFile
import com.google.android.diskusage.datasource.PkgInfo
import com.google.android.diskusage.datasource.PortableFile
import com.google.android.diskusage.datasource.StatFsSource
import java.io.File

class DefaultDataSource : DataSource() {
    override fun getInstalledPackages(pm: PackageManager): List<PkgInfo> {
        val flags = PackageManager.GET_META_DATA or PackageManager.MATCH_UNINSTALLED_PACKAGES
        return pm.getInstalledPackages(flags)
            .map { info -> PkgInfoImpl(info, pm) }
    }

    override fun statFs(mountPoint: String?): StatFsSource {
        return StatFsSourceImpl(mountPoint)
    }

    override fun getExternalFilesDir(context: Context): PortableFile? {
        return PortableFileImpl.make(context.getExternalFilesDir(null))
    }

    override fun createLegacyScanFile(root: String): LegacyFile {
        return LegacyFileImpl.createRoot(root)
    }

//    @Throws(Exception::class)
//    override fun getPackageSizeInfo(
//        pkgInfo: PkgInfo,
//        getPackageSizeInfo: Method,
//        pm: PackageManager?,
//        callback: AppStatsCallback?
//    ) {
//        getPackageSizeInfo.invoke(
//            pm,
//            pkgInfo.packageName,
//            object : IPackageStatsObserver.Stub() {
//                fun onGetStatsCompleted(
//                    pStats: PackageStats?, succeeded: Boolean
//                ) {
//                    callback?.onGetStatsCompleted(
//                        if (pStats != null) AppStatsImpl(pStats) else null, succeeded
//                    )
//                }
//            })
//    }

    override val externalStorageDirectory: PortableFile?
        get() = PortableFileImpl.make(Environment.getExternalStorageDirectory())

    override fun getParentFile(file: PortableFile): PortableFile? {
        return PortableFileImpl.make(File(file.absolutePath).getParentFile())
    }
}
