package com.example.aphones2t.model

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Describes an on-device streaming ASR model and where to fetch it from.
 *
 * `sha256` is optional: when null we skip the cryptographic check and rely on the
 * final native load-test (SherpaStreamingAsr.isModelValid) as the source of truth.
 * Fill in exact sha256/size once you have the model URL (see the TODO in [builtIn]).
 */
data class LocalModelFile(
    val name: String,
    val sizeBytes: Long = 0,
    val sha256: String? = null
)

data class LocalModelArchive(
    val name: String,
    val url: String,
    val sizeBytes: Long = 0,
    val sha256: String? = null,
    /** Top-level directory name inside the archive (its contents are flattened on extract). */
    val rootDirectory: String = ""
)

data class LocalModelInfo(
    val id: String,
    val version: String,
    val name: String,
    val description: String,
    val minSdk: Int = 24,
    val minRamMb: Int = 0,
    val archive: LocalModelArchive,
    val files: List<LocalModelFile> = emptyList(),
    val huggingFaceUrl: String = "",
    val license: String = "Apache-2.0",
    val isCustom: Boolean = false,
    /** Comma-separated language tags, e.g. "zh,en", "zh,yue,en", "ko", used by the UI filter. */
    val language: String = ""
) {
    val downloadSizeBytes: Long get() = archive.sizeBytes
    val installedSizeBytes: Long get() = files.sumOf { it.sizeBytes }
}

object ModelCatalog {

    // ---- built-in official streaming ASR models (sherpa-onnx, k2-fsa) ----
    //
    // All archive URLs are verified live at https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models
    // sizeBytes = exact tar.bz2 Content-Length (used for progress / resume / completeness check).
    // sha256 left null: final verification falls back to the native load-test (SherpaStreamingAsr.isModelValid).

    private fun official(
        id: String,
        version: String,
        name: String,
        description: String,
        sizeBytes: Long,
        minRamMb: Int = 0,
        huggingFaceUrl: String = "",
        language: String? = null
    ): LocalModelInfo {
        val archiveName = "$id.tar.bz2"
        return LocalModelInfo(
            id = id,
            version = version,
            name = name,
            description = description,
            minSdk = 24,
            minRamMb = minRamMb,
            archive = LocalModelArchive(
                name = archiveName,
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$archiveName",
                sizeBytes = sizeBytes,
                sha256 = null,
                rootDirectory = id
            ),
            files = emptyList(),
            huggingFaceUrl = huggingFaceUrl,
            license = "Apache-2.0",
            language = language ?: inferLanguage(id)
        )
    }

    /** Infers a comma-separated language tag from the official model id. */
    private fun inferLanguage(id: String): String = when {
        id.contains("cantonese", true) || id.contains("trilingual", true) -> "zh,yue,en"
        id.contains("bilingual", true) -> "zh,en"
        id.contains("korean", true) -> "ko"
        id.contains("fr-", true) -> "fr"
        id.contains("bn-", true) || id.contains("bn_vosk", true) -> "bn"
        id.contains("-en", true) || id.contains("en-", true) -> "en"
        else -> "zh"
    }

