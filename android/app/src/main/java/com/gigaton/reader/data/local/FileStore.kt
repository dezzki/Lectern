package com.gigaton.reader.data.local

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Manages downloaded page images in app-private storage. With server-side
 * rendering, "downloading a paper for offline" means caching every page image.
 * File names are derived from a hash of the server path so no traversal or
 * name collision is possible from weird paper names.
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
            return "paper-$digest"
        }
    }

    /** Directory holding a paper's cached page images for [widthPx]. */
    fun pagesDir(serverPath: String, widthPx: Int): File =
        File(downloadsDir, "${safeFileName(serverPath)}_p$widthPx")

    /** Permanent page image used for offline reading. */
    fun pageImageFile(serverPath: String, page: Int, widthPx: Int): File =
        File(pagesDir(serverPath, widthPx), "p$page.png")

    /** Short-lived cache copy (reader uses it when it exists). */
    fun cachePagesDir(serverPath: String, widthPx: Int): File =
        File(cacheDir, "${safeFileName(serverPath)}_p$widthPx")

    fun cachePageFile(serverPath: String, page: Int, widthPx: Int): File =
        File(cachePagesDir(serverPath, widthPx), "p$page.png")

    fun isPaperOffline(serverPath: String, widthPx: Int): Boolean {
        val dir = pagesDir(serverPath, widthPx)
        return dir.exists() && (dir.listFiles()?.isNotEmpty() == true)
    }

    fun deletePages(serverPath: String) {
        downloadPagesDirectories(serverPath).forEach { dir -> dir.deleteRecursively() }
    }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun downloadPagesDirectories(serverPath: String): List<File> =
        downloadsDir.listFiles { f ->
            f.name.startsWith(safeFileName(serverPath) + "_p")
        }?.toList() ?: emptyList()
}