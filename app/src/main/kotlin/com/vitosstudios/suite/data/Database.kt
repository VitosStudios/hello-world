package com.vitosstudios.suite.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * The app index: files the user opened or created. The user's files themselves are never stored
 * here; [workingPath] points to the private working copy the editors read and autosave.
 */
@Entity(tableName = "files")
data class FileEntry(
    @PrimaryKey val id: String,
    /** Storage Access Framework URI of the user's file; null for files kept only on this device. */
    val uri: String?,
    val displayName: String,
    val kind: FileKind,
    val sizeBytes: Long,
    val lastOpenedAt: Long,
    /** Private working copy (autosave target). */
    val workingPath: String,
    /** Copy of the file as it was when opened, kept until the first successful write-back. */
    val backupPath: String?,
    /** True when the working copy has changes that are not yet written to [uri]. */
    val pendingWriteBack: Boolean = false,
    /** Last error from writing back to [uri], shown to the user. */
    val lastError: String? = null,
    val pinned: Boolean = false,
)

@Dao
interface FileDao {
    @Query("SELECT * FROM files ORDER BY pinned DESC, lastOpenedAt DESC")
    fun observeAll(): Flow<List<FileEntry>>

    @Query("SELECT * FROM files WHERE id = :id")
    suspend fun get(id: String): FileEntry?

    @Query("SELECT * FROM files WHERE uri = :uri LIMIT 1")
    suspend fun byUri(uri: String): FileEntry?

    @Query("SELECT * FROM files WHERE pendingWriteBack = 1")
    suspend fun pending(): List<FileEntry>

    @Upsert
    suspend fun upsert(entry: FileEntry)

    @Query("DELETE FROM files WHERE id = :id")
    suspend fun delete(id: String)
}

class Converters {
    @androidx.room.TypeConverter
    fun kindToString(k: FileKind): String = k.name

    @androidx.room.TypeConverter
    fun stringToKind(s: String): FileKind = FileKind.valueOf(s)
}

@Database(entities = [FileEntry::class], version = 1, exportSchema = false)
@androidx.room.TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun files(): FileDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "app-index.db").build()
    }
}
