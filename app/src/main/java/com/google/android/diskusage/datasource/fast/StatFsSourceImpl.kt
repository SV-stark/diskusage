package com.google.android.diskusage.datasource.fast

import android.os.StatFs
import com.google.android.diskusage.datasource.StatFsSource

class StatFsSourceImpl(path: String?) : StatFsSource {
    private val statFs: StatFs

    init {
        statFs = StatFs(path)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use availableBlocksLong instead", replaceWith = ReplaceWith("availableBlocksLong"))
    override val availableBlocks: Int
        get() = statFs.availableBlocks
    override val availableBlocksLong: Long
        get() = statFs.availableBlocksLong
    override val availableBytes: Long
        get() = statFs.availableBytes

    @Suppress("DEPRECATION")
    @Deprecated("")
    override val blockCount: Int
        get() = statFs.blockCount
    override val blockCountLong: Long
        get() = statFs.blockCountLong

    @Suppress("DEPRECATION")
    @Deprecated("Use blockSizeLong instead", replaceWith = ReplaceWith("blockSizeLong"))
    override val blockSize: Int
        get() = statFs.blockSize
    override val blockSizeLong: Long
        get() = statFs.blockSizeLong
    override val freeBytes: Long
        get() = statFs.freeBytes

    @Suppress("DEPRECATION")
    @Deprecated("Use freeBlocksLong instead", replaceWith = ReplaceWith("freeBlocksLong"))
    override val freeBlocks: Int
        get() = statFs.freeBlocks
    override val freeBlocksLong: Long
        get() = statFs.freeBlocksLong
    override val totalBytes: Long
        get() = statFs.totalBytes
}
