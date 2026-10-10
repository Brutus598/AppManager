// SPDX-License-Identifier: GPL-3.0-or-later
package com.appmanager.processmanager.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appmanager.processmanager.R
import com.appmanager.processmanager.data.ProcessInfo
import java.text.NumberFormat
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessScreen(vm: ProcessViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    val ctx = LocalContext.current

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = ctx.getString(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = ctx.getString(
                                    R.string.process_count,
                                    state.processes.size
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        if (state.selected.isNotEmpty()) {
                            TextButton(onClick = { vm.clearSelection() }) {
                                Text(ctx.getString(R.string.cancel))
                            }
                            IconButton(onClick = { vm.requestKill(KillTarget.Selected(state.selected)) }) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteSweep,
                                    contentDescription = ctx.getString(R.string.kill_selected),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        } else {
                            IconButton(onClick = { vm.scan() }) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = ctx.getString(R.string.scan),
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
                SearchBar(
                    query = state.query,
                    onQueryChange = vm::setQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                AnimatedVisibility(
                    visible = state.isScanning,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primaryContainer,
                    )
                }
            }
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (state.selected.isEmpty()) {
                    FloatingActionButton(
                        onClick = {
                            if (!state.hasUsageAccess) {
                                vm.askPermissionIfNeeded()
                            } else {
                                vm.requestKill(KillTarget.All)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteSweep,
                            contentDescription = ctx.getString(R.string.kill_all),
                        )
                    }
                }
            }
        },
    ) { inner ->
        Box(Modifier.padding(inner)) {
            if (state.processes.isEmpty() && !state.isScanning) {
                EmptyState(Modifier.align(Alignment.Center))
            } else {
                ProcessList(
                    processes = state.filtered,
                    selected = state.selected,
                    onToggle = vm::toggleSelect,
                )
            }
        }
    }

    // Dialogs
    if (state.showPermissionDialog) {
        AlertDialog(
            onDismissRequest = vm::dismissPermissionDialog,
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            title = { Text(ctx.getString(R.string.permission_title)) },
            text = { Text(ctx.getString(R.string.permission_message)) },
            confirmButton = {
                TextButton(onClick = {
                    ctx.startActivity(vm.usageAccessIntent())
                    vm.dismissPermissionDialog()
                }) { Text(ctx.getString(R.string.open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissPermissionDialog) {
                    Text(ctx.getString(R.string.cancel))
                }
            },
        )
    }

    state.showKillConfirm?.let { target ->
        val count = when (target) {
            KillTarget.All -> state.killableCount
            is KillTarget.Selected -> target.pids.count { pid ->
                state.processes.any { p -> p.pid == pid && p.isKillable() }
            }
        }
        val msg = when (target) {
            KillTarget.All -> ctx.getString(R.string.confirm_kill_all_message, count)
            is KillTarget.Selected -> ctx.getString(R.string.confirm_kill_message, count)
        }
        AlertDialog(
            onDismissRequest = vm::dismissKillConfirm,
            icon = {
                Icon(
                    imageVector = Icons.Rounded.DeleteSweep,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(ctx.getString(R.string.confirm_kill_title)) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { vm.performKill(target) }) {
                    Text(
                        ctx.getString(R.string.confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissKillConfirm) {
                    Text(ctx.getString(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(ctx.getString(R.string.search_processes)) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ProcessList(
    processes: List<ProcessInfo>,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(processes, key = { it.pid }) { p ->
            ProcessCard(
                process = p,
                selected = p.pid in selected,
                onToggle = { onToggle(p.pid) },
            )
        }
    }
}

@Composable
private fun ProcessCard(
    process: ProcessInfo,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        onClick = onToggle,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 2.dp else 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Selection / type indicator circle
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            selected -> MaterialTheme.colorScheme.primary
                            process.isForeground -> Color(0xFF10B981)
                            process.isSystem -> Color(0xFFF59E0B)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    Text(
                        text = process.label.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            process.isForeground || process.isSystem -> Color.White
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = process.label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    TypeChip(process)
                }
                Text(
                    text = process.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                MetricsRow(process)
            }
        }
    }
}

@Composable
private fun TypeChip(p: ProcessInfo) {
    val ctx = LocalContext.current
    val (text, color) = when {
        p.isForeground -> ctx.getString(R.string.type_app) to Color(0xFF10B981)
        p.isService -> ctx.getString(R.string.type_service) to Color(0xFF3B82F6)
        p.isSystem -> ctx.getString(R.string.system_proc) to Color(0xFFF59E0B)
        else -> ctx.getString(R.string.type_background) to Color(0xFF8B5CF6)
    }
    Badge(containerColor = color.copy(alpha = 0.15f), contentColor = color) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun MetricsRow(p: ProcessInfo) {
    val fmt = remember { NumberFormat.getNumberInstance(Locale.getDefault()).apply { maximumFractionDigits = 1 } }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Metric(
            value = "${fmt.format(p.cpuPercent)}%",
            progress = p.cpuPercent / 100f,
            color = Color(0xFF3B82F6),
        )
        Metric(
            value = "${fmt.format(p.memoryMegabytes)} MB",
            progress = (p.memoryMegabytes / 512f).coerceIn(0f, 1f),
            color = Color(0xFF8B5CF6),
        )
        Text(
            text = formatDuration(p.uptimeMillis),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Metric(value: String, progress: Float, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Box(
            Modifier
                .width(56.dp)
                .height(3.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = ctx.getString(R.string.no_processes),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatDuration(ms: Long): String {
    val d = ms.milliseconds
    val hours = d.inWholeHours
    val mins = d.inWholeMinutes % 60
    val secs = d.inWholeSeconds % 60
    return when {
        hours > 0 -> "%dh %dm".format(hours, mins)
        mins > 0 -> "%dm %ds".format(mins, secs)
        else -> "%ds".format(secs)
    }
}

// -- Previews ---------------------------------------------------------------

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun ScreenPreview() {
    ProcessManagerTheme {
        Surface {
            Text("Preview available in Android Studio")
        }
    }
}
