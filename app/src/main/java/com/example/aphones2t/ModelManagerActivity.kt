package com.example.aphones2t

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.aphones2t.databinding.ActivityModelManagerBinding
import com.example.aphones2t.databinding.ItemModelBinding
import com.example.aphones2t.dialog.AddCustomModelDialog
import com.example.aphones2t.model.ModelCatalog
import com.example.aphones2t.model.ModelInstallStatus
import com.example.aphones2t.model.ModelManager
import com.example.aphones2t.model.ModelState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 模型管理界面
 * 支持模型下载、暂停、恢复、删除和设置活动模型
 */
class ModelManagerActivity : AppCompatActivity(), AddCustomModelDialog.OnModelAddedListener {

    private lateinit var binding: ActivityModelManagerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.navigationIcon = ContextCompat.getDrawable(this, androidx.appcompat.R.drawable.abc_ic_ab_back_material)

        binding.btnAdd.setOnClickListener { addCustom() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ModelManager.observeStates(this@ModelManagerActivity).collectLatest { render(it) }
            }
        }
    }

    private fun addCustom() {
        AddCustomModelDialog().show(
            supportFragmentManager,
            "AddCustomModelDialog"
        )
    }

    override fun onModelAdded() {
        // Model states are automatically updated through the observer
    }

    private fun render(states: List<ModelState>) {
        binding.container.removeAllViews()
        if (states.isEmpty()) return
        states.forEach { binding.container.addView(buildCard(it)) }
    }

    private fun buildCard(state: ModelState): View {
        val b = ItemModelBinding.inflate(layoutInflater, binding.container, false)
        b.tvName.text = state.info.name
        b.tvDesc.text = state.info.description

        val statusText = when (state.status) {
            ModelInstallStatus.NOT_INSTALLED -> getString(R.string.model_status_not_installed)
            ModelInstallStatus.QUEUED -> getString(R.string.model_status_queued)
            ModelInstallStatus.DOWNLOADING -> getString(R.string.model_status_downloading)
            ModelInstallStatus.VERIFYING -> getString(R.string.model_status_verifying)
            ModelInstallStatus.PAUSED -> getString(R.string.model_status_paused)
            ModelInstallStatus.INSTALLED -> getString(R.string.model_status_installed)
            ModelInstallStatus.FAILED -> getString(R.string.model_status_failed)
        }
        b.tvStatus.text = if (state.isActive) "$statusText · ${getString(R.string.active_model)}" else statusText

        val showProgress = state.status in listOf(
            ModelInstallStatus.DOWNLOADING, ModelInstallStatus.VERIFYING, ModelInstallStatus.PAUSED
        )
        b.progress.visibility = if (showProgress) View.VISIBLE else View.GONE
        if (showProgress) {
            if (state.status == ModelInstallStatus.VERIFYING) {
                b.progress.isIndeterminate = true
            } else {
                b.progress.isIndeterminate = false
                b.progress.progress = state.progressPercent.coerceIn(0, 100)
            }
            b.tvProgress.text = "${state.progressPercent}%"
        } else {
            b.tvProgress.text = ""
        }

        if (state.status == ModelInstallStatus.FAILED && !state.error.isNullOrBlank()) {
            b.tvDesc.text = "${state.info.description}\n错误: ${state.error}"
        }

        b.actions.removeAllViews()
        when (state.status) {
            ModelInstallStatus.NOT_INSTALLED, ModelInstallStatus.FAILED ->
                addBtn(b.actions, getString(R.string.action_download)) {
                    ModelManager.download(this, state.info)
                }
            ModelInstallStatus.QUEUED, ModelInstallStatus.DOWNLOADING, ModelInstallStatus.VERIFYING -> {
                addBtn(b.actions, getString(R.string.action_pause)) {
                    ModelManager.pause(this, state.info)
                }
                addBtn(b.actions, getString(R.string.action_cancel)) {
                    ModelManager.cancel(this, state.info)
                }
            }
            ModelInstallStatus.PAUSED -> {
                addBtn(b.actions, getString(R.string.action_resume)) {
                    ModelManager.resume(this, state.info)
                }
                addBtn(b.actions, getString(R.string.action_cancel)) {
                    ModelManager.cancel(this, state.info)
                }
            }
            ModelInstallStatus.INSTALLED -> {
                if (!state.isActive) {
                    addBtn(b.actions, getString(R.string.action_set_active)) {
                        ModelManager.setActiveModel(this, state.info.id)
                    }
                }
                addBtn(b.actions, getString(R.string.action_delete)) {
                    ModelManager.delete(this, state.info)
                }
            }
        }
        return b.root
    }

    private fun addBtn(parent: LinearLayout, label: String, onClick: () -> Unit) {
        val btn = Button(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 16, 0) }
            setOnClickListener { onClick() }
        }
        parent.addView(btn)
    }
}
