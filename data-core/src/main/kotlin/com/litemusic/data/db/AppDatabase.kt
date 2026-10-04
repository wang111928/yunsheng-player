package com.litemusic.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "playback_snapshot")
data class PlaybackEntity(
    @PrimaryKey val id: Int = 0,
    val snapshotJson: String,
    val updatedAt: Long,
)

@Entity(tableName = "local_meta")
data class LocalMetaEntity(
    @PrimaryKey val mediaId: Long,
    val editedTitle: String?,
    val editedArtist: String?,
    val editedAlbum: String?,
    val updatedAt: Long,
)

@Entity(tableName = "signin_history")
data class SigninHistoryEntity(
    @PrimaryKey val date: String, // yyyy-MM-dd
    val mobileCode: Int,
    val pcCode: Int,
    val points: Int,
    val ts: Long,
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyword: String,
    val ts: Long,
)

@Entity(tableName = "blocked_keywords")
data class BlockedKeywordEntity(
    @PrimaryKey val keyword: String,
    val ts: Long,
)

@Entity(tableName = "cache_index")
data class CacheIndexEntity(
    @PrimaryKey val key: String,
    val size: Long,
    val ts: Long,
)

@Dao
interface PlaybackDao {
    @Query("SELECT * FROM playback_snapshot WHERE id = 0")
    fun observe(): Flow<PlaybackEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: PlaybackEntity)
}

@Dao
interface LocalMetaDao {
    @Query("SELECT * FROM local_meta WHERE mediaId = :mediaId")
    suspend fun get(mediaId: Long): LocalMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalMetaEntity)
}

@Dao
interface SigninHistoryDao {
    @Query("SELECT * FROM signin_history ORDER BY date DESC LIMIT :limit")
    fun recent(limit: Int = 30): Flow<List<SigninHistoryEntity>>

    @Query("SELECT * FROM signin_history WHERE date = :date")
    suspend fun byDate(date: String): SigninHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SigninHistoryEntity)
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY ts DESC LIMIT :limit")
    suspend fun recent(limit: Int = 10): List<SearchHistoryEntity>

    @Insert
    suspend fun insert(entity: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE keyword = :keyword")
    suspend fun delete(keyword: String)
}

@Dao
interface BlockedKeywordDao {
    @Query("SELECT * FROM blocked_keywords ORDER BY ts DESC")
    fun observe(): Flow<List<BlockedKeywordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BlockedKeywordEntity)

    @Query("DELETE FROM blocked_keywords WHERE keyword = :keyword")
    suspend fun delete(keyword: String)
}

@Dao
interface CacheIndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CacheIndexEntity)

    @Query("DELETE FROM cache_index WHERE key = :key")
    suspend fun delete(key: String)

    @Query("SELECT * FROM cache_index")
    fun all(): Flow<List<CacheIndexEntity>>
}

@Database(
    entities = [
        PlaybackEntity::class,
        LocalMetaEntity::class,
        SigninHistoryEntity::class,
        SearchHistoryEntity::class,
        BlockedKeywordEntity::class,
        CacheIndexEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playbackDao(): PlaybackDao
    abstract fun localMetaDao(): LocalMetaDao
    abstract fun signinHistoryDao(): SigninHistoryDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun blockedKeywordDao(): BlockedKeywordDao
    abstract fun cacheIndexDao(): CacheIndexDao
}
