package com.mobileailocal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobileailocal.R
import com.mobileailocal.data.DownloadState
import com.mobileailocal.data.ModelEntity
import com.mobileailocal.data.RemoteFile
import com.mobileailocal.domain.Fit
import java.text.NumberFormat
import java.util.Locale

/** モデル画面(指示書 05 を参照)。検索タブと導入済みタブの2タブ。 */
@Composable
fun ModelsScreen(vm: ModelsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val state by vm.search.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    var tabIndex by rememberSaveable { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tabIndex) {
            Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }) {
                Text(stringResource(R.string.models_tab_search), modifier = Modifier.padding(vertical = 10.dp))
            }
            Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }) {
                Text(stringResource(R.string.models_tab_installed), modifier = Modifier.padding(vertical = 10.dp))
            }
        }
        if (tabIndex == 0) {
            SearchTab(vm, state, installed, downloads)
        } else {
            InstalledTab(vm, installed, selectedId)
        }
    }
}

@Composable
private fun SearchTab(
    vm: ModelsViewModel,
    state: SearchUiState,
    installed: List<ModelEntity>,
    downloads: Map<String, DownloadState>,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = { vm.setQuery(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.models_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.runSearch() }),
            )
            TextButton(onClick = { vm.runSearch() }) { Text(stringResource(R.string.models_search)) }
        }
        state.error?.let { e ->
            Text(
                stringResource(R.string.models_search_error, e),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        if (state.searching) {
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { CircularProgressIndicator() }
        } else if (state.results.isEmpty()) {
            RecommendSection(vm, state.recommend, installed, downloads, Modifier.weight(1f).fillMaxWidth())
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(state.results, key = { it.repoId }) { m ->
                    val expanded = state.files.containsKey(m.repoId)
                    val files = state.files[m.repoId]
                    val fileError = state.fileErrors[m.repoId]
                    Column {
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.toggleRepo(m.repoId) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(m.repoId, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    stringResource(R.string.models_repo_stats, formatCount(m.downloads), formatCount(m.likes)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (expanded && files == null) {
                                CircularProgressIndicator(Modifier.width(16.dp), strokeWidth = 2.dp)
                            }
                        }
                        if (expanded) {
                            fileError?.let { e ->
                                Text(
                                    e,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, top = 2.dp, end = 12.dp, bottom = 2.dp),
                                )
                            }
                            when {
                                files == null -> Unit // 読み込み中はこの行のスピナーだけ
                                files.isEmpty() -> Text(
                                    stringResource(R.string.models_no_files),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, top = 2.dp, end = 12.dp, bottom = 2.dp),
                                )
                                else -> files.forEach { f ->
                                    FileRow(
                                        file = f,
                                        download = downloads[f.url],
                                        alreadyInstalled = installed.any { it.fileName == vm.installedName(f) },
                                        onDownload = { vm.download(f) },
                                        onCancel = { vm.cancelDownload(f) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 検索が空のときに出す、端末に合わせたおすすめ。 */
@Composable
private fun RecommendSection(
    vm: ModelsViewModel,
    rec: RecommendUiState,
    installed: List<ModelEntity>,
    downloads: Map<String, DownloadState>,
    modifier: Modifier,
) {
    LaunchedEffect(Unit) { vm.loadRecommendations(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier) {
        Text(
            stringResource(R.string.models_recommend_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        rec.memory?.let { m ->
            Text(
                stringResource(
                    R.string.models_recommend_budget,
                    formatSize(rec.budgetMb * 1024L * 1024L), formatSize(m.totalMb * 1024L * 1024L), formatSize(m.freeMb * 1024L * 1024L),
                ),
                style = MaterialTheme.typography.bodySmall, color = muted,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            if (rec.nonArm64) {
                Text(
                    stringResource(R.string.models_recommend_nonarm),
                    style = MaterialTheme.typography.bodySmall, color = muted,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
        when (rec.phase) {
            Phase.Idle, Phase.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { CircularProgressIndicator() }
            Phase.Error -> Column(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
                Text(
                    stringResource(R.string.models_recommend_error, rec.error.orEmpty()),
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { vm.loadRecommendations(force = true) }) {
                    Text(stringResource(R.string.models_retry))
                }
                Text(stringResource(R.string.models_search_empty), color = muted)
            }
            Phase.Done -> if (rec.items.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                    Text(stringResource(R.string.models_recommend_none), color = muted, modifier = Modifier.padding(12.dp))
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(rec.items, key = { it.file.url }) { r ->
                        Column(Modifier.padding(top = 6.dp)) {
                            Text(
                                r.model.repoId, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                            Text(
                                stringResource(R.string.models_repo_stats, formatCount(r.model.downloads), formatCount(r.model.likes)),
                                style = MaterialTheme.typography.bodySmall, color = muted,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                            FileRow(
                                file = r.file,
                                download = downloads[r.file.url],
                                alreadyInstalled = installed.any { it.fileName == vm.installedName(r.file) },
                                onDownload = { vm.download(r.file) },
                                onCancel = { vm.cancelDownload(r.file) },
                                fit = r.fit,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    file: RemoteFile,
    download: DownloadState?,
    alreadyInstalled: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    fit: Fit? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, top = 4.dp, end = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(file.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${file.quant} · ${formatSize(file.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (fit != null) {
                Text(
                    stringResource(if (fit == Fit.Comfortable) R.string.models_recommend_fit_ok else R.string.models_recommend_fit_tight),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (fit == Fit.Comfortable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        if (download == null) {
            TextButton(onClick = onDownload, enabled = !alreadyInstalled) {
                Text(stringResource(if (alreadyInstalled) R.string.models_installed_mark else R.string.models_download))
            }
        } else if (download.error != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    download.error,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
                TextButton(onClick = onDownload) { Text(stringResource(R.string.models_retry)) }
            }
        } else {
            Column(horizontalAlignment = Alignment.End) {
                // 全体サイズが分かるまで進捗は不確定表示
                if (download.total > 0) {
                    LinearProgressIndicator(progress = { download.fraction }, modifier = Modifier.width(96.dp))
                } else {
                    LinearProgressIndicator(modifier = Modifier.width(96.dp))
                }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
            }
        }
    }
}

@Composable
private fun InstalledTab(vm: ModelsViewModel, installed: List<ModelEntity>, selectedId: String?) {
    // ダイアログは最前面に出すため画面レベルで持つ
    var deleteTarget by remember { mutableStateOf<ModelEntity?>(null) }

    Column(Modifier.fillMaxSize()) {
        if (installed.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                Text(
                    stringResource(R.string.models_installed_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                stringResource(R.string.models_total_size, formatSize(installed.sumOf { it.sizeBytes })),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(installed, key = { it.id }) { m ->
                    InstalledRow(
                        model = m,
                        inUse = m.id == selectedId,
                        onUse = { vm.select(m) },
                        onDelete = { deleteTarget = m },
                    )
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            text = { Text(stringResource(R.string.models_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(target); deleteTarget = null }) {
                    Text(stringResource(R.string.history_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun InstalledRow(
    model: ModelEntity,
    inUse: Boolean,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(model.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${model.quant} · ${formatSize(model.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (inUse) {
            Text(
                stringResource(R.string.models_in_use),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Box {
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = { expanded = true }) { Text("⋮") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (!inUse) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.models_use)) },
                        onClick = { expanded = false; onUse() },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.history_delete)) },
                    onClick = { expanded = false; onDelete() },
                )
            }
        }
    }
}

// 表示用の数値・サイズ整形(GB/MB 表記はコード側で固定)
private fun formatCount(n: Long): String = NumberFormat.getInstance().format(n)

private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.getDefault(), "%.1f GB", mb / 1024.0)
    } else {
        String.format(Locale.getDefault(), "%d MB", (mb).toLong())
    }
}
