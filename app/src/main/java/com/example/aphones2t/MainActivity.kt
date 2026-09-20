package com.example.aphones2t

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.aphones2t.data.AppDatabase
import com.example.aphones2t.data.TranscriptRepository
import com.example.aphones2t.databinding.ActivityMainBinding
import com.example.aphones2t.dialog.AddCustomModelDialog
import com.example.aphones2t.model.LocalModelInfo
import com.example.aphones2t.model.ModelCatalog
import com.example.aphones2t.model.ModelInstallStatus
import com.example.aphones2t.model.ModelManager
import com.example.aphones2t.model.ModelState
import com.example.aphones2t.utils.AudioFileDecoder
import com.example.aphones2t.utils.ErrorCodes
import com.example.aphones2t.utils.FileTranscriber
import com.example.aphones2t.utils.FormatUtils
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主界面 —— 三个页面：实时转写 / 录音 / 模型。
 *
 * 之前「实时转写」和录音列表挤在同一个页面里，而且录音还分成本次录音与历史记录
 * 两套（点「管理录音」看不到录音列表）。现在：
 *  - 实时转写页只负责：当前模型卡片 + 转写文本 + 录音控制；
 *  - 录音页是唯一的录音列表（实时录音 / 导入 / 离线转写都在这里）；
 *  - 模型页是完整的模型管理（筛选 / 下载 / 详情 / 失败原因）。
 */
class MainActivity : AppCompatActivity(), AddCustomModelDialog.OnModelAddedListener {

    private lateinit var binding: ActivityMainBinding
    private var recording = false
    private var paused = false
    private var processing = false

    /** 当前页（TAB_*）：旋转屏幕后按这一页恢复，而不是跳回第一页。 */
    private var currentPage = TAB_REALTIME

    // ---- 录音计时：待机显示 --:--，录音中显示已录时长（第 12 章线框） ----
    private val handler = Handler(Looper.getMainLooper())
    private var timerRunning = false
    private var segmentStart = 0L
    private var accumulatedMs = 0L
    private val timerTick = object : Runnable {
        override fun run() {
            if (!timerRunning) return
            val ms = if (paused) accumulatedMs
            else accumulatedMs + (System.currentTimeMillis() - segmentStart)
            binding.tvElapsed.text = FormatUtils.clock(ms)
            handler.postDelayed(this, 500L)
        }
    }

    private val repo by lazy {
        TranscriptRepository(AppDatabase.get(this).transcriptDao())
    }

