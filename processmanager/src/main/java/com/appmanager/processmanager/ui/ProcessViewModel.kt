// SPDX-License-Identifier: GPL-3.0-or-later
package com.appmanager.processmanager.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appmanager.processmanager.data.ProcessInfo
import com.appmanager.processmanager.data.ProcessRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ProcessUiState(
    val processes: List<ProcessInfo> = emptyList(),
    val selected: Set<Int> = emptySet(),       // PIDs of selected processes
    val isScanning: Boolean = false,
    val query: String = "",
    val hasUsageAccess: Boolean = false,
    val message: String? = null,
    val showPermissionDialog: Boolean = false,
    val showKillConfirm: KillTarget? = null,
) {
    val filtered: List<ProcessInfo>
        get() {
            val q = query.trim().lowercase()
            return if (q.isEmpty()) processes
            else processes.filter {
                it.label.lowercase().contains(q) ||
                    it.name.lowercase().contains(q) ||
                    it.packageName?.lowercase()?.contains(q) == true
            }
        }

    val totalMemoryBytes: Long get() = processes.sumOf { it.memoryBytes }
    val killableCount: Int get() = processes.count { it.isKillable() }
}

sealed class KillTarget {
    object All : KillTarget()
    data class Selected(val pids: Set<Int>) : KillTarget()
}

class ProcessViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ProcessRepository(app.applicationContext)

    private val _state = MutableStateFlow(ProcessUiState())
    val state: StateFlow<ProcessUiState> = _state.asStateFlow()

    private var autoRefreshJob: Job? = null

    init {
        viewModelScope.launch {
            _state.update { it.copy(hasUsageAccess = repo.hasUsageAccess()) }
            scan()
            startAutoRefresh()
        }
    }

    fun scan() {
        viewModelScope.launch {
            _state.update { it.copy(isScanning = true) }
            val list = repo.scan()
            _state.update {
                // Preserve selection for pids that still exist.
                val stillPresent = list.mapTo(HashSet()) { p -> p.pid }
                it.copy(
                    processes = list,
                    isScanning = false,
                    selected = it.selected intersect stillPresent,
                    hasUsageAccess = repo.hasUsageAccess(),
                )
            }
        }
    }

    fun toggleSelect(pid: Int) {
        _state.update { s ->
            val newSel = s.selected.toMutableSet()
            if (!newSel.add(pid)) newSel.remove(pid)
            s.copy(selected = newSel)
        }
    }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun askPermissionIfNeeded() {
        if (!repo.hasUsageAccess()) {
            _state.update { it.copy(showPermissionDialog = true) }
        }
    }

    fun dismissPermissionDialog() =
        _state.update { it.copy(showPermissionDialog = false) }

    fun usageAccessIntent(): Intent = repo.requestUsageAccessIntent()

    fun dismissKillConfirm() = _state.update { it.copy(showKillConfirm = null) }

    fun requestKill(target: KillTarget) {
        _state.update { it.copy(showKillConfirm = target) }
    }

    fun performKill(target: KillTarget) {
        val current = _state.value
        val toKill = when (target) {
            KillTarget.All -> current.processes.filter { it.isKillable() }
            is KillTarget.Selected -> current.processes.filter {
                it.pid in target.pids && it.isKillable()
            }
        }
        val killed = repo.killAll(toKill)
        val msg = app.applicationContext.getString(
            com.appmanager.processmanager.R.string.processes_killed, killed
        )
        _state.update {
            it.copy(
                selected = emptySet(),
                showKillConfirm = null,
                message = msg,
            )
        }
        // Re-scan after a short delay so the list reflects the kills.
        viewModelScope.launch {
            delay(400)
            scan()
        }
    }

    fun consumeMessage() { _state.update { it.copy(message = null) } }

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(3000)
                val list = repo.scan()
                _state.update { s ->
                    val stillPresent = list.mapTo(HashSet()) { p -> p.pid }
                    s.copy(
                        processes = list,
                        selected = s.selected intersect stillPresent,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        autoRefreshJob?.cancel()
        super.onCleared()
    }
}