    val builtIn: List<LocalModelInfo> = listOf(
        // ---- 流式 Paraformer ----
        official(
            id = "sherpa-onnx-streaming-paraformer-bilingual-zh-en",
            version = "2024-09-01",
            name = "Paraformer 中英双语 (流式)",
            description = "sherpa-onnx 流式 Paraformer，中英双语，支持普通话及多种方言，离线实时转写 · 下载约 999MB",
            sizeBytes = 1_047_319_737,
            minRamMb = 2048,
            huggingFaceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en"
        ),
        official(
            id = "sherpa-onnx-streaming-paraformer-trilingual-zh-cantonese-en",
            version = "2024-02-29",
            name = "Paraformer 三语 普通话/粤语/英语 (流式)",
            description = "流式 Paraformer，支持普通话、粤语、英语及方言，同族大模型 · 下载约 999MB",
            sizeBytes = 1_047_671_211,
            minRamMb = 2048,
            huggingFaceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-trilingual-zh-cantonese-en"
        ),

        // ---- 流式 Zipformer-Transducer（2025 新版，文件名即标准命名，可直接加载）----
        official(
            id = "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30",
            version = "2025-06-30",
            name = "Zipformer 中文 (2025, int8)",
            description = "2025 新版流式 Zipformer，中文，encoder int8 ~154MB，精度/体积均衡 · 下载约 126MB",
            sizeBytes = 132_634_597,
            minRamMb = 1024,
            huggingFaceUrl = "https://huggingface.co/yuekai/icefall-asr-multi-zh-hans-zipformer-large"
        ),
        official(
            id = "sherpa-onnx-streaming-zipformer-zh-xlarge-int8-2025-06-30",
            version = "2025-06-30",
            name = "Zipformer 中文 超大 (2025, int8)",
            description = "2025 新版流式 Zipformer XL，中文，精度最高，encoder int8 ~726MB · 下载约 570MB",
            sizeBytes = 597_755_927,
            minRamMb = 3072,
            huggingFaceUrl = "https://huggingface.co/yuekai/icefall-asr-multi-zh-hans-zipformer-xl"
        ),

        // ---- 流式 Zipformer-Transducer（epoch 命名，需代码前缀匹配）----
        official(
            id = "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
            version = "2023-02-20",
            name = "Zipformer 中英双语 (流式)",
            description = "社区贡献经典双语流式 Zipformer，中文+英文 · 下载约 488MB",
            sizeBytes = 511_274_346,
            minRamMb = 2048
        ),
        official(
            id = "sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16",
            version = "2023-02-16",
            name = "Zipformer 中英双语 小 (流式)",
            description = "双语小模型，中文+英文，体积更小更省内存 · 下载约 437MB",
            sizeBytes = 458_187_351,
            minRamMb = 1024
        ),
        official(
            id = "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23",
            version = "2023-02-23",
            name = "Zipformer 中文 微型 (14M)",
            description = "超小模型，适合 Cortex-A7 等低端 CPU，中文 · 下载约 71MB",
            sizeBytes = 74_004_050,
            minRamMb = 256
        ),
        official(
            id = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17",
            version = "2023-02-17",
            name = "Zipformer 英文 微型 (20M)",
            description = "英文小模型，适合低端 CPU · 下载约 122MB",
            sizeBytes = 127_887_156,
            minRamMb = 256
        ),
        official(
            id = "sherpa-onnx-streaming-zipformer-en-2023-06-26",
            version = "2023-06-26",
            name = "Zipformer 英文 (流式, LibriSpeech)",
            description = "英文流式 Zipformer，LibriSpeech 训练 · 下载约 296MB",
            sizeBytes = 310_414_022,
            minRamMb = 2048
        ),
        official(
            id = "sherpa-onnx-streaming-zipformer-en-2023-06-21",
            version = "2023-06-21",
            name = "Zipformer 英文 (流式, Libri+Giga)",
            description = "英文流式 Zipformer，LibriSpeech+GigaSpeech 训练 · 下载约 483MB",
            sizeBytes = 506_956_414,
            minRamMb = 2048
        ),
        official(
            id = "sherpa-onnx-streaming-zipformer-en-2023-02-21",
            version = "2023-02-21",
            name = "Zipformer 英文 (流式, LibriSpeech)",
            description = "英文流式 Zipformer，LibriSpeech 训练 · 下载约 380MB",
            sizeBytes = 397_939_030,
            minRamMb = 2048
        ),
        official(
            id = "sherpa-onnx-streaming-zipformer-korean-2024-06-16",
            version = "2024-06-16",
            name = "Zipformer 韩语 (流式)",
            description = "韩语流式 Zipformer，KsponSpeech 训练 · 下载约 399MB",
            sizeBytes = 418_218_652,
            minRamMb = 2048
        ),
        official(
            id = "sherpa-onnx-streaming-zipformer-multi-zh-hans-2023-12-12",
            version = "2023-12-12",
            name = "Zipformer 中文 (流式, 14k小时)",
            description = "中文流式 Zipformer，14k 小时训练 · 下载约 296MB",
            sizeBytes = 310_380_628,
            minRamMb = 2048
        ),
        official(
            id = "sherpa-onnx-streaming-zipformer-fr-2023-04-14",
            version = "2023-04-14",
            name = "Zipformer 法语 (流式)",
            description = "法语流式 Zipformer，CommonVoice 训练 · 下载约 380MB",
            sizeBytes = 398_444_115,
            minRamMb = 2048
        ),
        official(
            id = "icefall-asr-zipformer-streaming-wenetspeech-20230615",
            version = "2023-06-15",
            name = "Zipformer 中文 (流式, WenetSpeech)",
            description = "中文流式 Zipformer，WenetSpeech 训练，文件在子目录 · 下载约 316MB",
            sizeBytes = 331_870_551,
            minRamMb = 2048
        ),

        // ---- Conformer / LSTM（modelType 需自动识别）----
        official(
            id = "sherpa-onnx-streaming-conformer-zh-2023-05-23",
            version = "2023-05-23",
            name = "Conformer 中文 (流式)",
            description = "中文流式 Conformer-Transducer · 下载约 504MB",
            sizeBytes = 528_814_124,
            minRamMb = 2048
        ),
        official(
            id = "sherpa-onnx-lstm-en-2023-02-17",
            version = "2023-02-17",
            name = "LSTM 英文 (流式)",
            description = "英文流式 LSTM-Transducer · 下载约 366MB",
            sizeBytes = 384_245_724,
            minRamMb = 1024
        ),
        official(
            id = "sherpa-onnx-lstm-zh-2023-02-20",
            version = "2023-02-20",
            name = "LSTM 中文 (流式)",
            description = "中文流式 LSTM-Transducer · 下载约 398MB",
            sizeBytes = 417_007_347,
            minRamMb = 1024
        ),

        // ---- 其他语种 ----
        official(
            id = "sherpa-onnx-streaming-zipformer-bn-vosk-2026-02-09",
            version = "2026-02-09",
            name = "Zipformer 孟加拉语 (流式, vosk)",
            description = "孟加拉语流式 Zipformer，vosk 模型转换 · 下载约 83MB",
            sizeBytes = 87_289_525,
            minRamMb = 512
        ),

        // ---- 流式 Zipformer-CTC（体积最小，需流式 CTC 支持）----
        official(
            id = "sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01",
            version = "2025-04-01",
            name = "Zipformer-CTC 中文 微型 (int8)",
            description = "流式 CTC，官方最小模型，中文 · 下载约 20MB",
            sizeBytes = 21_264_113,
            minRamMb = 512,
            language = "zh"
        ),
        official(
            id = "sherpa-onnx-streaming-zipformer-small-ctc-zh-2025-04-01",
            version = "2025-04-01",
            name = "Zipformer-CTC 中文 微型 (fp32)",
            description = "流式 CTC，中文（fp32 版）· 下载约 83MB",
            sizeBytes = 87_170_593,
            minRamMb = 512,
            language = "zh"
        ),
        official(
            id = "sherpa-onnx-streaming-zipformer-ctc-zh-int8-2025-06-30",
            version = "2025-06-30",
            name = "Zipformer-CTC 中文 (int8)",
            description = "2025 流式 CTC，中文，编码器 int8 · 下载约 122MB",
            sizeBytes = 127_965_713,
            minRamMb = 1024,
            language = "zh"
        )
    )

