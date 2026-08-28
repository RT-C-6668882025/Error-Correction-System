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

    @Query("SELECT * FROM records WHERE uid = :uid")
    suspend fun byUid(uid: String): RecordEntity?

    @Query("SELECT uid FROM records")
    suspend fun allUids(): List<String>

    @Query("SELECT * FROM records WHERE status = :status ORDER BY created_at DESC")
    fun observeByStatus(status: String): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE verified = '冲突' ORDER BY created_at DESC")
    fun observeConflicts(): Flow<List<RecordEntity>>

    @Query("SELECT COUNT(*) FROM records")
    suspend fun count(): Int

    @Query("SELECT * FROM records WHERE tree_version IS NOT NULL AND tree_version != :version")
    suspend fun staleTreeVersion(version: String): List<RecordEntity>

    @Query("SELECT * FROM records WHERE kaodian = :kaodian")
    suspend fun byKaodian(kaodian: String): List<RecordEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<RecordEntity>): List<Long>

    @Update
    suspend fun update(row: RecordEntity)

    @Update
    suspend fun updateAll(rows: List<RecordEntity>)

    @Query("UPDATE records SET kaodian = :to, form_rule = :formRule, tree_version = :version WHERE kaodian = :from")
    suspend fun remapKaodian(from: String, to: String, formRule: String, version: String)

    @Query("UPDATE records SET status = :status, next_check = :nextCheck WHERE uid = :uid")
    suspend fun setStatus(uid: String, status: String, nextCheck: Long?)

    @Query("UPDATE records SET verified = :verified WHERE uid = :uid")
    suspend fun setVerified(uid: String, verified: String)

    @Query("DELETE FROM records WHERE uid = :uid")
    suspend fun delete(uid: String)
}
