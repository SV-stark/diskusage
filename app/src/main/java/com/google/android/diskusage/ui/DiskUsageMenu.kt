package com.google.android.diskusage.ui

import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Html
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.SearchView
import androidx.core.view.forEach
import androidx.lifecycle.lifecycleScope
import com.google.android.diskusage.R
import com.google.android.diskusage.datasource.SearchManager
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.entity.FileSystemSpecial
import com.google.android.diskusage.filesystem.entity.FileSystemSuperRoot
import com.google.android.diskusage.filesystem.mnt.MountPoint
import com.google.android.diskusage.utils.ThemeHelper
import com.google.android.diskusage.utils.item
import kotlinx.coroutines.launch
import splitties.resources.styledColor
import timber.log.Timber

class DiskUsageMenu(val diskusage: DiskUsage) {
    var masterRoot: FileSystemSuperRoot? = null
    private var searchPattern: String? = null
    private val searchManager by lazy { SearchManager(this) }
    private var selectedEntity: FileSystemEntry? = null
    private var searchView: SearchView? = null
    private var origSearchBackground: Drawable? = null
    private lateinit var viewModel: DiskUsageViewModel

    fun onCreate(viewModel: DiskUsageViewModel) {
        this.viewModel = viewModel
//        val actionBar = checkNotNull(diskusage.actionBar)
//        actionBar.setDisplayHomeAsUpEnabled(true)
    }

    fun readyToFinish(): Boolean {
        return true
    }

    fun searchRequest() {
    }

