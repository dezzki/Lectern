package com.gigaton.reader.data.local

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Manages PDF binaries in app-private storage. Downloaded papers live in
 * `filesDir/downloads`; the reader may also create temporary cache copies.
 * File names are derived from a hash of the server path, so there is no
 * risk of traversal or name collision from weird paper names.
 */
class FileStore(context: Context) {

    private val downloadsDir = File(context.filesDir, "downloads").apply { mkdirs() }
    private val cacheDir = File(context.cacheDir, "reader_cache").apply { mkdirs() }

    companion object {
        fun safeFileName(serverPath: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(serverPath.toByteArray(Charsets.UTF_8))
                .take(12)
                .joinToString("") { "%02x".format(it) }
            return "paper-$digest.pdf"
        }
    }

    /** Permanent file for a downloaded paper (may not exist yet). */
    fun downloadFile(serverPath: String): File = File(downloadsDir, safeFileName(serverPath))

    /** Temporary cache file used when opening a paper the user never downloaded. */
    fun cacheFile(serverPath: String): File = File(cacheDir, safeFileName(serverPath))

    fun isDownloaded(serverPath: String): Boolean = downloadFile(serverPath).exists()

    fun deleteDownload(serverPath: String) {
        downloadFile(serverPath).delete()
    }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }
}