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

// --------------------------------------------------------------------------- //
// Database
// --------------------------------------------------------------------------- //

@Database(
    entities = [
        AnnotationEntity::class,
        LocalProgressEntity::class,
        OutboxEntity::class,
        DownloadEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun annotationDao(): AnnotationDao
    abstract fun progressDao(): ProgressDao
    abstract fun outboxDao(): OutboxDao
    abstract fun downloadDao(): DownloadDao
}