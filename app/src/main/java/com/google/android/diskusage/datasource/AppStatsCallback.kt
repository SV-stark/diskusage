package com.google.android.diskusage.datasource

fun interface AppStatsCallback {
    fun onGetStatsCompleted(stats: AppStats?, succeeded: Boolean)
}