    fun all(context: Context): List<LocalModelInfo> = builtIn + customModels(context)

    fun findById(context: Context, id: String?): LocalModelInfo? =
        all(context).firstOrNull { it.id == id }

    /**
     * Adds a custom model (persisted at runtime, no recompile needed).
     * Same URL is de-duplicated; a blank name is derived from the file name.
     */
    fun addCustom(context: Context, url: String, name: String? = null): LocalModelInfo {
        val safeUrl = url.trim()
        val archiveName = safeUrl.substringAfterLast('/').ifBlank { "model.tar.bz2" }
        val displayName = name?.trim()?.takeIf { it.isNotEmpty() }
            ?: archiveName.removeSuffix(".tar.bz2").removeSuffix(".zip")
        val id = "custom-" + safeUrl.hashCode().let { if (it < 0) -it else it }
        val info = LocalModelInfo(
            id = id,
            version = "custom",
            name = displayName,
            description = "自定义模型（运行期添加）\n$safeUrl",
            minSdk = 24,
            minRamMb = 0,
            archive = LocalModelArchive(
                name = archiveName,
                url = safeUrl,
                sizeBytes = 0,
                sha256 = null,
                rootDirectory = archiveName.removeSuffix(".tar.bz2").removeSuffix(".zip")
            ),
            files = emptyList(),
            huggingFaceUrl = safeUrl,
            license = "Apache-2.0",
            isCustom = true
        )
        val list = customModels(context).toMutableList()
        list.removeIf { it.id == id }
        list.add(info)
        saveCustom(context, list)
        return info
    }

