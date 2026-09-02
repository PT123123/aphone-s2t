package com.example.aphones2t.data

import kotlinx.coroutines.flow.Flow

class TranscriptRepository(private val dao: TranscriptDao) {
    val all: Flow<List<TranscriptEntity>> = dao.observeAll()

    suspend fun insert(
        text: String,
        wavPath: String?,
        durationMs: Long,
        modelName: String
    ): Long = dao.insert(
        TranscriptEntity(
            text = text,
            wavPath = wavPath,
            durationMs = durationMs,
            modelName = modelName
        )
    )

    suspend fun delete(t: TranscriptEntity) = dao.delete(t)
    suspend fun deleteById(id: Long) = dao.deleteById(id)
    suspend fun update(t: TranscriptEntity) = dao.update(t)
    suspend fun get(id: Long): TranscriptEntity? = dao.get(id)
}
