package com.google.android.diskusage.datasource

interface StatFsSource {
    val availableBlocksLong: Long
    val availableBytes: Long
    val blockCountLong: Long
    val blockSizeLong: Long
    val freeBytes: Long
    val freeBlocksLong: Long
    val totalBytes: Long
}
