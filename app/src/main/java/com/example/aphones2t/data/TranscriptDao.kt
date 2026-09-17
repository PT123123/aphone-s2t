package com.example.aphones2t.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptDao {
    @Insert
    suspend fun insert(t: TranscriptEntity): Long

    @Update
    suspend fun update(t: TranscriptEntity)

    /** 所有录音（实时录音 + 导入 + 离线转写）统一按时间倒序，全应用只有一个列表。 */
    @Query("SELECT * FROM transcripts ORDER BY created_at DESC")
    fun observeAll(): Flow<List<TranscriptEntity>>

    @Query("SELECT * FROM transcripts ORDER BY created_at DESC")
    suspend fun getAll(): List<TranscriptEntity>

    @Query("SELECT * FROM transcripts WHERE id = :id")
    suspend fun get(id: Long): TranscriptEntity?

    @Delete
    suspend fun delete(t: TranscriptEntity)

    @Query("DELETE FROM transcripts WHERE id = :id")
    suspend fun deleteById(id: Long)
}