    /** Removes a custom model from the runtime catalog (does not delete installed files). */
    fun removeCustom(context: Context, id: String) {
        val list = customModels(context).toMutableList()
        list.removeIf { it.id == id }
        saveCustom(context, list)
    }

    // ---- custom model persistence (SharedPreferences JSON) ----

    private const val PREFS = "aphones2t_models"
    private const val KEY_CUSTOM = "custom_models"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun customModels(context: Context): List<LocalModelInfo> {
        val raw = prefs(context).getString(KEY_CUSTOM, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { decode(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Custom models only (persisted, added at runtime without recompiling). */
    fun custom(context: Context): List<LocalModelInfo> = customModels(context)


    private fun saveCustom(context: Context, models: List<LocalModelInfo>) {
        val arr = JSONArray()
        models.forEach { arr.put(encode(it)) }
        prefs(context).edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }

    private fun encode(m: LocalModelInfo) = JSONObject().apply {
        put("id", m.id)
        put("version", m.version)
        put("name", m.name)
        put("description", m.description)
        put("minSdk", m.minSdk)
        put("minRamMb", m.minRamMb)
        put("huggingFaceUrl", m.huggingFaceUrl)
        put("license", m.license)
        put("isCustom", m.isCustom)
        put("language", m.language)
        put(
            "archive", JSONObject().apply {
                put("name", m.archive.name)
                put("url", m.archive.url)
                put("sizeBytes", m.archive.sizeBytes)
                put("sha256", m.archive.sha256)
                put("rootDirectory", m.archive.rootDirectory)
            }
        )
        put("files", JSONArray().apply { m.files.forEach { f -> put(JSONObject().apply {
            put("name", f.name); put("sizeBytes", f.sizeBytes); put("sha256", f.sha256)
        }) } })
    }

    private fun decode(j: JSONObject): LocalModelInfo? = try {
        val a = j.getJSONObject("archive")
        LocalModelInfo(
            id = j.getString("id"),
            version = j.optString("version", "custom"),
            name = j.optString("name", "custom"),
            description = j.optString("description", ""),
            minSdk = j.optInt("minSdk", 24),
            minRamMb = j.optInt("minRamMb", 0),
            archive = LocalModelArchive(
                name = a.getString("name"),
                url = a.getString("url"),
                sizeBytes = a.optLong("sizeBytes", 0),
                sha256 = a.optString("sha256", "").takeIf { it.isNotBlank() },
                rootDirectory = a.optString("rootDirectory", "")
            ),
            files = run {
                val fa = j.optJSONArray("files") ?: JSONArray()
                (0 until fa.length()).map {
                    val f = fa.getJSONObject(it)
                    LocalModelFile(
                        name = f.getString("name"),
                        sizeBytes = f.optLong("sizeBytes", 0),
                        sha256 = f.optString("sha256", "").takeIf { s -> s.isNotBlank() }
                    )
                }
            },
            huggingFaceUrl = j.optString("huggingFaceUrl", ""),
            license = j.optString("license", "Apache-2.0"),
            isCustom = j.optBoolean("isCustom", true),
            language = j.optString("language", "")
        )
    } catch (_: Exception) {
        null
    }
}