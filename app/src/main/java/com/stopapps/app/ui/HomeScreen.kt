package com.stopapps.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stopapps.app.R
import com.stopapps.app.data.AppEntry
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel = viewModel(),
    onOpenWhitelist: () -> Unit
) {
    val context = LocalContext.current
    val apps by vm.apps.collectAsState()
    val ram by vm.ram.collectAsState()
    val loading by vm.loading.collectAsState()
    val selection by vm.selection.collectAsState()
    val turbo by vm.turbo.collectAsState()
    val running by vm.running.collectAsState()
    val hasUsage by vm.hasUsageAccess.collectAsState()
    val a11y by vm.a11yEnabled.collectAsState()
    val query by vm.query.collectAsState()
    val logVersion by vm.logVersion.collectAsState()
    var showLog by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(AppFilter.RUNNING) }

    val visible = remember(apps, query, filter) {
        apps.filter { e ->
            (filter == AppFilter.ALL || (filter == AppFilter.RUNNING && e.isRunning)) &&
                (query.isBlank() ||
                    e.label.contains(query, ignoreCase = true) ||
                    e.packageName.contains(query, ignoreCase = true))
        }
    }
    // Recompose log panel when new lines arrive.
    @Suppress("UNUSED_VARIABLE")
    val logTick = logVersion
    val logLines = remember(logTick) { vm.logLines() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { vm.refreshAll() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                    IconButton(onClick = onOpenWhitelist) {
                        Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.whitelist_title))
                    }
                }
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // ---- RAM card ----
            item { RamCard(ram = ram) }

            // ---- setup checklist ----
            if (!hasUsage || !a11y) {
                item {
                    SetupCard(
                        hasUsage = hasUsage,
                        a11y = a11y,
                        onGrantUsage = { vm.openUsageSettings() },
                        onOpenA11y = {
                            try {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            } catch (_: Exception) {
                            }
                        }
                    )
                }
            }

            // ---- run controls ----
            item {
                RunControls(
                    running = running,
                    turbo = turbo,
                    selectedCount = selection.size,
                    canRun = a11y && selection.isNotEmpty(),
                    onTurboChange = vm::setTurbo,
                    onStop = vm::stopSelected,
                    onCancel = vm::cancelRun
                )
            }

            // ---- log panel ----
            if (logLines.isNotEmpty() || running) {
                item {
                    LogPanel(
                        lines = logLines,
                        expanded = showLog,
                        onToggle = { showLog = !showLog }
                    )
                }
            }

            // ---- search + filters ----
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = vm::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = filter == AppFilter.ALL,
                        onClick = { filter = AppFilter.ALL },
                        label = { Text(stringResource(R.string.filter_all)) }
                    )
                    FilterChip(
                        selected = filter == AppFilter.RUNNING,
                        onClick = { filter = AppFilter.RUNNING },
                        label = { Text(stringResource(R.string.filter_running)) }
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.selectAllVisible(visible) }) {
                        Text(stringResource(R.string.select_all))
                    }
                    TextButton(onClick = vm::clearSelection) {
                        Text(stringResource(R.string.clear))
                    }
                }
            }

            // ---- app list ----
            if (loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (visible.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (filter == AppFilter.RUNNING && !hasUsage)
                                stringResource(R.string.no_running_no_usage)
                            else
                                stringResource(R.string.no_apps),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(visible, key = { it.packageName }) { entry ->
                    AppRow(
                        entry = entry,
                        checked = entry.packageName in selection,
                        onToggle = { vm.toggleSelect(entry.packageName) },
                        onWhitelist = { vm.toggleWhitelist(entry.packageName) }
                    )
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

private enum class AppFilter { ALL, RUNNING }

@Composable
private fun RamCard(ram: com.stopapps.app.data.RamInfo?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painterResource(R.drawable.ic_memory),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.ram_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (ram == null) {
                    Text(
                        stringResource(R.string.ram_loading),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    Text(
                        stringResource(
                            R.string.ram_stats,
                            formatBytes(ram.usedBytes),
                            formatBytes(ram.totalBytes),
                            ram.usedPercent,
                            formatBytes(ram.availBytes)
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { ram.usedPercent / 100f },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupCard(
    hasUsage: Boolean,
    a11y: Boolean,
    onGrantUsage: () -> Unit,
    onOpenA11y: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.setup_title),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            SetupRow(
                done = hasUsage,
                text = stringResource(R.string.setup_usage),
                actionText = stringResource(R.string.grant),
                onAction = onGrantUsage
            )
            SetupRow(
                done = a11y,
                text = stringResource(R.string.setup_a11y),
                actionText = stringResource(R.string.open_settings),
                onAction = onOpenA11y
            )
        }
    }
}

@Composable
private fun SetupRow(done: Boolean, text: String, actionText: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (done) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.width(8.dp))
        Text(text, modifier = Modifier.weight(1f), fontSize = 14.sp)
        if (!done) {
            TextButton(onClick = onAction) { Text(actionText) }
        }
    }
}

@Composable
private fun RunControls(
    running: Boolean,
    turbo: Boolean,
    selectedCount: Int,
    canRun: Boolean,
    onTurboChange: (Boolean) -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.turbo_title),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.turbo_desc),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = turbo, onCheckedChange = onTurboChange, enabled = !running)
            }
            if (running) {
                Button(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(painterResource(R.drawable.ic_stop), null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.cancel_run))
                }
            } else {
                Button(
                    onClick = onStop,
                    enabled = canRun,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (selectedCount > 0)
                            stringResource(R.string.stop_n_apps, selectedCount)
                        else
                            stringResource(R.string.stop_apps),
                        fontSize = 16.sp
                    )
                }
                if (selectedCount == 0) {
                    Text(
                        stringResource(R.string.select_hint),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun LogPanel(lines: List<String>, expanded: Boolean, onToggle: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.run_log, lines.size),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
            }
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val tail = lines.takeLast(60)
                    for (line in tail) {
                        Text(
                            line,
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    entry: AppEntry,
    checked: Boolean,
    onToggle: () -> Unit,
    onWhitelist: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(
            containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(drawable = entry.icon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.label,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    statusText(entry),
                    fontSize = 12.sp,
                    color = if (entry.isRunning) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onWhitelist) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = stringResource(R.string.whitelist_action),
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
        }
    }
}

@Composable
private fun statusText(entry: AppEntry): String {
    val ram = if (entry.ramKb > 0)
        " • " + formatBytes(entry.ramKb * 1024)
    else ""
    val state = if (entry.isRunning)
        stringResource(R.string.status_running)
    else
        stringResource(R.string.status_not_running)
    return state + ram
}

@Composable
private fun AppIcon(drawable: Drawable?) {
    val bitmap = remember(drawable) { drawable?.toBitmap() }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
        )
    } else {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.KeyboardArrowRight, null)
        }
    }
}

private fun Drawable.toBitmap(): Bitmap? {
    return try {
        if (this is BitmapDrawable && bitmap != null) return bitmap
        val w = intrinsicWidth.takeIf { it > 0 } ?: 96
        val h = intrinsicHeight.takeIf { it > 0 } ?: 96
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        setBounds(0, 0, w, h)
        draw(canvas)
        bmp
    } catch (_: Exception) {
        null
    }
}

private fun formatBytes(bytes: Long): String {
    val gb = bytes / 1_073_741_824.0
    if (gb >= 1) return String.format(Locale.US, "%.2f GB", gb)
    val mb = bytes / 1_048_576.0
    if (mb >= 1) return String.format(Locale.US, "%.0f MB", mb)
    val kb = bytes / 1024.0
    return String.format(Locale.US, "%.0f KB", kb)
}
