package com.rezerv.app.updates

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rezerv.app.AppContainer
import com.rezerv.app.R
import com.rezerv.app.data.model.UpdateDownloadState
import com.rezerv.app.data.model.UpdateEntry
import com.rezerv.app.data.model.UpdateInfo
import com.rezerv.app.databinding.ActivityUpdatesBinding
import com.rezerv.app.databinding.ItemUpdateHistoryBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UpdatesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUpdatesBinding
    private var latestEntry: UpdateEntry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUpdatesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val baseTopPadding = binding.topBarUpdates.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBarUpdates.updatePadding(top = baseTopPadding + bars.top)
            insets
        }

        binding.btnBackUpdates.setOnClickListener { finish() }
        binding.btnInstallUpdate.setOnClickListener { onInstallClicked() }
        observeDownloadState()

        lifecycleScope.launch {
            loadUpdateInfo()
        }
    }

    override fun onResume() {
        super.onResume()
        renderLatestSection(latestEntry, AppContainer.updateRepository.currentVersionCode())
    }

    private suspend fun loadUpdateInfo() {
        val currentCode = AppContainer.updateRepository.currentVersionCode()
        val currentName = AppContainer.updateRepository.currentVersionName()
        binding.tvCurrentVersion.text = "$currentName ($currentCode)"

        val cachedInfo = AppContainer.updateRepository.cachedUpdateInfo(currentCode)
        if (cachedInfo != null) {
            latestEntry = cachedInfo.latest
            renderInfo(cachedInfo, currentCode)
            AppContainer.updateRepository.markLatestUpdateSeen()
            setLoading(loading = true, lockActions = false)
        } else {
            setLoading(loading = true, lockActions = true)
        }

        val result = AppContainer.updateRepository.checkForUpdatesAndDownload(currentCode)
        setLoading(loading = false, lockActions = false)

        result.onSuccess { info ->
            val mergedInfo = mergeWithCached(cachedInfo, info, currentCode)
            latestEntry = mergedInfo.latest
            AppContainer.updateRepository.markLatestUpdateSeen()
            renderInfo(mergedInfo, currentCode)
        }.onFailure { throwable ->
            if (cachedInfo == null) {
                Toast.makeText(
                    this@UpdatesActivity,
                    throwable.message ?: getString(R.string.updates_load_error),
                    Toast.LENGTH_SHORT
                ).show()
                renderInfo(UpdateInfo(latest = null, history = emptyList(), hasUpdate = false), currentCode)
            }
        }
    }

    private fun renderInfo(info: UpdateInfo, currentCode: Int) {
        renderLatestSection(info.latest, currentCode)
        renderHistory(info.history)
    }

    private fun renderLatestSection(latest: UpdateEntry?, currentCode: Int) {
        if (latest == null) {
            binding.tvLatestVersion.text = getString(R.string.updates_latest_missing)
            binding.tvUpdateStatus.text = getString(R.string.updates_status_unknown)
            binding.btnInstallUpdate.isEnabled = false
            binding.btnInstallUpdate.text = getString(R.string.updates_install_button)
            return
        }

        val size = if (latest.fileSize > 0) " • ${formatFileSize(latest.fileSize)}" else ""
        binding.tvLatestVersion.text = "${latest.versionName} (${latest.versionCode})$size"

        val updateAvailable = latest.versionCode > currentCode
        if (!updateAvailable) {
            binding.tvUpdateStatus.text = getString(R.string.updates_status_actual)
            binding.btnInstallUpdate.isEnabled = false
            binding.btnInstallUpdate.text = getString(R.string.updates_actual_button)
            return
        }

        val downloaded = AppContainer.updateRepository.isLatestDownloaded(latest, currentCode)
        binding.tvUpdateStatus.text = if (downloaded) {
            getString(R.string.updates_status_ready)
        } else {
            getString(R.string.updates_status_available)
        }
        binding.btnInstallUpdate.isEnabled = true
        binding.btnInstallUpdate.text = if (downloaded) {
            getString(R.string.updates_install_button)
        } else {
            getString(R.string.updates_download_install_button)
        }
    }

    private fun renderHistory(history: List<UpdateEntry>) {
        binding.historyContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        history.sortedByDescending { it.versionCode }.forEach { entry ->
            val itemBinding = ItemUpdateHistoryBinding.inflate(inflater, binding.historyContainer, false)
            itemBinding.tvHistoryVersion.text = "${entry.versionName} (${entry.versionCode})"
            itemBinding.tvHistoryDate.text = formatDate(entry.publishedAt)
            itemBinding.tvHistoryTitle.text = entry.title
            itemBinding.tvHistoryChanges.text = formatChanges(entry.changes)
            binding.historyContainer.addView(itemBinding.root)
        }

        if (binding.historyContainer.childCount == 0) {
            val empty = View.inflate(this, android.R.layout.simple_list_item_1, null)
            val text = empty.findViewById<android.widget.TextView>(android.R.id.text1)
            text.text = getString(R.string.updates_history_empty)
            text.setTextColor(0xFF93A994.toInt())
            binding.historyContainer.addView(empty)
        }
    }

    private fun onInstallClicked() {
        val latest = latestEntry ?: return
        val currentCode = AppContainer.updateRepository.currentVersionCode()
        if (latest.versionCode <= currentCode) return

        lifecycleScope.launch {
            binding.btnInstallUpdate.isEnabled = false
            if (!AppContainer.updateRepository.isLatestDownloaded(latest, currentCode)) {
                val result = AppContainer.updateRepository.checkForUpdatesAndDownload(currentCode)
                if (result.isFailure) {
                    Toast.makeText(
                        this@UpdatesActivity,
                        result.exceptionOrNull()?.message ?: getString(R.string.updates_download_error),
                        Toast.LENGTH_SHORT
                    ).show()
                    binding.btnInstallUpdate.isEnabled = true
                    return@launch
                }
            }

            val started = AppContainer.updateRepository.installDownloadedUpdate(this@UpdatesActivity)
            if (!started) {
                Toast.makeText(
                    this@UpdatesActivity,
                    getString(R.string.updates_install_error),
                    Toast.LENGTH_SHORT
                ).show()
            }
            binding.btnInstallUpdate.isEnabled = true
            renderLatestSection(latest, currentCode)
        }
    }

    private fun setLoading(loading: Boolean, lockActions: Boolean) {
        binding.progressUpdates.isVisible = loading
        if (lockActions) {
            binding.btnInstallUpdate.isEnabled = !loading
        }
    }

    private fun observeDownloadState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppContainer.updateRepository.downloadState.collect { state ->
                    renderDownloadState(state)
                }
            }
        }
    }

    private fun renderDownloadState(state: UpdateDownloadState) {
        when (state) {
            is UpdateDownloadState.Downloading -> {
                binding.downloadProgress.isVisible = true
                binding.tvDownloadProgress.isVisible = true
                val percent = state.progressPercent
                if (percent != null) {
                    binding.downloadProgress.isIndeterminate = false
                    binding.downloadProgress.progress = percent
                    binding.tvDownloadProgress.text = getString(R.string.updates_download_progress_percent, percent)
                } else {
                    binding.downloadProgress.isIndeterminate = true
                    binding.tvDownloadProgress.text = getString(R.string.updates_download_progress_preparing)
                }
            }

            is UpdateDownloadState.Failed -> {
                binding.downloadProgress.isVisible = false
                binding.tvDownloadProgress.isVisible = true
                binding.tvDownloadProgress.text = state.message ?: getString(R.string.updates_download_error)
            }

            is UpdateDownloadState.Ready -> {
                binding.downloadProgress.isVisible = false
                binding.tvDownloadProgress.isVisible = false
                renderLatestSection(latestEntry, AppContainer.updateRepository.currentVersionCode())
            }

            UpdateDownloadState.Idle -> {
                binding.downloadProgress.isVisible = false
                binding.tvDownloadProgress.isVisible = false
            }
        }
    }

    private fun mergeWithCached(cached: UpdateInfo?, fresh: UpdateInfo, currentCode: Int): UpdateInfo {
        if (cached == null) return fresh
        val mergedByVersion = linkedMapOf<Int, UpdateEntry>()
        (fresh.history + cached.history)
            .sortedByDescending { it.versionCode }
            .forEach { entry ->
                if (!mergedByVersion.containsKey(entry.versionCode)) {
                    mergedByVersion[entry.versionCode] = entry
                }
            }
        val mergedHistory = mergedByVersion.values.toList()
        val mergedLatest = fresh.latest ?: mergedHistory.firstOrNull()
        return fresh.copy(
            latest = mergedLatest,
            history = mergedHistory,
            hasUpdate = (mergedLatest?.versionCode ?: 0) > currentCode
        )
    }

    private fun formatChanges(changes: List<String>): String {
        if (changes.isEmpty()) return getString(R.string.updates_changes_missing)
        return changes.joinToString(separator = "\n") { "• $it" }
    }

    private fun formatDate(timestamp: Long): String {
        if (timestamp <= 0) return ""
        return runCatching {
            val format = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            format.format(Date(timestamp))
        }.getOrDefault("")
    }

    private fun formatFileSize(sizeBytes: Long): String {
        if (sizeBytes <= 0) return "0 B"
        val kb = sizeBytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.2f MB", mb)
    }
}
