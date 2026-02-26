package com.google.android.diskusage.datasource.fast

import android.os.StatFs
import com.google.android.diskusage.datasource.StatFsSource

class StatFsSourceImpl(path: String?) : StatFsSource {
    private val statFs: StatFs

    init {
        statFs = StatFs(path)
    }

    override val availableBlocksLong: Long
        get() = statFs.availableBlocksLong
    override val availableBytes: Long
        get() = statFs.availableBytes

    override val blockCountLong: Long
        get() = statFs.blockCountLong

    override val blockSizeLong: Long
        get() = statFs.blockSizeLong
    override val freeBytes: Long
        get() = statFs.freeBytes

    override val freeBlocksLong: Long
        get() = statFs.freeBlocksLong
    override val totalBytes: Long
        get() = statFs.totalBytes
}