    private fun setupSearchMenuItem(menu: Menu): MenuItem {
        val iconTint = diskusage.styledColor(android.R.attr.colorControlNormal)
        return menu.item(
            R.string.button_search,
            android.R.drawable.ic_search_category_default,
            iconTint,
            true,
        ).apply {
            actionView = SearchView(diskusage).also {
                searchView = it
                origSearchBackground = it.background
                if (searchPattern != null) {
                    it.isIconified = false
                    it.setQuery(searchPattern, false)
                }
                it.setOnCloseListener {
                    Timber.d("Search process closed")
                    searchPattern = null
                    diskusage.applyPatternNewRoot(masterRoot, null)
                    false
                }
                it.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                    override fun onQueryTextSubmit(query: String): Boolean {
                        onQueryTextChange(query)
                        return false
                    }

                    override fun onQueryTextChange(newText: String): Boolean {
                        Timber.d("Search query changed to: %s", newText)
                        searchPattern = newText
                        applyPattern(searchPattern)
                        return true
                    }
                })
            }
        }
    }

    fun onSaveInstanceState(outState: Bundle) {
        outState.putString("search", searchPattern)
    }

    fun onRestoreInstanceState(inState: Bundle) {
        searchPattern = inState.getString("search")
    }

    fun wrapAndSetContentView(view: View?, newRoot: FileSystemSuperRoot?) {
        masterRoot = newRoot
        updateMenu()
        diskusage.setContentView(view)
        diskusage.invalidateOptionsMenu()
    }

    fun applyPattern(searchQuery: String?) {
        if (searchQuery == null || masterRoot == null) return

        if (searchQuery.isEmpty()) {
            searchManager.cancelSearch()
            finishedSearch(masterRoot, searchQuery)
        } else {
            searchManager.search(searchQuery)
        }
    }

    fun finishedSearch(newRoot: FileSystemSuperRoot?, searchQuery: String?): Boolean {
        return if (newRoot != null) {
            searchView?.background = origSearchBackground
            diskusage.applyPatternNewRoot(newRoot, searchQuery)
            true
        } else {
            searchView?.setBackgroundColor(Color.parseColor("#FFDDDD"))
            diskusage.applyPatternNewRoot(masterRoot, searchQuery)
            false
        }
    }

    fun update(position: FileSystemEntry?) {
        this.selectedEntity = position
        updateMenu()
    }

    fun setupToolbarMenu(menu: Menu) {
        setupSearchMenuItem(menu)

        menu.item(R.string.button_show, showAsAction = true) {
            if (selectedEntity != null) {
                diskusage.view(selectedEntity)
            }
        }.apply {
            diskusage.lifecycleScope.launch {
                viewModel.showButton.collect { isVisible = it }
            }
        }

        menu.item(R.string.button_rescan) {
            diskusage.rescan()
        }.apply {
            diskusage.lifecycleScope.launch {
                viewModel.rescanButton.collect { isVisible = it }
            }
        }

        menu.item(R.string.button_delete) {
            diskusage.askForDeletion(selectedEntity!!)
        }.apply {
            diskusage.lifecycleScope.launch {
                viewModel.deleteButton.collect { isVisible = it }
            }
        }

        menu.item(R.string.rederer) {
            diskusage.rendererManager.switchRenderer(masterRoot)
        }.apply {
            diskusage.lifecycleScope.launch {
                viewModel.rendererButtonTitle.collect { title = it }
            }
        }

        diskusage.lifecycleScope.launch {
            viewModel.toolbarActionButtonVisible.collect {
                menu.forEach { item -> item.isVisible = it }
            }
        }

        menu.forEach { it.isVisible = false }

        menu.item("Theme") {
            val themes = arrayOf(ThemeHelper.THEME_SYSTEM, ThemeHelper.THEME_LIGHT, ThemeHelper.THEME_DARK, ThemeHelper.THEME_AMOLED)
            val prefs = diskusage.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            val currentTheme = prefs.getString(ThemeHelper.PREF_THEME, ThemeHelper.THEME_SYSTEM)
            val checkedItem = themes.indexOf(currentTheme).takeIf { it >= 0 } ?: 0

            AlertDialog.Builder(diskusage)
                .setTitle("Select Theme")
                .setSingleChoiceItems(themes, checkedItem) { dialog, which ->
                    prefs.edit().putString(ThemeHelper.PREF_THEME, themes[which]).apply()
                    ThemeHelper.applyTheme(diskusage)
                    diskusage.recreate()
                    dialog.dismiss()
                }
                .show()
        }

        menu.item(R.string.action_about) {
            val tv = android.widget.TextView(diskusage)
            tv.setPadding(32, 32, 32, 32)
            val htmlText = diskusage.getString(
                R.string.about_view_source_code,
                "<b><a href=\"https://github.com/IvanVolosyuk/diskusage\">GitHub</a></b>",
            )
            tv.text = Html.fromHtml(htmlText, Html.FROM_HTML_MODE_LEGACY)
            tv.movementMethod = android.text.method.LinkMovementMethod.getInstance()
            var version = ""
            try {
                version = diskusage.packageManager.getPackageInfo(diskusage.packageName, PackageManager.GET_META_DATA).versionName
            } catch (e: PackageManager.NameNotFoundException) {
                Timber.e(e, "Package '\${diskusage.packageName}' not found")
            }

            AlertDialog.Builder(diskusage)
                .setTitle("DiskUsage \$version")
                .setView(tv)
                .show()
        }
        updateMenu()
    }

    private fun updateMenu() {
        val fileSystemState = diskusage.fileSystemState ?: run {
            viewModel.hideToolBarActionButton()
            return
        }

        if (fileSystemState.sdcardIsEmpty()) {
            viewModel.hideToolBarActionButton()
            viewModel.enableRescanButton()
        }

        viewModel.showToolbarActionButton()

        val titleRes = if (fileSystemState.isGPU) R.string.software_renderer else R.string.hardware_renderer
        viewModel.setRendererButtonTitle(diskusage.getString(titleRes))

        val isFirstRoot = selectedEntity === fileSystemState.masterRoot.children?.getOrNull(0)
        val isSpecial = selectedEntity is FileSystemSpecial
        val view = !(isFirstRoot || isSpecial)

        if (view) {
            viewModel.enableRescanButton()
        } else {
            viewModel.disableShowButton()
        }

        val fileOrNotSearching = searchPattern == null || selectedEntity?.children == null
        val mountPoint = MountPoint.getForKey(diskusage, diskusage.key)
        if (view && selectedEntity?.isDeletable() == true && fileOrNotSearching && mountPoint?.isDeleteSupported == true) {
            viewModel.enableDeleteButton()
        } else {
            viewModel.disableDeleteButton()
        }
    }
}