    private lateinit var recordingsController: RecordingsController
    private lateinit var modelListController: ModelListController

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { importAudio(it) } }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            startRecording()
        } else {
            // 区分「这次拒绝」和「不再询问」：后者直接告诉用户去哪儿开
            val mic = Manifest.permission.RECORD_AUDIO
            val permanent = !shouldShowRequestPermissionRationale(mic)
            showError(ErrorCodes.permission(this, permanent))
        }
    }

    private val partialReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            binding.tvTranscript.text = i.getStringExtra(TranscriptionService.EXTRA_TEXT) ?: ""
        }
    }

    private val finalReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val text = i.getStringExtra(TranscriptionService.EXTRA_TEXT) ?: ""
            val wav = i.getStringExtra(TranscriptionService.EXTRA_WAV)
            val dur = i.getLongExtra(TranscriptionService.EXTRA_DURATION, 0L)
            val segments = i.getStringExtra(TranscriptionService.EXTRA_SEGMENTS)
            Log.d("MainActivity", "FINAL received text=$text wav=$wav dur=$dur")
            binding.tvTranscript.text = text
            updateUI()
            if (text.isNotBlank() || !wav.isNullOrBlank()) saveRecording(text, wav, dur, segments)
        }
    }

    private val errorReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val code = i.getStringExtra(TranscriptionService.EXTRA_ERROR_CODE).orEmpty()
            val detail = i.getStringExtra(TranscriptionService.EXTRA_TEXT).orEmpty()
            when (code) {
                // 模型加载失败：不再静默，也不再停录音 —— 弹窗说清「本次仅录音」+ 缺了哪些文件
                TranscriptionService.ERR_ASR_INIT -> {
                    val roles = detail.split(",").filter { it.isNotBlank() }
                    showError(
                        ErrorCodes.asrInit(this@MainActivity, roles),
                        onRetry = { switchToTab(TAB_MODELS) },
                        positiveLabelRes = R.string.settings_manage_models
                    )
                }

                TranscriptionService.ERR_MIC -> showError(ErrorCodes.mic(this@MainActivity, detail))

                TranscriptionService.ERR_RECORD -> Toast.makeText(
                    this@MainActivity,
                    detail.ifBlank { getString(R.string.record_error) },
                    Toast.LENGTH_LONG
                ).show()

                else -> showError(ErrorCodes.generic(this@MainActivity, detail))
            }
            updateUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_import -> {
                    if (!processing) importLauncher.launch("audio/*"); true
                }
                R.id.action_add_model -> {
                    AddCustomModelDialog().show(supportFragmentManager, "AddCustomModelDialog"); true
                }
                R.id.action_paste_models -> {
                    startActivity(Intent(this, CustomModelActivity::class.java)); true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java)); true
                }
                else -> false
            }
        }

        setupBottomNav()
        setupRealtimePage()
        setupRecordingsPage()
        setupModelsPage()

        updateUI()
        if (savedInstanceState == null) {
            switchToTab(intent.getIntExtra(EXTRA_TAB, TAB_REALTIME))
        } else {
            showPage(currentPage)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        switchToTab(intent.getIntExtra(EXTRA_TAB, TAB_REALTIME))
    }

    override fun onStart() {
        super.onStart()
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_NOT_EXPORTED else 0
        registerReceiver(partialReceiver, IntentFilter(TranscriptionService.ACTION_PARTIAL), flags)
        registerReceiver(finalReceiver, IntentFilter(TranscriptionService.ACTION_FINAL), flags)
        registerReceiver(errorReceiver, IntentFilter(TranscriptionService.ACTION_ERROR), flags)
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(partialReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(finalReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(errorReceiver) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        recordingsController.pausePlayback()
    }

    // ================= 页面骨架 =================

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_realtime -> { showPage(TAB_REALTIME); true }
                R.id.nav_recordings -> { showPage(TAB_RECORDINGS); true }
                R.id.nav_models -> { showPage(TAB_MODELS); true }
                else -> false
            }
        }
    }

    /**
     * 切换页面（底栏三选一）。
     *
     * 顺带按页切换工具栏菜单：**导入只在「录音」页出现**（它属于录音列表），
     * 「添加 / 批量粘贴自定义模型」只在「模型」页出现 —— 以前这五个入口全挤在一起。
     */
    private fun showPage(index: Int) {
        binding.llRealtime.isVisible = index == TAB_REALTIME
        binding.llRecordings.isVisible = index == TAB_RECORDINGS
        binding.llModels.isVisible = index == TAB_MODELS
        currentPage = index

        binding.toolbar.menu.findItem(R.id.action_import)?.isVisible = index == TAB_RECORDINGS
        binding.toolbar.menu.findItem(R.id.action_add_model)?.isVisible = index == TAB_MODELS
        binding.toolbar.menu.findItem(R.id.action_paste_models)?.isVisible = index == TAB_MODELS
    }

    private fun switchToTab(index: Int) {
        val clamped = index.coerceIn(TAB_REALTIME, TAB_MODELS)
        binding.bottomNav.selectedItemId = when (clamped) {
            TAB_RECORDINGS -> R.id.nav_recordings
            TAB_MODELS -> R.id.nav_models
            else -> R.id.nav_realtime
        }
        // 选中的还是同一项时 listener 不会回调，这里补一次保证页面与菜单同步
        showPage(clamped)
    }

    // ================= ① 实时转写 =================

    private fun setupRealtimePage() {
        binding.btnRecord.setOnClickListener {
            if (recording) stopRecording() else checkPermissionsAndRecord()
        }
        binding.btnPause.setOnClickListener {
            if (!recording) return@setOnClickListener
            if (paused) {
                startService(
                    Intent(this, TranscriptionService::class.java)
                        .setAction(TranscriptionService.ACTION_RESUME)
                )
                paused = false
                segmentStart = System.currentTimeMillis()
            } else {
                startService(
                    Intent(this, TranscriptionService::class.java)
                        .setAction(TranscriptionService.ACTION_PAUSE)
                )
                accumulatedMs += System.currentTimeMillis() - segmentStart
                paused = true
            }
            updateUI()
        }
        binding.btnCopyTranscript.setOnClickListener {
            copyText(binding.tvTranscript.text?.toString().orEmpty())
        }
        // 「切换」与「管理模型」合并成这一个「更换」入口
        binding.btnChangeModel.setOnClickListener { showModelSwitcher() }

        // 模型状态变化 → 顶部卡片实时刷新（下载完成会自动变成当前模型）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ModelManager.observeStates(this@MainActivity).collectLatest { states ->
                    renderCurrentModel(states)
                }
            }
        }
    }

    /** 显示「当前用的是什么模型」——以前实时转写页完全没有这个信息。 */
    private fun renderCurrentModel(states: List<ModelState>) {
        val activeId = ModelManager.getActiveModelId(this)
        val installed = states.filter { it.isInstalled }
        // 直接从状态里挑，不要调 getActiveModel()：那条路会真的去加载 ONNX 模型，
        // 而这里每次进度回调（约 500ms 一次）都会执行。
        val model: LocalModelInfo? =
            installed.firstOrNull { it.info.id == activeId }?.info ?: installed.firstOrNull()?.info

        if (model == null) {
            binding.tvCurrentModelName.text = getString(R.string.current_model_none)
            val downloading = states.firstOrNull { it.status == ModelInstallStatus.DOWNLOADING }
            binding.tvCurrentModelMeta.text = if (downloading != null) {
                getString(
                    R.string.model_downloading_meta,
                    downloading.info.name,
                    downloading.progressPercent.coerceAtLeast(0)
                )
            } else {
                getString(R.string.current_model_none_hint)
            }
            binding.btnChangeModel.isEnabled = installed.isNotEmpty()
            binding.tvTranscript.hint = getString(R.string.hint_no_model)
            return
        }

        binding.tvCurrentModelName.text = model.name
        binding.tvCurrentModelMeta.text = buildString {
            append(ModelListController.languageLabel(this@MainActivity, model.language))
            if (model.downloadSizeBytes > 0) {
                append(" · ").append(FormatUtils.size(this@MainActivity, model.downloadSizeBytes))
            }
            append(" · ").append(getString(R.string.model_status_installed))
            if (model.id != activeId) {
                append(getString(R.string.model_not_active_wrapped))
            }
        }
        binding.btnChangeModel.isEnabled = true
        if (!recording) binding.tvTranscript.hint = getString(R.string.hint_ready)
    }

    private fun showModelSwitcher() {
        val installed = ModelManager.installedModels(this)
        if (installed.isEmpty()) {
            Toast.makeText(this, R.string.model_switch_no_installed, Toast.LENGTH_SHORT).show()
            switchToTab(TAB_MODELS)
            return
        }
        val activeId = ModelManager.getActiveModelId(this)
        val sheet = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.model_switch_title)
            textSize = 17f
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, 0, 0, dp(4))
        })
        installed.forEach { info ->
            val isActive = info.id == activeId
            content.addView(TextView(this).apply {
                text = buildString {
                    if (isActive) append("✓ ")
                    append(info.name)
                    append('\n')
                    append(ModelListController.languageLabel(this@MainActivity, info.language))
                    append(" · ").append(FormatUtils.size(this@MainActivity, info.downloadSizeBytes))
                }
                textSize = 15f
                setPadding(0, dp(12), 0, dp(12))
                setTextColor(getColor(if (isActive) R.color.seed else R.color.text_primary))
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    ModelManager.setActiveModel(this@MainActivity, info.id)
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.model_activated, info.name),
                        Toast.LENGTH_SHORT
                    ).show()
                    sheet.dismiss()
                }
            })
        }
        content.addView(TextView(this).apply {
            text = getString(
                if (recording) R.string.model_switch_recording_hint else R.string.model_switch_hint
            )
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
            setTextColor(getColor(R.color.text_secondary))
        })
        sheet.setContentView(ScrollView(this).apply { addView(content) })
        sheet.show()
    }

    // ================= ② 录音 =================

    private fun setupRecordingsPage() {
        recordingsController = RecordingsController(
            activity = this,
            recycler = binding.rvRecordings,
            emptyView = binding.tvRecordingsEmpty,
            repo = repo,
            onCountChanged = { count ->
                binding.tvRecordingsCount.text = getString(R.string.recordings_count, count)
            }
        )
        recordingsController.start()
        // 无模型时点「下载模型后转写」→ 弹说明 + 一键去模型页
        recordingsController.pendingCta = {
            showError(
                ErrorCodes.generic(this).copy(
                    code = "NEED_MODEL",
                    level = ErrorCodes.Level.L4,
                    title = getString(R.string.err_need_model_title),
                    body = getString(R.string.err_need_model_body)
                ),
                onRetry = { switchToTab(TAB_MODELS) },
                positiveLabelRes = R.string.settings_manage_models
            )
        }
        binding.btnImportAudio.setOnClickListener {
            if (!processing) importLauncher.launch("audio/*")
        }
    }

    // ================= ③ 模型 =================

    private fun setupModelsPage() {
        modelListController = ModelListController(
            activity = this,
            container = binding.llModelContainer,
            emptyView = binding.tvModelEmpty,
            chipGroup = binding.chipGroupStatus,
            spLanguage = binding.spLanguage,
            spSort = binding.spSort
        )
        modelListController.start()
    }

    override fun onModelAdded() {
        // 状态通过 ModelManager 的流自动刷新
    }

    // ================= 录音控制 =================

    private fun checkPermissionsAndRecord() {
        val needed = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) startRecording() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startRecording() {
        startForegroundService(
            Intent(this, TranscriptionService::class.java).setAction(TranscriptionService.ACTION_START)
        )
        recording = true; paused = false
        accumulatedMs = 0L
        updateUI()
    }

    private fun stopRecording() {
        startService(
            Intent(this, TranscriptionService::class.java).setAction(TranscriptionService.ACTION_STOP)
        )
        recording = false; paused = false
        updateUI()
    }

    private fun updateUI() {
        binding.btnRecord.isEnabled = !processing
        // checkable + ColorStateList：录音中变红，待机是主色（不再用代码写死颜色，
        // 深色模式下才不会出现「黑字叠黑底」）
        binding.btnRecord.isChecked = recording
        if (recording) {
            binding.btnRecord.setIconResource(R.drawable.ic_stop)
            binding.btnRecord.contentDescription = getString(R.string.btn_stop_cd)
            binding.btnPause.isVisible = true
            binding.btnPause.setIconResource(
                if (paused) R.drawable.ic_play else R.drawable.ic_pause
            )
            binding.btnPause.contentDescription = getString(
                if (paused) R.string.btn_resume_cd else R.string.btn_pause_cd
            )
            binding.tvStatus.isVisible = true
            binding.tvStatus.text = getString(
                if (paused) R.string.status_paused else R.string.status_recording
            )
            startTimer()
        } else {
            binding.btnRecord.setIconResource(R.drawable.ic_mic)
            binding.btnRecord.contentDescription = getString(R.string.btn_record_cd)
            binding.btnPause.isVisible = false
            // 待机不再常驻显示「待机」两个字，只留计时占位
            binding.tvStatus.isVisible = false
            stopTimer()
            binding.tvElapsed.text = getString(R.string.timer_idle)
        }
        // 复制按钮只在真的有文本时出现（以前常驻，点了才说「没有可复制的内容」）
        binding.btnCopyTranscript.isVisible = !binding.tvTranscript.text.isNullOrBlank()
    }

    private fun startTimer() {
        if (timerRunning) return
        timerRunning = true
        segmentStart = System.currentTimeMillis()
        handler.postDelayed(timerTick, 0L)
    }

    private fun stopTimer() {
        timerRunning = false
        accumulatedMs = 0L
        handler.removeCallbacks(timerTick)
    }

    // ================= 错误展示（统一入口） =================

    /**
     * 所有用户可见错误的唯一出口。
     *
     * L0 用 Toast，L2/L4 用对话框：标题 + 人话 + 按需出现的按钮
     * （「换镜像重试」/「重试」/「详情」）。技术细节永远放在「详情」里，
     * 不再把异常原文甩到主界面。
     */
    private fun showError(
        error: ErrorCodes.UiError,
        onRetry: (() -> Unit)? = null,
        onMirror: (() -> Unit)? = null,
        positiveLabelRes: Int? = null
    ) {
        if (error.level == ErrorCodes.Level.L0) {
            Toast.makeText(this, error.oneLine(), Toast.LENGTH_LONG).show()
            return
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(error.title)
            .setMessage(error.body.ifBlank { error.title })

        when {
            error.offerMirror && onMirror != null ->
                builder.setPositiveButton(R.string.action_switch_mirror) { _, _ -> onMirror() }
            error.offerRetry && onRetry != null ->
                builder.setPositiveButton(positiveLabelRes ?: R.string.action_retry) { _, _ -> onRetry() }
            onRetry != null && positiveLabelRes != null ->
                builder.setPositiveButton(positiveLabelRes) { _, _ -> onRetry() }
            else -> builder.setPositiveButton(android.R.string.ok, null)
        }
        if (!error.details.isNullOrBlank()) {
            builder.setNeutralButton(R.string.action_details) { _, _ -> showDetails(error) }
        }
        builder.show()
    }

    /** 技术详情：错误码 + 折叠的原始信息。 */
    private fun showDetails(error: ErrorCodes.UiError) {
        AlertDialog.Builder(this)
            .setTitle(R.string.model_error_dialog_title)
            .setMessage("${getString(R.string.detail_error_message)}：[${error.code}]\n\n${error.details}")
            .setPositiveButton(R.string.action_copy_details) { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(
                        ClipData.newPlainText("error-details", "${error.code}\n${error.details}")
                    )
                Toast.makeText(this, R.string.details_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyText(text: String) {
        if (text.isBlank()) {
            Toast.makeText(this, R.string.nothing_to_copy, Toast.LENGTH_SHORT).show()
            return
        }
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("transcript", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    /** 一条实时录音落库（带分段时间轴），统一出现在录音页。 */
    private fun saveRecording(text: String, wav: String?, durationMs: Long, segmentsJson: String?) {
        val id = ModelManager.getActiveModelId(this)
        val modelName = id?.let { ModelCatalog.findById(this, it)?.name }
            ?: getString(R.string.pending_transcribe)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val rowId = repo.insert(text, wav, durationMs, modelName, segmentsJson)
                Log.d("MainActivity", "inserted rowId=$rowId text='$text'")
            } catch (e: Exception) {
                Log.e("MainActivity", "insert failed", e)
                // 以前这里只写 Log：录音列表里没有这条，用户以为录音丢了
                runOnUiThread { showError(ErrorCodes.room(this@MainActivity, e.message)) }
            }
        }
    }

    // ================= 导入音频 =================

    private fun importAudio(uri: Uri) {
        if (processing) return
        processing = true
        binding.tvStatus.text = getString(R.string.importing_status)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val originalName = queryDisplayName(uri) ?: "import_$ts"
                val ext = originalName.substringAfterLast('.', "").ifBlank { "m4a" }
                val dir = File(filesDir, "recordings").apply { if (!exists()) mkdirs() }
                val dest = File(dir, "import_$ts.$ext")
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException(getString(R.string.err_import_read_failed))

                val modelDir = ModelManager.getActiveModelDirectory(this@MainActivity)
                var text = ""
                var durationMs = 0L
                var modelName = getString(R.string.pending_transcribe)
                var segmentsJson: String? = null
                if (modelDir != null) {
                    when (val r = FileTranscriber.transcribe(this@MainActivity, modelDir, dest.absolutePath)) {
                        is FileTranscriber.Result.Success -> {
                            text = r.text
                            durationMs = r.durationMs
                            segmentsJson = r.segmentsJson
                            modelName = ModelManager.getActiveModelId(this@MainActivity)
                                ?.let { ModelCatalog.findById(this@MainActivity, it)?.name } ?: "sherpa-onnx"
                        }

                        is FileTranscriber.Result.Failure -> {
                            // 转写失败也要说清原因（格式不支持 / 内存不足 / 模型坏了），
                            // 但音频先留下来 —— 以后还能重试，不能白丢
                            durationMs = AudioFileDecoder
                                .decodeToPcm16kMono(this@MainActivity, dest.absolutePath)
                                ?.size?.div(16)?.toLong() ?: 0L
                            runOnUiThread {
                                showError(ErrorCodes.transcribe(this@MainActivity, r.message))
                            }
                        }
                    }
                } else {
                    // 没有模型：仍然记录时长，方便之后一键转写
                    durationMs = AudioFileDecoder
                        .decodeToPcm16kMono(this@MainActivity, dest.absolutePath)
                        ?.size?.div(16)?.toLong() ?: 0L
                }
                repo.insert(text, dest.absolutePath, durationMs, modelName, segmentsJson)
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        if (text.isNotBlank()) getString(R.string.import_success)
                        else getString(R.string.import_pending),
                        Toast.LENGTH_LONG
                    ).show()
                    processing = false
                    updateUI()
                    switchToTab(TAB_RECORDINGS)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    // 导入失败给「人话 + 详情」，不再只拼一句异常消息
                    showError(ErrorCodes.import(this@MainActivity, e.message))
                    processing = false
                    updateUI()
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** 打开主界面时定位到哪个页面（设置页的「管理模型 / 管理录音」会用）。 */
        const val EXTRA_TAB = "extra_tab"
        const val TAB_REALTIME = 0
        const val TAB_RECORDINGS = 1
        const val TAB_MODELS = 2
    }
}
