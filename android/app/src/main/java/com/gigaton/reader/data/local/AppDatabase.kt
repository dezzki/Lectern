package com.gigaton.reader.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

// --------------------------------------------------------------------------- //
// Entities
// --------------------------------------------------------------------------- //

/** Mirror of server annotations plus locally-created (unsynced) ones. */
@Entity(tableName = "annotations")
data class AnnotationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "paper_path") val paperPath: String,
    val page: Int,
    val kind: String, // highlight | note | ink | bookmark
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int = 1,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
    // true until the change has been pushed to the server
    val unsynced: Boolean = true,
)

/** Reading progress cache + pending-sync source. */
@Entity(tableName = "progress_local")
data class LocalProgressEntity(
    @PrimaryKey @ColumnInfo(name = "paper_path") val paperPath: String,
    val page: Int,
    @ColumnInfo(name = "position_json") val positionJson: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

/** Durable outbox of operations that still need to reach the server. */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val op: String, // annotation_put | annotation_delete | progress_put
    val body: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
)

/** Metadata about a paper downloaded for offline reading. */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val path: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    val size: Long,
    @ColumnInfo(name = "downloaded_at") val downloadedAt: String,
)

/**
 * One page image on disk. Pinned rows are explicit downloads and are never
 * evicted; unpinned rows are the reading cache and are removed oldest-first
 * when the cache budget is exceeded.
 */
@Entity(tableName = "page_files", primaryKeys = ["paper_path", "page"])
data class PageFileEntity(
    @ColumnInfo(name = "paper_path") val paperPath: String,
    val page: Int,
    @ColumnInfo(name = "file_name") val fileName: String,
    val size: Long,
    val pinned: Boolean,
    @ColumnInfo(name = "last_access") val lastAccess: Long,
)

// --------------------------------------------------------------------------- //
// DAOs
// --------------------------------------------------------------------------- //

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM annotations WHERE paper_path = :paperPath ORDER BY page, created_at")
    fun forPaper(paperPath: String): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotations WHERE id = :id")
    suspend fun byId(id: String): AnnotationEntity?

    @Query("SELECT * FROM annotations WHERE paper_path = :paperPath")
    suspend fun forPaperOnce(paperPath: String): List<AnnotationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AnnotationEntity)

    @Query("UPDATE annotations SET unsynced = 0 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("DELETE FROM annotations WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress_local WHERE paper_path = :paperPath")
    suspend fun forPaper(paperPath: String): LocalProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalProgressEntity)

    @Query("DELETE FROM progress_local WHERE paper_path = :paperPath")
    suspend fun clear(paperPath: String)
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY rowId")
    fun all(): Flow<List<OutboxEntity>>

    @Query("SELECT * FROM outbox ORDER BY rowId")
    suspend fun allOnce(): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox")
    fun count(): Flow<Int>

    @Insert
    suspend fun insert(entity: OutboxEntity)

    @Query("DELETE FROM outbox WHERE rowId = :rowId")
    suspend fun delete(rowId: Long)

    @Query("DELETE FROM outbox WHERE op = 'progress_put' AND body LIKE '%' || :paperPath || '%'")
    suspend fun deletePendingProgress(paperPath: String)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY downloaded_at DESC")
    fun all(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE path = :path")
    suspend fun byPath(path: String): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity)

    @Query("DELETE FROM downloads WHERE path = :path")
    suspend fun delete(path: String)
}

@Dao
interface PageFileDao {
    @Query("SELECT * FROM page_files WHERE paper_path = :path AND page = :page")
    suspend fun get(path: String, page: Int): PageFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PageFileEntity)

    @Query("UPDATE page_files SET last_access = :ts WHERE paper_path = :path AND page = :page")
    suspend fun touch(path: String, page: Int, ts: Long)

    @Query("DELETE FROM page_files WHERE paper_path = :path AND page = :page")
    suspend fun delete(path: String, page: Int)

    @Query("DELETE FROM page_files WHERE paper_path = :path")
    suspend fun deletePaper(path: String)

    @Query("SELECT * FROM page_files WHERE paper_path = :path")
    suspend fun forPaper(path: String): List<PageFileEntity>

    @Query("SELECT * FROM page_files WHERE pinned = 0 ORDER BY last_access ASC")
    suspend fun cachedByAge(): List<PageFileEntity>

    @Query("SELECT COALESCE(SUM(size), 0) FROM page_files WHERE pinned = 0")
    suspend fun cachedBytes(): Long

    @Query("SELECT COALESCE(SUM(size), 0) FROM page_files WHERE pinned = 1")
    suspend fun pinnedBytes(): Long

    @Query("SELECT COALESCE(SUM(size), 0) FROM page_files")
    fun totalBytes(): Flow<Long>

    @Query("SELECT COUNT(*) FROM page_files WHERE paper_path = :path AND pinned = 1")
    suspend fun pinnedCount(path: String): Int

    @Query("SELECT COALESCE(SUM(size), 0) FROM page_files WHERE paper_path = :path AND pinned = 1")
    suspend fun pinnedBytesFor(path: String): Long

    @Query("SELECT COUNT(*) FROM page_files WHERE paper_path = :path AND pinned = 0")
    suspend fun cachedCount(path: String): Int

    @Query("DELETE FROM page_files WHERE pinned = 0")
    suspend fun clearCache()
}

// --------------------------------------------------------------------------- //
// Database
// --------------------------------------------------------------------------- //

@Database(
    entities = [
        AnnotationEntity::class,
        LocalProgressEntity::class,
        OutboxEntity::class,
        DownloadEntity::class,
        PageFileEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun annotationDao(): AnnotationDao
    abstract fun progressDao(): ProgressDao
    abstract fun outboxDao(): OutboxDao
    abstract fun downloadDao(): DownloadDao
    abstract fun pageFileDao(): PageFileDao

    companion object {
        /** Adds the page_files table (v1 -> v2) without dropping existing data. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `page_files` (
                      `paper_path` TEXT NOT NULL,
                      `page` INTEGER NOT NULL,
                      `file_name` TEXT NOT NULL,
                      `size` INTEGER NOT NULL,
                      `pinned` INTEGER NOT NULL,
                      `last_access` INTEGER NOT NULL,
                      PRIMARY KEY(`paper_path`, `page`)
                    )
                    """.trimIndent()
                )
            }
        }
    }
}