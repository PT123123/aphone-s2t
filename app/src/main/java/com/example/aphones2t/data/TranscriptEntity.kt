package com.example.aphones2t.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transcripts")
data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "wav_path") val wavPath: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "duration_ms") val durationMs: Long = 0,
    @ColumnInfo(name = "model_name") val modelName: String = "",
    /** 分段时间轴 JSON（见 [TranscriptSegments]），老数据 / 无分段时为 null。 */
    @ColumnInfo(name = "segments") val segmentsJson: String? = null,
    /**
     * 历史遗留列（v2 引入的「主窗口列表 / 历史记录」二分）。
     * 现在全应用只有一个录音列表，该列不再参与查询，仅保留以兼容旧数据库结构
     * —— Room 会校验实际表结构与实体一致，删列需要重建表，得不偿失。
     *
     * 注意：这里不要写 defaultValue 注解。老库是 `ALTER TABLE ... DEFAULT 0`
     * 加进来的，实体再声明别的默认值会让 Room 的表结构校验判定「迁移失败」。
     */
    @ColumnInfo(name = "show_in_main") val showInMain: Boolean = true
)
