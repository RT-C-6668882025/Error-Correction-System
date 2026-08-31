package com.ecs.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {

    @Query("SELECT * FROM records ORDER BY created_at DESC")
    fun observeAll(): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records ORDER BY created_at DESC")
    suspend fun all(): List<RecordEntity>

    @Query("SELECT uid FROM records")
    suspend fun allUids(): List<String>

    @Query("SELECT COUNT(*) FROM records")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<RecordEntity>): List<Long>

    @Update
    suspend fun update(row: RecordEntity)

    @Query("DELETE FROM records WHERE uid = :uid")
    suspend fun delete(uid: String)

    /** 批量删。SQLite 的变量上限是 999，分批由调用方控制。 */
    @Query("DELETE FROM records WHERE uid IN (:uids)")
    suspend fun deleteAll(uids: List<String>): Int
}
