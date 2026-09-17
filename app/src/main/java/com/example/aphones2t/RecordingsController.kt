package com.example.aphones2t

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.aphones2t.data.TranscriptEntity
import com.example.aphones2t.data.TranscriptRepository
import com.example.aphones2t.data.TranscriptSegments
import com.example.aphones2t.databinding.ItemRecordingBinding
import com.example.aphones2t.model.ModelCatalog
import com.example.aphones2t.model.ModelManager
import com.example.aphones2t.utils.FileTranscriber
import com.example.aphones2t.utils.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录音列表控制器（全应用唯一一份录音列表）。
 *
 * - 实时录音、导入音频、离线转写的结果全部在这里，不再分「本次录音 / 历史记录」两套；
 * - 每条记录可以直接播放 / 拖动进度；
 * - 带时间轴的记录按句展示，点某一句就跳到录音的对应时间（边听边看文字）；
 * - 「更多」里有转写 / 生成时间轴 / 复制 / 分享 / 删除。
 */
class RecordingsController(
    private val activity: AppCompatActivity,
    private val recycler: RecyclerView,
    private val emptyView: View,
    private val repo: TranscriptRepository,
    private val onCountChanged: ((Int) -> Unit)? = null
) {

    private val adapter = RecordingsAdapter(
        onPlay = { togglePlay(it, 0) },
        onSeek = { item, pos -> seekTo(item, pos) },
        onSegment = { item, startMs -> togglePlay(item, startMs) },
        onMore = { showActions(it) }
    )

    private var player: MediaPlayer? = null
    private var playingId = -1L
    private val handler = Handler(Looper.getMainLooper())
    private var transcribingId = -1L
    private var busy = false

    private val progressTick = object : Runnable {
        override fun run() {
            val p = player ?: return
            val vh = adapter.activeVh
            if (vh != null) {
                val pos = if (p.isPlaying) p.currentPosition else vh.lastPos
                if (!vh.binding.sbProgress.isPressed) {
                    vh.binding.sbProgress.progress = pos
                    vh.binding.tvItemPos.text = FormatUtils.clock(pos.toLong())
                }
                vh.lastPos = pos
            }
            // Keep ticking while a player is alive, even if the playing item is scrolled off.
            handler.postDelayed(this, 200L)
        }
    }

    fun start() {
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.all.collectLatest { list ->
                    adapter.submitList(list)
                    emptyView.isVisible = list.isEmpty()
                    onCountChanged?.invoke(list.size)
                }
            }
        }
    }

    /** 页面离开时停掉播放，避免 MediaPlayer 泄漏。 */
    fun pausePlayback() = stopPlayback()

    // ---------------- 播放 ----------------

    private fun togglePlay(item: TranscriptEntity, startMs: Int) {
        val path = item.wavPath
        if (path.isNullOrBlank()) {
            Toast.makeText(activity, R.string.no_audio_file, Toast.LENGTH_SHORT).show()
            return
        }
        if (playingId == item.id && startMs == 0) {
            val p = player
            if (p?.isPlaying == true) {
                p.pause()
                adapter.notifyPlayingChanged()
            } else {
                p?.start()
                handler.postDelayed(progressTick, 0L)
                adapter.notifyPlayingChanged()
            }
            return
        }
        stopPlayback()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(path)
            mp.setOnPreparedListener { prepared ->
                prepared.start()
                if (startMs > 0) {
                    try { prepared.seekTo(startMs) } catch (_: Exception) {}
                }
                playingId = item.id
                adapter.playingId = item.id
                handler.postDelayed(progressTick, 0L)
            }
            mp.setOnCompletionListener { stopPlayback() }
            mp.setOnErrorListener { _, _, _ -> stopPlayback(); true }
            mp.prepareAsync()
            player = mp
        } catch (_: Exception) {
            Toast.makeText(activity, R.string.playback_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPlayback() {
        handler.removeCallbacks(progressTick)
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        if (playingId != -1L) {
            playingId = -1L
            adapter.playingId = -1L
        }
    }

    private fun seekTo(item: TranscriptEntity, progressMs: Int) {
        if (playingId == item.id) {
            try { player?.seekTo(progressMs) } catch (_: Exception) {}
        }
    }

    // ---------------- 每条记录的操作 ----------------

    private fun isPending(item: TranscriptEntity): Boolean =
        item.text.isBlank() && !item.wavPath.isNullOrBlank() &&
            (item.modelName.isBlank() || item.modelName == activity.getString(R.string.pending_transcribe))

    private fun showActions(item: TranscriptEntity) {
        // 用「便宜版本」判断有没有可用模型（不做原生加载）；真正的转写里再严格校验。
        val modelDir = ModelManager.getInstalledModelDirectory(activity)
        val pending = isPending(item)
        val hasTimeline = TranscriptSegments.decode(item.segmentsJson).isNotEmpty()
        val canRebuild = !hasTimeline && item.text.isNotBlank() &&
            !item.wavPath.isNullOrBlank() && modelDir != null

        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += activity.getString(
            if (playingId == item.id) R.string.stop_playback else R.string.play
        ) to {
            if (playingId == item.id) stopPlayback() else togglePlay(item, 0)
        }
        if (pending && modelDir != null) {
            actions += activity.getString(R.string.action_transcribe) to { transcribe(item, modelDir) }
        }
        if (canRebuild) {
            actions += activity.getString(R.string.action_rebuild_timeline) to { rebuildTimeline(item, modelDir!!) }
        }
        actions += activity.getString(R.string.copy) to { copy(item) }
        actions += activity.getString(R.string.share) to { share(item) }
        actions += activity.getString(R.string.delete) to { confirmDelete(item) }

        val labels = actions.map { it.first }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(
                activity.getString(
                    R.string.recordings_item_title,
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.createdAt)),
                    FormatUtils.clock(item.durationMs)
                )
            )
            .setItems(labels) { _, which -> actions[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copy(item: TranscriptEntity) {
        val text = item.text.ifBlank {
            activity.getString(if (isPending(item)) R.string.pending_transcribe else R.string.transcribe_empty)
        }
        activity.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("transcript", text))
        Toast.makeText(activity, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun share(item: TranscriptEntity) {
        activity.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, item.text)
                },
                activity.getString(R.string.share)
            )
        )
    }

    private fun confirmDelete(item: TranscriptEntity) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.delete)
            .setMessage(activity.getString(R.string.recordings_delete_confirm))
            .setPositiveButton(R.string.delete) { _, _ ->
                if (playingId == item.id) stopPlayback()
                val wavPath = item.wavPath
                activity.lifecycleScope.launch {
                    repo.delete(item)
                    // 一并删掉音频文件，否则「删除」只删记录、文件一直堆着
                    withContext(Dispatchers.IO) {
                        val f = wavPath?.let { File(it) }
                        if (f != null && f.parentFile?.name == "recordings" && f.exists()) f.delete()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 重新转写一条待转写记录（无模型时的录音 / 导入）。 */
    private fun transcribe(item: TranscriptEntity, modelDir: File) {
        if (transcribingId == item.id) return
        val path = item.wavPath ?: run {
            Toast.makeText(activity, R.string.no_audio_file, Toast.LENGTH_SHORT).show(); return
        }
        transcribingId = item.id
        Toast.makeText(activity, activity.getString(R.string.transcribing_status), Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                FileTranscriber.transcribe(activity, modelDir, path)
            }
            transcribingId = -1L
            if (result == null) {
                Toast.makeText(activity, activity.getString(R.string.transcribe_failed), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val modelName = ModelManager.getActiveModelId(activity)
                ?.let { ModelCatalog.findById(activity, it)?.name } ?: "sherpa-onnx"
            repo.update(
                item.copy(
                    text = result.text,
                    durationMs = if (result.durationMs > 0L) result.durationMs else item.durationMs,
                    modelName = modelName,
                    segmentsJson = result.segmentsJson
                )
            )
            Toast.makeText(activity, activity.getString(R.string.transcribe_success), Toast.LENGTH_SHORT).show()
        }
    }

    /** 给老录音（或没切分成功的）重新算一遍时间轴，保留原有文本。 */
    private fun rebuildTimeline(item: TranscriptEntity, modelDir: File) {
        if (busy) return
        val path = item.wavPath ?: run {
            Toast.makeText(activity, R.string.no_audio_file, Toast.LENGTH_SHORT).show(); return
        }
        busy = true
        Toast.makeText(activity, activity.getString(R.string.transcribing_status), Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                FileTranscriber.transcribe(activity, modelDir, path)
            }
            busy = false
            if (result?.segmentsJson == null) {
                Toast.makeText(activity, activity.getString(R.string.rebuild_timeline_failed), Toast.LENGTH_SHORT).show()
                return@launch
            }
            repo.update(
                item.copy(
                    text = item.text.ifBlank { result.text },
                    segmentsJson = result.segmentsJson,
                    durationMs = if (item.durationMs > 0L) item.durationMs else result.durationMs
                )
            )
            Toast.makeText(activity, activity.getString(R.string.rebuild_timeline_done), Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- 列表适配器 ----------------

    private inner class RecordingsAdapter(
        private val onPlay: (TranscriptEntity) -> Unit,
        private val onSeek: (TranscriptEntity, Int) -> Unit,
        private val onSegment: (TranscriptEntity, Int) -> Unit,
        private val onMore: (TranscriptEntity) -> Unit
    ) : ListAdapter<TranscriptEntity, RecordingsAdapter.VH>(DIFF) {

        /** 当前正在播放的记录 id；-1 表示没有。 */
        var playingId: Long = -1L
            set(value) {
                if (field == value) return
                field = value
                if (value == -1L) activeVh = null
                notifyDataSetChanged()
            }

        var activeVh: VH? = null

        private val expandedItems = mutableSetOf<Long>()

        fun notifyPlayingChanged() = notifyDataSetChanged()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            val b = holder.binding
            holder.item = item

            val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(item.createdAt))
            b.tvTitle.text = activity.getString(
                R.string.recordings_item_title,
                time,
                FormatUtils.clock(item.durationMs)
            ) + " · " + item.modelName.ifBlank { "—" }
            b.tvItemTotal.text = FormatUtils.clock(item.durationMs)
            b.tvItemPos.text = FormatUtils.clock(0L)
            holder.lastPos = 0

            val pending = isPending(item)
            b.tvBadge.isVisible = pending

            // 「待转写」的记录（无模型时录的 / 导入的）在模型就绪后可以一键转写
            val pendingModelDir = if (pending) ModelManager.getInstalledModelDirectory(activity) else null
            b.btnTranscribe.isVisible = pendingModelDir != null
            b.btnTranscribe.setOnClickListener { pendingModelDir?.let { dir -> transcribe(item, dir) } }

            val isPlaying = item.id == playingId
            b.btnPlay.text = activity.getString(
                if (isPlaying && player?.isPlaying == true) R.string.stop_playback else R.string.play
            )
            if (isPlaying) activeVh = holder else if (activeVh === holder) activeVh = null

            b.sbProgress.max = item.durationMs.coerceAtLeast(1L).toInt()
            b.sbProgress.progress = if (isPlaying) (player?.currentPosition ?: 0) else 0

            // ---- 文本：带时间轴时按句可点 ----
            val segments = TranscriptSegments.decode(item.segmentsJson)
            b.tvTimelineHint.isVisible = segments.isNotEmpty()
            if (segments.isEmpty()) {
                b.tvText.text = when {
                    item.text.isNotBlank() -> item.text
                    pending -> activity.getString(R.string.pending_transcribe)
                    !item.wavPath.isNullOrBlank() -> activity.getString(R.string.transcribe_empty)
                    else -> "[空]"
                }
                b.tvText.movementMethod = null
                b.tvText.maxLines = 6
                b.tvText.ellipsize = android.text.TextUtils.TruncateAt.END
                b.btnExpand.isVisible = item.text.length > 150
            } else {
                b.tvText.text = buildSegmentSpans(segments) { startMs -> onSegment(item, startMs) }
                b.tvText.movementMethod = LinkMovementMethod.getInstance()
                b.tvText.highlightColor = Color.TRANSPARENT
                val expanded = item.id in expandedItems
                b.tvText.maxLines = if (expanded) Int.MAX_VALUE else 6
                b.tvText.ellipsize = if (expanded) null else android.text.TextUtils.TruncateAt.END
                b.btnExpand.isVisible = segments.size > 3 || item.text.length > 150
                b.btnExpand.text = activity.getString(
                    if (expanded) R.string.recordings_collapse else R.string.recordings_expand
                )
                b.btnExpand.setOnClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                    if (!expandedItems.remove(item.id)) expandedItems.add(item.id)
                    notifyItemChanged(pos)
                }
            }

            b.btnPlay.setOnClickListener { onPlay(item) }
            b.btnMore.setOnClickListener { onMore(item) }
            b.sbProgress.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        holder.lastPos = progress
                        b.tvItemPos.text = FormatUtils.clock(progress.toLong())
                    }
                }

                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}

                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    val pos = sb?.progress ?: 0
                    holder.lastPos = pos
                    b.tvItemPos.text = FormatUtils.clock(pos.toLong())
                    onSeek(item, pos)
                }
            })
        }

        inner class VH(val binding: ItemRecordingBinding) : RecyclerView.ViewHolder(binding.root) {
            var item: TranscriptEntity? = null
            var lastPos: Int = 0
        }
    }

    /** 把分段渲染成 [mm:ss] 文本 + 可点击跳转。 */
    private fun buildSegmentSpans(
        segments: List<com.example.aphones2t.data.TranscriptSegment>,
        onClick: (Int) -> Unit
    ): CharSequence {
        val sb = SpannableStringBuilder()
        segments.forEachIndexed { index, seg ->
            val lineStart = sb.length
            sb.append('[').append(FormatUtils.clock(seg.startMs)).append("] ").append(seg.text)
            if (index != segments.lastIndex) sb.append('\n')
            val lineEnd = sb.length
            sb.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) = onClick(seg.startMs.toInt())
                    override fun updateDrawState(ds: TextPaint) {
                        ds.isUnderlineText = false
                    }
                },
                lineStart, lineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return sb
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<TranscriptEntity>() {
            override fun areItemsTheSame(a: TranscriptEntity, b: TranscriptEntity) = a.id == b.id
            override fun areContentsTheSame(a: TranscriptEntity, b: TranscriptEntity) = a == b
        }
    }
}
