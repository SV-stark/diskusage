package com.google.android.diskusage.datasource.fast

import com.google.android.diskusage.utils.AppHelper.appContext
import com.google.android.diskusage.utils.DeviceHelper.isDeviceRooted
import org.jetbrains.annotations.Contract
import timber.log.Timber
import java.io.IOException
import java.io.InputStream

class NativeScannerStream(private val process: Process) :
    InputStream() {
    private val input = process.inputStream

    @Throws(IOException::class)
    override fun read(): Int {
        return input.read()
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray): Int {
        return input.read(buffer)
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, byteOffset: Int, byteCount: Int): Int {
        return input.read(buffer, byteOffset, byteCount)
    }

    @Throws(IOException::class)
    override fun close() {
        input.close()
        try {
            process.waitFor()
        } catch (e: InterruptedException) {
            throw IOException(e.message)
        }
    }

    companion object Factory {
        private const val LIBSCAN = "libscan.so"

        private fun getLibscanPath(): String? {
            return try {
                appContext.applicationInfo?.nativeLibraryDir?.let { "${it}/${LIBSCAN}" }
            } catch (e: Exception) {
                Timber.e(e, "Failed to get native library path")
                null
            }
        }

        @JvmStatic
        @Contract("_, _ -> new")
        @Throws(IOException::class, InterruptedException::class)
        fun create(path: String, rootRequired: Boolean): NativeScannerStream {
            return runScanner(path, rootRequired)
        }

        @Contract("_, _ -> new")
        @Throws(IOException::class, InterruptedException::class)
        private fun runScanner(root: String, rootRequired: Boolean): NativeScannerStream {
            val libscanPath = getLibscanPath()
            if (libscanPath == null) {
                throw IOException("Native library path not available")
            }
            
            val process = if (!(rootRequired && isDeviceRooted())) {
                Runtime.getRuntime().exec(arrayOf(libscanPath, root))
            } else {
                arrayOf("su", "/system/bin/su", "/system/xbin/su").firstNotNullOf { su ->
                    runCatching {
                        Runtime.getRuntime().exec(arrayOf(su))
                    }.getOrNull()
                }.also {
                    it?.outputStream?.use { o ->
                        o.write("$libscanPath $root".toByteArray())
                        o.flush()
                    }
                }
            }
            if (process == null) {
                throw IOException("Failed to start native scanner process")
            }
            return NativeScannerStream(process)
        }
    }
}
