package com.google.android.diskusage.datasource

import androidx.lifecycle.lifecycleScope
import com.google.android.diskusage.filesystem.entity.FileSystemEntry.SearchInterruptedException
import com.google.android.diskusage.filesystem.entity.FileSystemSuperRoot
import com.google.android.diskusage.ui.DiskUsageMenu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchManager(private val menu: DiskUsageMenu) {
    private var finishedSearch: SearchData? = null
    private var activeSearchJob: Job? = null
    private lateinit var query: String

    private data class SearchData(
        val query: String,
        val newRoot: FileSystemSuperRoot?,
    )

    fun search(newQuery: String) {
        query = newQuery.lowercase()
        activeSearchJob?.cancel()
        activeSearchJob = null
        startSearch()
    }

    private fun startSearch() {
        var baseRoot = menu.masterRoot
        finishedSearch?.let {
            if (query.contains(it.query)) {
                baseRoot = it.newRoot
            } else {
                finishedSearch = null
            }
        }

        val currentBaseRoot = baseRoot ?: run {
            menu.finishedSearch(null, null)
            return
        }

        activeSearchJob = menu.diskusage.lifecycleScope.launch {
            try {
                val currentQuery = query
                val newRootResult = withContext(Dispatchers.Default) {
                    val root = menu.masterRoot
                    root?.filter(currentQuery, currentBaseRoot.displayBlockSize) as? FileSystemSuperRoot
                }

                searchFinished(SearchData(currentQuery, newRootResult))
            } catch (ignored: SearchInterruptedException) {
            }
        }
    }

    private fun searchFinished(searchData: SearchData) {
        activeSearchJob = null
        finishedSearch = searchData
        if (query != searchData.query) {
            startSearch()
        }
        menu.finishedSearch(searchData.newRoot, searchData.query)
    }

    fun cancelSearch() {
        activeSearchJob?.cancel()
        activeSearchJob = null
        finishedSearch = null
    }
}
