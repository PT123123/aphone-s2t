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
    val isCustom: Boolean = false
) {
    val downloadSizeBytes: Long get() = archive.sizeBytes
    val installedSizeBytes: Long get() = files.sumOf { it.sizeBytes }
}

object ModelCatalog {

    /**
     * Default model: sherpa-onnx streaming Paraformer, bilingual zh + en.
     *
     * TODO(ted): when you find the model address, paste the final URL here and,
     * if you have them, the exact archive size + per-file sha256 for strict
     * verification. Left null = verification falls back to the native load-test.
     */
    val builtIn: List<LocalModelInfo> = listOf(
        LocalModelInfo(
            id = "paraformer-bilingual-zh-en",
            version = "2024-09-01",
            name = "Paraformer 中英双语 (流式)",
            description = "sherpa-onnx 流式 Paraformer，中英双语，离线实时转写",
            minSdk = 24,
            minRamMb = 2048,
            archive = LocalModelArchive(
                name = "sherpa-onnx-streaming-paraformer-bilingual-zh-en.tar.bz2",
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                    "sherpa-onnx-streaming-paraformer-bilingual-zh-en.tar.bz2",
                sizeBytes = 0,
                sha256 = null,
                rootDirectory = "sherpa-onnx-streaming-paraformer-bilingual-zh-en"
            ),
            files = emptyList(),
            huggingFaceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en",
            license = "Apache-2.0"
        )
    )

    fun all(context: Context): List<LocalModelInfo> = builtIn + customModels(context)

    fun findById(context: Context, id: String?): LocalModelInfo? =
        all(context).firstOrNull { it.id == id }

    fun addCustom(context: Context, url: String, displayName: String): LocalModelInfo {
        val id = "custom-" + url.hashCode().toString().replace("-", "n")
        val name = displayName.ifBlank { url.substringAfterLast('/').substringBefore('.') }
        val info = LocalModelInfo(
            id = id,
            version = "custom",
            name = name,
            description = url,
            archive = LocalModelArchive(
                name = url.substringAfterLast('/').ifBlank { "model.tar.bz2" },
                url = url,
                sizeBytes = 0,
                sha256 = null
            ),
            huggingFaceUrl = url,
            isCustom = true
        )
        val list = customModels(context).toMutableList()
        list.removeIf { it.id == id }
        list.add(info)
        saveCustom(context, list)
        return info
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
            isCustom = j.optBoolean("isCustom", true)
        )
    } catch (_: Exception) {
        null
    }
}
