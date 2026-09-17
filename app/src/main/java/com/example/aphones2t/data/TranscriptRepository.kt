package com.example.aphones2t.data

import kotlinx.coroutines.flow.Flow

class TranscriptRepository(private val dao: TranscriptDao) {

    /** 录音 / 导入 / 离线转写统一列表。 */
    val all: Flow<List<TranscriptEntity>> = dao.observeAll()

    suspend fun insert(
        text: String,
        wavPath: String?,
        durationMs: Long,
        modelName: String,
        segmentsJson: String? = null
    ): Long = dao.insert(
        TranscriptEntity(
            text = text,
            wavPath = wavPath,
            durationMs = durationMs,
            modelName = modelName,
            segmentsJson = segmentsJson
        )
    )

    suspend fun delete(t: TranscriptEntity) = dao.delete(t)
    suspend fun deleteById(id: Long) = dao.deleteById(id)
    suspend fun update(t: TranscriptEntity) = dao.update(t)
    suspend fun get(id: Long): TranscriptEntity? = dao.get(id)
}
