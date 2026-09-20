package com.example.aphones2t.model

import android.content.Context
import android.content.SharedPreferences
import com.example.aphones2t.R
import java.net.URI

/**
 * 下载源解析：GitHub → GitHub 代理镜像 → HuggingFace → hf-mirror。
 *
 * 背景：内置模型都在 GitHub Release 上，国内网络直连经常 403 / 超时。之前失败只能
 * 「重试」，而重试还是打同一个地址，用户就没别的办法。现在每个模型都有一条有序的
 * 候选源链：主源失败自动换下一个，用户点「换镜像重试」也能手动跳下一个。
 *
 * 两条规则：
 *  1. **断点续传不换源**：只要 staging 里已经有 .part 数据，就继续用上次那个源把剩余
 *     字节拉完（换源后 Range 偏移对不上，会把文件写坏）；
 *  2. **换了源就从零下**：主动换源时丢掉旧 .part，避免拼出一个损坏的压缩包。
 */
object MirrorResolver {

    /** GitHub 代理前缀（按可用性排序，前两个最稳）。 */
    private val GITHUB_PROXIES = listOf(
        "https://ghproxy.net/",
        "https://gh-proxy.com/",
        "https://hub.gitmirror.com/"
    )

    /** HuggingFace 官方镜像。 */
    private const val HF_MIRROR_HOST = "hf-mirror.com"

    private const val PREFS = "aphones2t_mirror"
    private const val KEY_PREFIX = "src_"

    /**
     * 某个模型的候选源链（第一项永远是 catalog 里写的原始 URL）。
     * [huggingFaceUrl] 非空时会插到代理之后 —— HF 上通常有同一模型的官方权重。
     */
    fun candidates(originalUrl: String, huggingFaceUrl: String?): List<String> {
        if (originalUrl.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        out += originalUrl

        val host = hostOf(originalUrl)
        when {
            host.endsWith("github.com") || host.endsWith("githubusercontent.com") -> {
                GITHUB_PROXIES.forEach { p -> out += p + originalUrl }
            }
            host == "huggingface.co" || host == "www.huggingface.co" -> {
                out += originalUrl.replace("huggingface.co", HF_MIRROR_HOST)
            }
        }

        if (!huggingFaceUrl.isNullOrBlank()) {
            out += huggingFaceUrl
            if (hostOf(huggingFaceUrl).endsWith("huggingface.co")) {
                out += huggingFaceUrl.replace("huggingface.co", HF_MIRROR_HOST)
            }
        }
        return out.toList()
    }

    /** 源链里挑第 [index] 个（越界时回到主源，保证永远有源可用）。 */
    fun at(urls: List<String>, index: Int): String =
        urls.getOrElse(index.coerceAtLeast(0)) { urls.firstOrNull().orEmpty() }

    /** 这个源的短名字，用于卡片上显示「正在用 镜像 2/4」。 */
    fun label(context: Context, url: String): String = when {
        url.startsWith("https://ghproxy.net/") -> context.getString(R.string.mirror_1)
        url.startsWith("https://gh-proxy.com/") -> context.getString(R.string.mirror_2)
        url.startsWith("https://hub.gitmirror.com/") -> context.getString(R.string.mirror_3)
        url.contains(HF_MIRROR_HOST) -> context.getString(R.string.mirror_hf)
        url.contains("huggingface.co") -> "HuggingFace"
        else -> context.getString(R.string.mirror_primary)
    }

    /**
     * 上次成功的源下标（重试时优先用同一个，避免又踩一次坏源）。按模型 id 记。
     */
    fun preferredIndex(context: Context, id: String): Int =
        prefs(context).getInt(KEY_PREFIX + id, 0)

    fun rememberIndex(context: Context, id: String, index: Int) {
        prefs(context).edit().putInt(KEY_PREFIX + id, index).apply()
    }

    /** 「换镜像重试」：把源指针往后挪一格（循环使用）。 */
    fun nextIndex(context: Context, info: LocalModelInfo): Int {
        val count = candidates(info.archive.url, info.huggingFaceUrl).size.coerceAtLeast(1)
        return (preferredIndex(context, info.id) + 1) % count
    }

    /** 源总数，UI 用来显示「镜像 2/4」。 */
    fun sourceCount(info: LocalModelInfo): Int =
        candidates(info.archive.url, info.huggingFaceUrl).size.coerceAtLeast(1)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun hostOf(url: String): String = try {
        URI(url).host?.lowercase().orEmpty()
    } catch (_: Exception) {
        ""
    }
}
