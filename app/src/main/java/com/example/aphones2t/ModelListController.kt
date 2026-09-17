package com.example.aphones2t

import android.content.ClipData
import android.content.ClipboardManager
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.aphones2t.databinding.ItemModelBinding
import com.example.aphones2t.model.DownloadDiagnostics
import com.example.aphones2t.model.ModelInstallStatus
import com.example.aphones2t.model.ModelManager
import com.example.aphones2t.model.ModelState
import com.example.aphones2t.model.ModelStatusFilter
import com.example.aphones2t.utils.FormatUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 模型管理列表（状态筛选 + 语言筛选 + 排序 + 卡片详情）。
 *
 * 支持展开查看下载详情与失败原因 —— 之前失败原因被 tvDesc 的 maxLines=2 截掉了，
 * 所以看起来「只显示失败、不显示原因」。
 *
 * 渲染是增量更新（复用已有 View），否则每次进度回调都会重建 20 多张卡片。
 */
class ModelListController(
    private val activity: AppCompatActivity,
    private val container: LinearLayout,
    private val emptyView: View,
    private val chipGroup: ChipGroup,
    private val spLanguage: Spinner,
    private val spSort: Spinner
) {

    private var lastStates: List<ModelState> = emptyList()
    private var statesById: Map<String, ModelState> = emptyMap()
    private var statusFilter = ModelStatusFilter.ALL
    private var languageFilter = "all"
    private var sortMode = "default"

    private val holders = LinkedHashMap<String, ItemModelBinding>()
    private var renderedOrder: List<String> = emptyList()
    private val expanded = mutableSetOf<String>()

    private val langLabels = arrayOf("全部", "中文", "中英", "粤语", "英文", "韩语", "法语", "孟加拉语")
    private val langKeys = arrayOf("all", "zh", "zh+en", "yue", "en", "ko", "fr", "bn")
    private val sortLabels = arrayOf("默认顺序", "大小从大到小", "大小从小到大")
    private val sortKeys = arrayOf("default", "desc", "asc")

    fun start() {
        setupFilters()
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ModelManager.observeStates(activity).collectLatest { states ->
                    lastStates = states
                    statesById = states.associateBy { it.info.id }
                    render()
                }
            }
        }
    }

    // ---------------- 筛选 ----------------

    private fun setupFilters() {
        chipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isEmpty()) {
                group.check(R.id.chipAll)
                return@setOnCheckedStateChangeListener
            }
            statusFilter = when (checkedIds.first()) {
                R.id.chipNotInstalled -> ModelStatusFilter.NOT_INSTALLED
                R.id.chipInProgress -> ModelStatusFilter.IN_PROGRESS
                R.id.chipInstalled -> ModelStatusFilter.INSTALLED
                else -> ModelStatusFilter.ALL
            }
            render()
        }

        val langAdapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, langLabels)
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spLanguage.adapter = langAdapter
        spLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                languageFilter = langKeys[position.coerceIn(0, langKeys.lastIndex)]
                render()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val sortAdapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, sortLabels)
        sortAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spSort.adapter = sortAdapter
        spSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                sortMode = sortKeys[position.coerceIn(0, sortKeys.lastIndex)]
                render()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateChipCounts() {
        fun label(chipId: Int, base: String, filter: ModelStatusFilter) {
            chipGroup.findViewById<Chip>(chipId)
                ?.let { it.text = "$base ${lastStates.count { s -> filter.matches(s.status) }}" }
        }
        label(R.id.chipAll, activity.getString(R.string.filter_all), ModelStatusFilter.ALL)
        label(R.id.chipNotInstalled, activity.getString(R.string.filter_not_installed), ModelStatusFilter.NOT_INSTALLED)
        label(R.id.chipInProgress, activity.getString(R.string.filter_in_progress), ModelStatusFilter.IN_PROGRESS)
        label(R.id.chipInstalled, activity.getString(R.string.filter_installed), ModelStatusFilter.INSTALLED)
    }

    private fun matchesLanguage(modelLang: String, filter: String): Boolean = when (filter) {
        "all" -> true
        "zh+en" -> modelLang.contains("zh") && modelLang.contains("en")
        else -> modelLang.contains(filter)
    }

    // ---------------- 渲染 ----------------

    private fun render() {
        updateChipCounts()

        val filtered = lastStates
            .filter { statusFilter.matches(it.status) }
            .filter { matchesLanguage(it.info.language, languageFilter) }
        val sorted = when (sortMode) {
            "asc" -> filtered.sortedBy { it.info.downloadSizeBytes }
            "desc" -> filtered.sortedByDescending { it.info.downloadSizeBytes }
            else -> filtered
        }

        if (sorted.isEmpty()) {
            container.removeAllViews()
            holders.clear()
            renderedOrder = emptyList()
            emptyView.isVisible = true
            return
        }
        emptyView.isVisible = false

        val ids = sorted.map { it.info.id }

        // 1) 回收不再显示的卡片
        holders.keys.toList().filter { it !in ids }.forEach { id ->
            holders.remove(id)?.root?.let { container.removeView(it) }
        }
        // 2) 补齐缺失的卡片
        ids.forEach { id ->
            if (holders[id] == null) {
                val newBinding = ItemModelBinding.inflate(LayoutInflater.from(activity), container, false)
                holders[id] = newBinding
                wire(newBinding)
            }
        }
        // 3) 顺序变化时重排（筛选 / 排序是用户操作，重建顺序不频繁）
        if (ids != renderedOrder) {
            container.removeAllViews()
            ids.forEach { id -> holders[id]?.let { container.addView(it.root) } }
            renderedOrder = ids
        }
        // 4) 更新内容
        sorted.forEach { state -> holders[state.info.id]?.let { bind(it, state) } }
    }

    /** 绑定一次点击事件；事件里按 id 取最新状态，避免持有过期对象。 */
    private fun wire(b: ItemModelBinding) {
        b.btnToggleDetails.setOnClickListener {
            val id = b.root.tag as? String ?: return@setOnClickListener
            if (!expanded.remove(id)) expanded.add(id)
            statesById[id]?.let { bind(b, it) }
        }
        b.tvError.setOnClickListener {
            val id = b.root.tag as? String ?: return@setOnClickListener
            statesById[id]?.let { showErrorDialog(it) }
        }
        b.btnCopyDetails.setOnClickListener {
            val id = b.root.tag as? String ?: return@setOnClickListener
            statesById[id]?.let { copyDetails(it) }
        }
    }

    private fun bind(b: ItemModelBinding, state: ModelState) {
        b.root.tag = state.info.id
        val info = state.info

        b.tvName.text = info.name
        b.tvBadge.isVisible = state.isActive
        b.tvMeta.text = listOf(
            languageLabel(info.language),
            "sherpa-onnx",
            FormatUtils.size(activity, info.downloadSizeBytes, "大小未知")
        ).joinToString(" · ")

        val statusText = when (state.status) {
            ModelInstallStatus.NOT_INSTALLED -> activity.getString(R.string.model_status_not_installed)
            ModelInstallStatus.QUEUED -> activity.getString(R.string.model_status_queued)
            ModelInstallStatus.DOWNLOADING -> activity.getString(R.string.model_status_downloading)
            ModelInstallStatus.VERIFYING -> stageLabel(state.stage.ifBlank { "verifying" })
            ModelInstallStatus.PAUSED -> activity.getString(R.string.model_status_paused)
            ModelInstallStatus.INSTALLED -> activity.getString(R.string.model_status_installed)
            ModelInstallStatus.FAILED -> activity.getString(R.string.model_status_failed)
        }
        b.tvStatus.text = when {
            state.status == ModelInstallStatus.FAILED && !state.error.isNullOrBlank() ->
                "$statusText · ${state.error}"
            state.status == ModelInstallStatus.PAUSED && state.downloadedBytes > 0L ->
                "$statusText · ${activity.getString(R.string.downloaded_short, FormatUtils.size(activity, state.downloadedBytes))}"
            else -> statusText
        }
        b.tvStatus.setTextColor(
            activity.getColor(
                when (state.status) {
                    ModelInstallStatus.INSTALLED -> R.color.status_installed
                    ModelInstallStatus.DOWNLOADING, ModelInstallStatus.VERIFYING -> R.color.status_downloading
                    ModelInstallStatus.PAUSED, ModelInstallStatus.QUEUED -> R.color.status_paused
                    ModelInstallStatus.FAILED -> R.color.status_failed
                    else -> R.color.status_queued
                }
            )
        )

        b.tvSize.text = when {
            state.isInstalled && state.installedSizeBytes > 0L ->
                activity.getString(R.string.installed_size_short, FormatUtils.size(activity, state.installedSizeBytes))
            info.downloadSizeBytes > 0L -> FormatUtils.size(activity, info.downloadSizeBytes)
            else -> ""
        }

        // ---- 进度 ----
        val showProgress = state.status in listOf(
            ModelInstallStatus.QUEUED,
            ModelInstallStatus.DOWNLOADING,
            ModelInstallStatus.VERIFYING,
            ModelInstallStatus.PAUSED
        )
        b.llProgress.isVisible = showProgress
        b.tvProgressInfo.isVisible = showProgress
        if (showProgress) {
            val known = state.progressPercent >= 0
            val determinate = known && state.status in listOf(
                ModelInstallStatus.DOWNLOADING, ModelInstallStatus.PAUSED
            )
            b.progress.isIndeterminate = !determinate
            if (determinate) b.progress.progress = state.progressPercent
            b.tvPercent.text = if (determinate) "${state.progressPercent}%" else ""
            b.tvProgressInfo.text = buildProgressInfo(state)
        } else {
            b.progress.isIndeterminate = false
            b.tvPercent.text = ""
        }

        // ---- 失败原因 ----
        b.tvError.isVisible = state.hasFailure
        if (state.hasFailure) {
            val summary = state.errorDetail?.summary() ?: state.error.orEmpty()
            b.tvError.text = activity.getString(R.string.model_failed_prefix, summary) +
                "\n" + activity.getString(R.string.model_details_tap_hint)
        }

        // ---- 详情 ----
        val isExpanded = state.info.id in expanded
        b.llDetails.isVisible = isExpanded
        b.btnToggleDetails.text = activity.getString(
            if (isExpanded) R.string.model_details_hide else R.string.model_details
        )
        if (isExpanded) b.tvDetails.text = buildDetails(state)

        // ---- 操作按钮 ----
        b.actions.removeAllViews()
        when (state.status) {
            ModelInstallStatus.NOT_INSTALLED ->
                actionButton(b, activity.getString(R.string.action_download)) {
                    ModelManager.download(activity, info)
                }

            ModelInstallStatus.FAILED -> {
                actionButton(b, activity.getString(R.string.action_retry)) {
                    ModelManager.download(activity, info)
                }
                actionButton(b, activity.getString(R.string.detail_error_message)) { showErrorDialog(state) }
                actionButton(b, activity.getString(R.string.action_cancel)) {
                    ModelManager.cancel(activity, info)
                }
            }

            ModelInstallStatus.QUEUED, ModelInstallStatus.DOWNLOADING, ModelInstallStatus.VERIFYING -> {
                actionButton(b, activity.getString(R.string.action_pause)) {
                    ModelManager.pause(activity, info)
                }
                actionButton(b, activity.getString(R.string.action_cancel)) {
                    ModelManager.cancel(activity, info)
                }
            }

            ModelInstallStatus.PAUSED -> {
                actionButton(b, activity.getString(R.string.action_resume)) {
                    ModelManager.resume(activity, info)
                }
                actionButton(b, activity.getString(R.string.action_cancel)) {
                    ModelManager.cancel(activity, info)
                }
            }

            ModelInstallStatus.INSTALLED -> {
                if (!state.isActive) {
                    actionButton(b, activity.getString(R.string.action_set_active)) {
                        ModelManager.setActiveModel(activity, info.id)
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.model_activated, info.name),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                actionButton(b, activity.getString(R.string.action_delete)) { confirmDelete(state) }
            }
        }
    }

    private fun buildProgressInfo(state: ModelState): String = when {
        state.status == ModelInstallStatus.QUEUED -> activity.getString(R.string.stage_queued)
        state.downloadedBytes > 0L && state.totalBytes > 0L -> buildString {
            append(
                activity.getString(
                    R.string.progress_bytes,
                    FormatUtils.size(activity, state.downloadedBytes),
                    FormatUtils.size(activity, state.totalBytes)
                )
            )
            if (state.bytesPerSec > 0L) {
                append(" · ")
                append(
                    activity.getString(
                        R.string.progress_speed,
                        FormatUtils.speed(activity, state.bytesPerSec),
                        FormatUtils.eta(state.etaSec)
                    )
                )
            }
        }

        state.downloadedBytes > 0L ->
            activity.getString(R.string.progress_no_total, FormatUtils.size(activity, state.downloadedBytes))

        else -> activity.getString(
            R.string.model_stage_running,
            stageLabel(state.stage.ifBlank { "downloading" })
        )
    }

    /** 展开区 / 错误弹窗里的完整文案。 */
    private fun buildDetails(state: ModelState): String {
        val info = state.info
        val sb = StringBuilder()
        fun row(label: String, value: String?) {
            if (value.isNullOrBlank()) return
            sb.append(label).append(": ").append(value).append('\n')
        }

        row(activity.getString(R.string.detail_status), statusLabel(state.status))
        if (state.stage.isNotBlank()) {
            row(activity.getString(R.string.detail_stage), stageLabel(state.stage))
        }
        row(activity.getString(R.string.detail_language), languageLabel(info.language))
        row(activity.getString(R.string.detail_engine), "sherpa-onnx（流式离线识别）")
        row(
            activity.getString(R.string.detail_download_size),
            FormatUtils.size(activity, info.downloadSizeBytes, "未知")
        )
        if (state.isInstalled) {
            row(
                activity.getString(R.string.detail_installed_size),
                FormatUtils.size(activity, state.installedSizeBytes)
            )
        }
        if (state.downloadedBytes > 0L) {
            row(activity.getString(R.string.detail_downloaded), FormatUtils.size(activity, state.downloadedBytes))
        }
        if (state.bytesPerSec > 0L) {
            row(activity.getString(R.string.detail_speed), FormatUtils.speed(activity, state.bytesPerSec))
            row(activity.getString(R.string.detail_eta), FormatUtils.eta(state.etaSec))
        }
        if (state.installedPath.isNotBlank()) {
            row(activity.getString(R.string.detail_install_path), state.installedPath)
        }
        row(activity.getString(R.string.detail_url), info.archive.url)

        val err = state.errorDetail
        if (err != null) {
            sb.append('\n')
            row(activity.getString(R.string.detail_error_time), DownloadDiagnostics.stamp(err.time))
            row(activity.getString(R.string.detail_error_message), err.message)
            row(activity.getString(R.string.detail_error_type), err.type)
            row(activity.getString(R.string.detail_error_cause), err.cause)
            if (err.stage.isNotBlank()) {
                row(activity.getString(R.string.detail_error_stage), stageLabel(err.stage))
            }
            if (err.httpCode > 0) row(activity.getString(R.string.detail_error_http), err.httpCode.toString())
            if (err.downloadedBytes > 0L) {
                row(
                    activity.getString(R.string.detail_error_bytes),
                    FormatUtils.size(activity, err.downloadedBytes)
                )
            }
        }

        val log = DownloadDiagnostics.readLog(activity, info.id)
        if (log.isNotEmpty()) {
            sb.append('\n').append(activity.getString(R.string.detail_log)).append(":\n")
            log.forEach { sb.append("  ").append(it).append('\n') }
        }
        if (!err?.stack.isNullOrBlank()) {
            sb.append('\n').append(activity.getString(R.string.detail_stack)).append(":\n")
            sb.append(err?.stack).append('\n')
        }
        sb.append('\n').append(activity.getString(R.string.download_continues_hint))
        return sb.toString()
    }

    private fun copyDetails(state: ModelState) {
        activity.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("model-details", buildDetails(state)))
        Toast.makeText(activity, R.string.details_copied, Toast.LENGTH_SHORT).show()
    }

    private fun showErrorDialog(state: ModelState) {
        val builder = AlertDialog.Builder(activity)
            .setTitle(
                if (state.hasFailure) R.string.model_error_dialog_title else R.string.model_details
            )
            .setMessage(buildDetails(state))
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.action_copy_details) { _, _ -> copyDetails(state) }
        if (state.hasFailure) {
            builder.setPositiveButton(R.string.action_retry) { _, _ ->
                ModelManager.download(activity, state.info)
            }
        } else {
            builder.setPositiveButton(android.R.string.ok, null)
        }
        builder.show()
    }

    private fun confirmDelete(state: ModelState) {
        AlertDialog.Builder(activity)
            .setTitle(state.info.name)
            .setMessage(
                activity.getString(
                    R.string.model_delete_confirm,
                    FormatUtils.size(activity, state.installedSizeBytes, "未知")
                )
            )
            .setPositiveButton(R.string.action_delete) { _, _ ->
                ModelManager.delete(activity, state.info)
                expanded.remove(state.info.id)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun actionButton(b: ItemModelBinding, label: String, onClick: () -> Unit) {
        val btn = MaterialButton(
            activity, null, androidx.appcompat.R.attr.borderlessButtonStyle
        ).apply {
            text = label
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 6, 0) }
            setOnClickListener { onClick() }
        }
        b.actions.addView(btn)
    }

    private fun statusLabel(status: ModelInstallStatus): String = activity.getString(
        when (status) {
            ModelInstallStatus.NOT_INSTALLED -> R.string.model_status_not_installed
            ModelInstallStatus.QUEUED -> R.string.model_status_queued
            ModelInstallStatus.DOWNLOADING -> R.string.model_status_downloading
            ModelInstallStatus.VERIFYING -> R.string.model_status_verifying
            ModelInstallStatus.PAUSED -> R.string.model_status_paused
            ModelInstallStatus.INSTALLED -> R.string.model_status_installed
            ModelInstallStatus.FAILED -> R.string.model_status_failed
        }
    )

    private fun stageLabel(stage: String): String = activity.getString(
        when (stage) {
            "init" -> R.string.stage_init
            "queued" -> R.string.stage_queued
            "downloading" -> R.string.stage_downloading
            "extracting" -> R.string.stage_extracting
            "verifying" -> R.string.stage_verifying
            "installing" -> R.string.stage_installing
            "loading" -> R.string.stage_loading
            "paused" -> R.string.stage_paused
            "done" -> R.string.stage_done
            else -> R.string.stage_downloading
        }
    )

    companion object {
        /** 语言代码（"zh,en"）转中文标签，供模型卡片与切换弹窗共用。 */
        fun languageLabel(code: String): String = code.split(",")
            .mapNotNull {
                when (it.trim().lowercase()) {
                    "zh" -> "中文"
                    "en" -> "英文"
                    "yue" -> "粤语"
                    "ko" -> "韩语"
                    "fr" -> "法语"
                    "bn" -> "孟加拉语"
                    else -> it.trim().takeIf { s -> s.isNotEmpty() }
                }
            }
            .joinToString("·")
            .ifBlank { "未标注" }
    }
}
