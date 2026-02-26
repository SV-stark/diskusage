package com.google.android.diskusage.ui

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.FileUriExposedException
import android.os.Handler
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.google.android.diskusage.BuildConfig
import com.google.android.diskusage.R
import com.google.android.diskusage.core.NativeScanner
import com.google.android.diskusage.core.Scanner
import com.google.android.diskusage.datasource.StatFsSource
import com.google.android.diskusage.datasource.fast.LegacyFileImpl
import com.google.android.diskusage.datasource.fast.StatFsSourceImpl
import com.google.android.diskusage.filesystem.Apps2SDLoader
import com.google.android.diskusage.filesystem.BackgroundDelete
import com.google.android.diskusage.filesystem.entity.*
import com.google.android.diskusage.filesystem.mnt.MountPoint
import com.google.android.diskusage.opengl.RendererManager
import splitties.toast.toast
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.*

typealias AfterLoad = (FileSystemSuperRoot, Boolean) -> Unit

class DiskUsage : LoadableActivity() {
    var fileSystemState: FileSystemState? = null

    private var pathToDelete: String? = null
    var menu = DiskUsageMenu(this)
    var rendererManager = RendererManager(this)
    // removedPackage is held in the ViewModel so it survives configuration changes

    internal var afterLoadAction = ArrayList<Runnable>()
    private var isAppResumed = false

    override val key: String
        get() = _key!!
    private var _key: String? = null
    private val memoryClass = MemoryClassDetected()
    lateinit var viewModel: DiskUsageViewModel

    override fun onCreate(icicle: Bundle?) {
        com.google.android.diskusage.utils.ThemeHelper.applyTheme(this)
        super.onCreate(icicle)
        val viewModel = ViewModelProvider(this)[DiskUsageViewModel::class.java]
        this.viewModel = viewModel
        Timber.d("DiskUsage.onCreate()")
        if (com.google.android.diskusage.utils.ThemeHelper.isAmoledTheme(this)) {
            window.decorView.setBackgroundColor(android.graphics.Color.BLACK)
        }
        menu.onCreate(viewModel)
        val i = intent

        _key = i.getStringExtra(KEY_KEY)
        if (_key == null) {
            finish()
            return
        }
        val receivedState = i.getBundleExtra(STATE_KEY)

        val mountPoint = MountPoint.getForKey(this, _key!!)
        if (mountPoint == null) {
            finish()
            return
        }
        Timber.d("DiskUsage.onCreate(), rootPath = %s, receivedState = %s", mountPoint.root, receivedState)
        if (receivedState != null) onRestoreInstanceState(receivedState)
    }

    fun applyPatternNewRoot(newRoot: FileSystemSuperRoot?, searchQuery: String?) {
        fileSystemState?.replaceRootKeepCursor(newRoot!!, searchQuery)
    }

