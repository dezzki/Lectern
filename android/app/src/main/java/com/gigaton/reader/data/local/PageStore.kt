package com.gigaton.reader.data.local

import java.io.File
import java.security.MessageDigest

/**
 * Owns every page image on disk.
 *
 * Two classes of file live side by side under app-private storage (not the
 * OS cache directory, which Android may clear at any time):
 *
 *  - **pinned** — explicit downloads. Never evicted.
 *  - **cached** — pages kept from reading. Evicted oldest-first once the cache
 *    budget is exceeded.
 *
 * Room records which pages exist so usage can be reported and eviction can pick
 * the least recently used files without scanning the filesystem.
 */
class PageStore(
    rootDir: File,
    private val dao: PageFileDao,
    private val budgetBytes: () -> Long,
) {
    data class Entry(val paperPath: String, val page: Int, val size: Long)
    data class Stats(val cachedBytes: Long, val pinnedBytes: Long, val budgetBytes: Long)

    private val pinnedDir = File(rootDir, "downloads").apply { mkdirs() }
    private val cacheDir = File(rootDir, "pagecache").apply { mkdirs() }
    private val metaDir = File(rootDir, "meta").apply { mkdirs() }
    private val listingDir = File(rootDir, "listings").apply { mkdirs() }

    companion object {
        fun safeKey(serverPath: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(serverPath.toByteArray(Charsets.UTF_8))
                .take(12)
                .joinToString("") { "%02x".format(it) }
            return digest
        }

        fun fileName(serverPath: String, page: Int): String = "${safeKey(serverPath)}_p$page.jpg"

        /** Pixel size of a local page image, reading only the header. */
        fun imageSize(file: File): Pair<Int, Int>? {
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
            return if (options.outWidth > 0 && options.outHeight > 0) {
                options.outWidth to options.outHeight
            } else {
                null
            }
        }

        /**
         * Which cached entries to delete, oldest first, so the cache fits the
         * budget. Pure so it can be unit tested.
         */
        fun planEviction(
            entriesOldestFirst: List<Entry>,
            totalBytes: Long,
            budgetBytes: Long,
        ): List<Entry> {
            if (budgetBytes <= 0 || totalBytes <= budgetBytes) return emptyList()
            var toFree = totalBytes - budgetBytes
            val victims = mutableListOf<Entry>()
            for (entry in entriesOldestFirst) {
                if (toFree <= 0) break
                victims += entry
                toFree -= entry.size
            }
            return victims
        }
    }

    private fun dirFor(pinned: Boolean) = if (pinned) pinnedDir else cacheDir

    fun fileFor(serverPath: String, page: Int, pinned: Boolean): File =
        File(dirFor(pinned), fileName(serverPath, page))

    /** The local file for a page, if it exists (checking current and older naming). */
    suspend fun existing(serverPath: String, page: Int): File? {
        val row = dao.get(serverPath, page)
        if (row != null) {
            val file = fileFor(serverPath, page, row.pinned)
            if (file.exists() && file.length() > 0) return file
            dao.delete(serverPath, page) // stale record
        }
        return candidateFiles(serverPath, page).firstOrNull { it.exists() && it.length() > 0 }
    }

    /**
     * Every page image present on disk for a paper, ascending. Scans the
     * filesystem as well as the database so pages written by older builds (or
     * whose database rows are missing) are still found.
     */
    suspend fun localPages(serverPath: String): List<Int> {
        val key = safeKey(serverPath)
        val pages = sortedSetOf<Int>()
        for (row in dao.forPaper(serverPath)) {
            if (fileFor(serverPath, row.page, row.pinned).exists()) pages.add(row.page)
        }
        val flat = Regex("^${Regex.escape(key)}_p(\\d+)\\.(jpg|png)$")
        val legacy = Regex("^p(\\d+)\\.(png|jpg)$")
        for (root in listOf(pinnedDir, cacheDir)) {
            root.listFiles()?.forEach { file ->
                if (file.isFile) flat.matchEntire(file.name)?.let { pages.add(it.groupValues[1].toInt()) }
            }
            root.listFiles { f -> f.isDirectory && f.name.startsWith("paper-${key}_p") }?.forEach { dir ->
                dir.listFiles()?.forEach { file ->
                    legacy.matchEntire(file.name)?.let { pages.add(it.groupValues[1].toInt()) }
                }
            }
        }
        return pages.toList()
    }

    /** Candidate file locations for a page, including the pre-1.2 layout. */
    private fun candidateFiles(serverPath: String, page: Int): List<File> {
        val key = safeKey(serverPath)
        val candidates = mutableListOf<File>()
        for (root in listOf(pinnedDir, cacheDir)) {
            candidates += File(root, "${key}_p$page.jpg")
            candidates += File(root, "${key}_p$page.png")
            root.listFiles { f -> f.isDirectory && f.name.startsWith("paper-${key}_p") }?.forEach { dir ->
                candidates += File(dir, "p$page.png")
                candidates += File(dir, "p$page.jpg")
            }
        }
        return candidates
    }

    suspend fun touch(serverPath: String, page: Int) {
        dao.touch(serverPath, page, System.currentTimeMillis())
    }

    /** Writes a page image and records it. Evicts the cache if needed. */
    suspend fun put(serverPath: String, page: Int, bytes: ByteArray, pinned: Boolean): File {
        val file = fileFor(serverPath, page, pinned)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        dao.upsert(
            PageFileEntity(
                paperPath = serverPath,
                page = page,
                fileName = file.name,
                size = bytes.size.toLong(),
                pinned = pinned,
                lastAccess = System.currentTimeMillis(),
            )
        )
        if (!pinned) enforceBudget()
        return file
    }

    suspend fun isPinned(serverPath: String, page: Int): Boolean =
        dao.get(serverPath, page)?.pinned == true

    suspend fun pinnedCount(serverPath: String): Int = dao.pinnedCount(serverPath)

    suspend fun pinnedBytes(serverPath: String): Long = dao.pinnedBytesFor(serverPath)

    /** Deletes every page of a paper (pinned and cached). */
    suspend fun removePaper(serverPath: String) {
        for (row in dao.forPaper(serverPath)) {
            fileFor(serverPath, row.page, row.pinned).delete()
            dao.delete(serverPath, row.page)
        }
    }

    suspend fun clearCache() {
        for (row in dao.cachedByAge()) {
            fileFor(row.paperPath, row.page, false).delete()
        }
        dao.clearCache()
    }

    suspend fun stats(): Stats = Stats(
        cachedBytes = dao.cachedBytes(),
        pinnedBytes = dao.pinnedBytes(),
        budgetBytes = budgetBytes(),
    )

    // ------------------------------------------------------------------ //
    // Small JSON caches: page metadata and folder listings.
    //
    // The reader cannot open a paper without its page count/dimensions, and
    // the library screen needs the last listing, so both are cached so the app
    // works with no network at all.
    // ------------------------------------------------------------------ //

    fun saveMeta(serverPath: String, json: String) {
        runCatching { File(metaDir, "${safeKey(serverPath)}.json").writeText(json) }
    }

    fun loadMeta(serverPath: String): String? {
        val file = File(metaDir, "${safeKey(serverPath)}.json")
        return if (file.exists()) runCatching { file.readText() }.getOrNull() else null
    }

    fun saveListing(folderPath: String, json: String) {
        runCatching { File(listingDir, "${safeKey(folderPath)}.json").writeText(json) }
    }

    fun loadListing(folderPath: String): String? {
        val file = File(listingDir, "${safeKey(folderPath)}.json")
        return if (file.exists()) runCatching { file.readText() }.getOrNull() else null
    }

    private suspend fun enforceBudget() {
        val budget = budgetBytes()
        if (budget <= 0) return
        val total = dao.cachedBytes()
        if (total <= budget) return
        val victims = planEviction(
            dao.cachedByAge().map { Entry(it.paperPath, it.page, it.size) },
            total,
            budget,
        )
        for (victim in victims) {
            fileFor(victim.paperPath, victim.page, false).delete()
            dao.delete(victim.paperPath, victim.page)
        }
    }
}