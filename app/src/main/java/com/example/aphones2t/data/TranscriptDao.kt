package com.example.aphones2t.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptDao {
    @Insert
    suspend fun insert(t: TranscriptEntity): Long

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
