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

    companion object {
        fun safeKey(serverPath: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(serverPath.toByteArray(Charsets.UTF_8))
                .take(12)
                .joinToString("") { "%02x".format(it) }
            return digest
        }

        fun fileName(serverPath: String, page: Int): String = "${safeKey(serverPath)}_p$page.jpg"

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

    /** The local file for a page, if it exists and is still recorded. */
    suspend fun existing(serverPath: String, page: Int): File? {
        val row = dao.get(serverPath, page) ?: return null
        val file = fileFor(serverPath, page, row.pinned)
        if (file.exists() && file.length() > 0) return file
        dao.delete(serverPath, page) // stale record
        return null
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