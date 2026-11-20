package app.filmmaking.studio.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "studio_records", primaryKeys = ["collection", "id"],
    indices = [Index(value = ["collection", "updatedAt"])])
data class StudioRecord(
    val collection: String,
    val id: String,
    val title: String,
    val payload: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val baseVersion: Long? = null,
    val baseHash: String? = null,
    val dirty: Boolean = true,
    val deletedAt: Long? = null,
    val deviceId: String = ""
)

@Dao
interface StudioDao {
    @Query("SELECT * FROM studio_records WHERE collection = :collection AND deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observe(collection: String): Flow<List<StudioRecord>>

    @Query("SELECT * FROM studio_records WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<StudioRecord>>

    @Query("SELECT * FROM studio_records ORDER BY collection, updatedAt")
    suspend fun allRecords(): List<StudioRecord>

    @Query("SELECT * FROM studio_records WHERE collection = :collection AND id = :id LIMIT 1")
    suspend fun find(collection: String, id: String): StudioRecord?

    @Query("SELECT * FROM studio_records WHERE collection = :collection AND deletedAt IS NULL AND json_extract(payload, '$.projectId') = :projectId")
    suspend fun listByProject(collection: String, projectId: String): List<StudioRecord>

    @Query("SELECT * FROM studio_records WHERE dirty = 1 ORDER BY updatedAt")
    suspend fun pendingSync(): List<StudioRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: StudioRecord)

    @Query("UPDATE studio_records SET deletedAt = :at, updatedAt = :at, dirty = 1 WHERE collection = :collection AND id = :id")
    suspend fun tombstone(collection: String, id: String, at: Long = System.currentTimeMillis())
}

@Database(entities = [StudioRecord::class], version = 1, exportSchema = false)
abstract class StudioDatabase : RoomDatabase() {
    abstract fun dao(): StudioDao
    companion object {
        @Volatile private var instance: StudioDatabase? = null
        fun get(context: Context): StudioDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, StudioDatabase::class.java, "studio.db").build().also { instance = it }
        }
    }
}