    override fun onResume() {
        super.onResume()
        isAppResumed = true
        rendererManager.onResume()
        val pkg = viewModel.removedPackage
        if (pkg != null) {
            val pkgName = pkg.pkg
            val pm = packageManager
            try {
                pm.getPackageInfo(pkgName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                fileSystemState?.removeInRenderThread(pkg)
            }
            viewModel.removedPackage = null
        }
        loadFiles({ root, isCached ->
            val currentState = FileSystemState(this@DiskUsage, root)
            fileSystemState = currentState
            rendererManager.makeView(currentState, root)
            currentState.startZoomAnimationInRenderThread(null, !isCached, false)
            for (r in afterLoadAction) {
                r.run()
            }
            afterLoadAction.clear()
            if (pathToDelete != null) {
                val path = pathToDelete
                pathToDelete = null
                path?.let { continueDelete(it) }
            }
        }, false)
    }

    override fun onPause() {
        isAppResumed = false
        rendererManager.onPause()
        super.onPause()
        fileSystemState?.let {
            it.killRenderThread()
            val savedState = Bundle()
            it.saveState(savedState)
            afterLoadAction.add(Runnable { it.restoreStateInRenderThread(savedState) })
        }
    }

    override fun onActivityResult(a: Int, result: Int, i: Intent?) {
        super.onActivityResult(a, result, i)
        if (result != RESULT_DELETE_CONFIRMED || i == null) return
        pathToDelete = i.getStringExtra("path")
    }

    override fun onCreateOptionsMenu(m: Menu): Boolean {
        menu.setupToolbarMenu(m)
        return true
    }

    private inner class PackageViewer {
        fun viewPackage(pkg: String) {
            Timber.d("Show package = %s", pkg)
            val viewIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            startActivity(viewIntent)
        }
    }

    private val packageViewer = PackageViewer()

    fun viewPackage(pkg: FileSystemPackage) {
        packageViewer.viewPackage(pkg.pkg)
        viewModel.removedPackage = pkg
    }

    internal fun continueDelete(path: String) {
        val entry = fileSystemState?.masterRoot?.getEntryByName(path, true)
        if (entry != null) {
            BackgroundDelete.startDelete(this, entry)
        } else {
            toast("Oops. Can't find directory to be deleted.")
        }
    }

    fun askForDeletion(entry: FileSystemEntry) {
        val path = entry.path2()
        val fullPath = entry.absolutePath()
        Timber.d("Deletion requested for %s", path)

        when (entry) {
            is FileSystemEntrySmall -> {
                toast("Delete directory instead")
                return
            }
            is FileSystemPackage -> {
                if (entry.children.isNullOrEmpty()) {
                    viewModel.removedPackage = entry
                    BackgroundDelete.startDelete(this, entry)
                    return
                }
            }
            else -> {} // Continue handling
        }

        if (entry.children.isNullOrEmpty()) {
            val fullPathFile = File(fullPath)
            if (fullPathFile.exists() && fullPathFile.isDirectory) {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.ask_to_delete_directory, path))
                    .setPositiveButton(R.string.button_delete) { _, _ -> BackgroundDelete.startDelete(this@DiskUsage, entry) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create().show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.ask_to_delete_file, path))
                    .setPositiveButton(R.string.button_delete) { _, _ -> BackgroundDelete.startDelete(this@DiskUsage, entry) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create().show()
            }
        } else {
            val i = Intent(this, DeleteActivity::class.java)
            i.putExtra(DELETE_PATH_KEY, path)
            i.putExtra(DELETE_ABSOLUTE_PATH_KEY, fullPath)
            i.putExtra(DeleteActivity.NUM_FILES_KEY, entry.getNumFiles())
            i.putExtra(KEY_KEY, _key)
            i.putExtra(DeleteActivity.SIZE_KEY, entry.sizeString())
            startActivityForResult(i, 0)
        }
    }

    fun view(e: FileSystemEntry?) {
        var entry = e ?: return
        var intent = Intent(Intent.ACTION_VIEW)
        intent.addCategory(Intent.CATEGORY_DEFAULT)

        when (entry) {
            is FileSystemEntrySmall -> entry = entry.parent!!
            is FileSystemPackage -> {
                viewPackage(entry)
                return
            }
            else -> {
                val parent = entry.parent
                if (parent is FileSystemPackage) {
                    viewPackage(parent)
                    return
                }
            }
        }

        val path = entry.absolutePath()
        val file = File(path)
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".provider", file)
        } else {
            Uri.fromFile(file)
        }

        if (file.isDirectory) {
            intent = Intent(Intent.ACTION_VIEW)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.setDataAndType(uri, "inode/directory")
            try {
                startActivity(intent)
                return
            } catch (ignored: ActivityNotFoundException) {
            }

            intent = Intent("org.openintents.action.VIEW_DIRECTORY")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.data = uri
            try {
                startActivity(intent)
                return
            } catch (ignored: ActivityNotFoundException) {
            } catch (ignored: FileUriExposedException) {
            }

            intent = Intent("org.openintents.action.PICK_DIRECTORY")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.data = uri
            intent.putExtra("org.openintents.extra.TITLE", getString(R.string.title_in_oi_file_manager))
            intent.putExtra("org.openintents.extra.BUTTON_TEXT", getString(R.string.button_text_in_oi_file_manager))
            try {
                startActivity(intent)
                return
            } catch (ignored: ActivityNotFoundException) {
            } catch (ignored: FileUriExposedException) {
            }

            intent = Intent(Intent.ACTION_VIEW)
            intent.addCategory(Intent.CATEGORY_DEFAULT)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.setDataAndType(uri, "vnd.android.cursor.item/com.metago.filemanager.dir")
            try {
                startActivity(intent)
                return
            } catch (ignored: ActivityNotFoundException) {
            } catch (ignored: FileUriExposedException) {
            }

            toast(R.string.no_viewer_found)
            return
        }

        val fileName = entry.name ?: return
        val dot = fileName.lastIndexOf(".")
        Timber.d("name: $fileName path: $path dot: $dot")
        if (dot != -1) {
            val extension = fileName.substring(dot + 1).lowercase(Locale.getDefault())
            val mimeTypeMap = MimeTypeMap.getSingleton()
            val mime = mimeTypeMap.getMimeTypeFromExtension(extension)
            Timber.d("extension: $extension mime: $mime")

            try {
                intent = Intent(Intent.ACTION_VIEW)
                intent.addCategory(Intent.CATEGORY_DEFAULT)
                if (mime != null) {
                    intent.setDataAndType(uri, mime)
                } else {
                    intent.setDataAndType(uri, "binary/octet-stream")
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(intent)
                return
            } catch (ignored: ActivityNotFoundException) {
                Timber.e("Can't open viewer and crash", ignored)
            } catch (ignored: FileUriExposedException) {
                Timber.e("Can't open viewer and crash", ignored)
            }
        }
        toast(R.string.no_viewer_found)
    }

    fun rescan() {
        loadFiles({ newRoot, isCached ->
            fileSystemState?.startZoomAnimationInRenderThread(newRoot, !isCached, false)
        }, true)
    }

    fun finishOnBack() {
        if (!menu.readyToFinish()) return
        val outState = Bundle()
        onSaveInstanceState(outState)
        val result = Intent()
        result.putExtra(STATE_KEY, outState)
        result.putExtra(KEY_KEY, _key)
        setResult(0, result)
        finish()
    }

    fun setSelectedEntity(position: FileSystemEntry?) {
        menu.update(position)
        if (position != null) {
            title = getString(R.string.title_for_path, position.toTitleString())
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finishOnBack()
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        fileSystemState?.let {
            it.killRenderThread()
            it.saveState(outState)
            menu.onSaveInstanceState(outState)
        }
    }

    override fun onRestoreInstanceState(inState: Bundle) {
        super.onRestoreInstanceState(inState)
        Timber.d("DiskUsage.onRestoreInstanceState(), rootPath = %s", inState.getString(KEY_KEY))
        fileSystemState?.let {
            it.restoreStateInRenderThread(inState)
        } ?: run {
            afterLoadAction.add(Runnable { fileSystemState?.restoreStateInRenderThread(inState) })
        }
        menu.onRestoreInstanceState(inState)
    }


    internal inner class MemoryClassDetected {
        fun maxHeap(): Int {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            return manager.memoryClass * 1024 * 1024
        }
    }

    private fun getMemoryQuota(): Int {
        val totalMem = memoryClass.maxHeap()
        val numMountPoints = MountPoint.getMountPoints(this).size
        return totalMem / (numMountPoints + 1)
    }

    class FileSystemStats(mountPoint: MountPoint) {
        val blockSize: Long
        val freeBlocks: Long
        val busyBlocks: Long
        val totalBlocks: Long

        init {
            var stats: StatFsSource? = null
            try {
                stats = StatFsSourceImpl(mountPoint.root)
            } catch (e: IllegalArgumentException) {
                Timber.e(e, "Failed to get filesystem stats for ${mountPoint.root}")
            }
            if (stats != null) {
                blockSize = stats.blockSizeLong
                freeBlocks = stats.availableBlocksLong
                totalBlocks = stats.blockCountLong
                busyBlocks = totalBlocks - freeBlocks
            } else {
                totalBlocks = 0
                busyBlocks = 0
                freeBlocks = 0
                blockSize = 512
            }
        }

        fun formatUsageInfo(): String {
            if (totalBlocks == 0L) return "Used <no information>"
            return String.format(
                "Used %s of %s",
                FileSystemEntry.calcSizeString((busyBlocks * blockSize).toFloat()),
                FileSystemEntry.calcSizeString((totalBlocks * blockSize).toFloat())
            )
        }
    }

    private fun startProgressUpdater(
        lastFileProvider: () -> FileSystemEntry?,
        posProvider: () -> Long,
        stats: FileSystemStats
    ): kotlinx.coroutines.Job {
        return lifecycleScope.launch {
            var file: FileSystemEntry? = null
            while (isActive) {
                val dialog = persistentState.loading
                if (dialog != null) {
                    dialog.max = stats.busyBlocks
                    val lastFile = lastFileProvider()

                    if (lastFile !== file) {
                        dialog.setProgress(posProvider(), lastFile)
                    }
                    file = lastFile
                }
                delay(50)
            }
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    override fun scan(): FileSystemSuperRoot {
        val mountPoint = MountPoint.getForKey(this, key)!!
        val stats = FileSystemStats(mountPoint)
        val heap = getMemoryQuota()

        var rootElement: FileSystemEntry
        try {
            val scanner = NativeScanner(this, stats.blockSize, stats.busyBlocks, heap)
            val progressJob = startProgressUpdater(
                { scanner.lastCreatedFile() },
                { scanner.pos() },
                stats
            )
            rootElement = scanner.scan(mountPoint)!!
            progressJob.cancel()
        } catch (e: RuntimeException) {
            Timber.e(e, "NativeScanner failed with RuntimeException, falling back to Java Scanner")
            rootElement = scanWithJavaScanner(mountPoint, stats)
        } catch (e: IOException) {
            Timber.e(e, "NativeScanner failed with IOException, falling back to Java Scanner")
            rootElement = scanWithJavaScanner(mountPoint, stats)
        }

        return finishScan(rootElement, mountPoint, stats)
    }

    private fun scanWithJavaScanner(mountPoint: MountPoint, stats: FileSystemStats): FileSystemEntry {
        val heap = getMemoryQuota()
        val scanner = Scanner(20, stats.blockSize, stats.busyBlocks, heap)
        val progressJob = startProgressUpdater(
            { scanner.lastCreatedFile() },
            { scanner.pos() },
            stats
        )
        val rootElement = scanner.scan(LegacyFileImpl.createRoot(mountPoint.root))!!
        progressJob.cancel()
        return rootElement
    }

    private fun finishScan(rootElement: FileSystemEntry, mountPoint: MountPoint, stats: FileSystemStats): FileSystemSuperRoot {
        var entries = ArrayList<FileSystemEntry>()

        rootElement.children?.let { children ->
            entries.addAll(children.toList())
        }

        if (mountPoint.hasApps()) {
            val media = FileSystemRoot.makeNode(getString(R.string.graph_media), mountPoint.root, false)
                .setChildren(entries.toTypedArray(), stats.blockSize) as FileSystemRoot
            entries = ArrayList()
            entries.add(media)

            val appList = loadApps2SD(stats.blockSize)
            if (appList != null) {
                moveAppData(appList, media, stats.blockSize)
                val apps = FileSystemEntry.makeNode(null, getString(R.string.graph_apps))
                    .setChildren(appList, stats.blockSize)
                entries.add(apps)
            }
        }

        var visibleBlocks: Long = 0
        for (e in entries) {
            visibleBlocks += e.sizeInBlocks
        }

        val systemBlocks = stats.totalBlocks - stats.freeBlocks - visibleBlocks
        entries.sortWith(FileSystemEntry.COMPARE)
        if (systemBlocks > 0) {
            entries.add(FileSystemSystemSpace(getString(R.string.graph_system_data), systemBlocks * stats.blockSize, stats.blockSize))
            entries.add(FileSystemFreeSpace(getString(R.string.graph_free_space), stats.freeBlocks * stats.blockSize, stats.blockSize))
        } else {
            val freeBlocks = stats.freeBlocks + systemBlocks
            if (freeBlocks > 0) {
                entries.add(FileSystemFreeSpace(getString(R.string.graph_free_space), freeBlocks * stats.blockSize, stats.blockSize))
            }
        }

        val finalRoot = FileSystemRoot.makeNode(mountPoint.title, mountPoint.root, false)
            .setChildren(entries.toTypedArray(), stats.blockSize)
        val newRoot = FileSystemSuperRoot(stats.blockSize)
        newRoot.setChildren(arrayOf(finalRoot), stats.blockSize)
        return newRoot
    }

    protected fun loadApps2SD(blockSize: Long): Array<FileSystemEntry>? {
        return try {
            Apps2SDLoader(this).load(blockSize)
        } catch (t: Throwable) {
            Timber.e(t, "loadApps2SD: Problem loading apps2sd info")
            null
        }
    }

    internal fun moveIntoPackage(
        pkg: FileSystemPackage,
        root: FileSystemRoot,
        path: String, newName: String,
        type: FileSystemPackage.ChildType,
        blockSize: Long
    ) {
        val e = root.getByAbsolutePath(path)
        if (e != null) {
            e.remove(blockSize)
            val newRoot = FileSystemRoot.makeNode(newName, path, true)
            newRoot.setChildren(e.children, blockSize)
            pkg.addPublicChild(newRoot, type, blockSize)
        }
    }

    internal fun moveAppData(apps: Array<FileSystemEntry>, media: FileSystemRoot, blockSize: Long) {
        val diskusage = "com.google.android.diskusage"
        val diskusageRegex = diskusage.toRegex()
        for (a in apps) {
            val app = a as FileSystemPackage
            try {
                val cacheDir = cacheDir.canonicalPath.replace(diskusageRegex, app.pkg)
                moveIntoPackage(app, media, cacheDir, "Cache", FileSystemPackage.ChildType.CACHE, blockSize)
            } catch (e: IOException) {
                Timber.w(e, "Failed to get cache dir for ${app.pkg}")
            }
        }
        for (a in apps) {
            val app = a as FileSystemPackage
            try {
                val dir = codeCacheDir.canonicalPath.replace(diskusageRegex, app.pkg)
                moveIntoPackage(app, media, dir, "CodeCache", FileSystemPackage.ChildType.CACHE, blockSize)
            } catch (e: IOException) {
                Timber.w(e, "Failed to get code cache dir for ${app.pkg}")
            }
        }
        for (a in apps) {
            val app = a as FileSystemPackage
            try {
                val dir = externalCacheDir?.canonicalPath?.replace(diskusageRegex, app.pkg)
                if (dir != null) {
                    moveIntoPackage(app, media, dir, "ExternalCache", FileSystemPackage.ChildType.CACHE, blockSize)
                }
            } catch (e: IOException) {
                Timber.w(e, "Failed to get external cache dir for ${app.pkg}")
            }
        }
        for (a in apps) {
            val app = a as FileSystemPackage
            try {
                val dir = dataDir.canonicalPath.replace(diskusageRegex, app.pkg)
                moveIntoPackage(app, media, dir, "Data", FileSystemPackage.ChildType.DATA, blockSize)
            } catch (e: IOException) {
                Timber.w(e, "Failed to get data dir for ${app.pkg}")
            }
        }
        for (a in apps) {
            val app = a as FileSystemPackage
            try {
                val dir = filesDir.canonicalPath.replace(diskusageRegex, app.pkg)
                moveIntoPackage(app, media, dir, "InternalFiles", FileSystemPackage.ChildType.DATA, blockSize)
            } catch (e: IOException) {
                Timber.w(e, "Failed to get files dir for ${app.pkg}")
            }
        }

        for (a in apps) {
            val app = a as FileSystemPackage
            try {
                val dir = getExternalFilesDir(null)?.canonicalPath?.replace(diskusageRegex, app.pkg)
                if (dir != null) {
                    moveIntoPackage(app, media, dir, "Files", FileSystemPackage.ChildType.DATA, blockSize)
                }
            } catch (e: IOException) {
                Timber.w(e, "Failed to get external files dir for ${app.pkg}")
            }
        }
        for (a in apps) {
            val app = a as FileSystemPackage
            try {
                for (mediaDir in externalMediaDirs) {
                    val dir = mediaDir.canonicalPath.replace(diskusageRegex, app.pkg)
                    moveIntoPackage(app, media, dir, "MediaFiles", FileSystemPackage.ChildType.DATA, blockSize)
                }
            } catch (e: IOException) {
                Timber.w(e, "Failed to get media dir for ${app.pkg}")
            }
        }
        for (a in apps) {
            val app = a as FileSystemPackage
            try {
                for (mediaDir in obbDirs) {
                    val dir = mediaDir.canonicalPath.replace(diskusageRegex, app.pkg)
                    moveIntoPackage(app, media, dir, "Obb", FileSystemPackage.ChildType.CODE, blockSize)
                }
            } catch (e: IOException) {
                Timber.w(e, "Failed to get obb dir for ${app.pkg}")
            }
        }

        for (a in apps) {
            val app = a as FileSystemPackage
            app.applyFilter(blockSize)
        }
        Arrays.sort(apps, FileSystemEntry.COMPARE)
    }

    fun searchRequest() {
        menu.searchRequest()
    }

    companion object {
        const val RESULT_DELETE_CONFIRMED = 10
        const val RESULT_DELETE_CANCELED = 11

        const val STATE_KEY = "state"
        const val KEY_KEY = "key"

        const val DELETE_PATH_KEY = "path"
        const val DELETE_ABSOLUTE_PATH_KEY = "absolute_path"
    }

    interface ProgressGenerator {
        fun lastCreatedFile(): FileSystemEntry?
        fun pos(): Long
    }
}
