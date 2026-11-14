package com.slopware.androidllm.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slopware.androidllm.domain.HfModelEntry
import com.slopware.androidllm.domain.LlmConfig
import com.slopware.androidllm.domain.ModelDownloadState
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelHubSheet(
    config: LlmConfig,
    discoveredModels: List<String>,
    catalogModels: List<HfModelEntry>,
    hfSearchResults: List<HfModelEntry>,
    isSearchingHf: Boolean,
    downloadState: ModelDownloadState,
    isBusy: Boolean,
    onSelectModel: (String) -> Unit,
    onRefreshModels: () -> Unit,
    onImportModel: (uriString: String, fileName: String) -> Unit,
    onDeleteModel: (String) -> Unit,
    onSearchHfRepo: (repoId: String, token: String) -> Unit,
    onDownloadHfModel: (HfModelEntry, String) -> Unit,
    onCancelDownload: () -> Unit,
    onCopySnippet: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Use */* because .litertlm lacks a standard Android MIME mapping.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val fileName = resolveUriFileName(context, uri) ?: "model.litertlm"
            onImportModel(uri.toString(), fileName)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ModelHubSheetBody(
            config = config,
            discoveredModels = discoveredModels,
            catalogModels = catalogModels,
            hfSearchResults = hfSearchResults,
            isSearchingHf = isSearchingHf,
            downloadState = downloadState,
            isBusy = isBusy,
            onSelectModel = onSelectModel,
            onRefreshModels = onRefreshModels,
            onImportLocalClick = { picker.launch(arrayOf("*/*")) },
            onDeleteModel = onDeleteModel,
            onSearchHfRepo = onSearchHfRepo,
            onDownloadHfModel = onDownloadHfModel,
            onCancelDownload = onCancelDownload,
            onCopySnippet = onCopySnippet,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelHubSheetBody(
    config: LlmConfig,
    discoveredModels: List<String>,
    catalogModels: List<HfModelEntry>,
    hfSearchResults: List<HfModelEntry>,
    isSearchingHf: Boolean,
    downloadState: ModelDownloadState,
    isBusy: Boolean,
    onSelectModel: (String) -> Unit,
    onRefreshModels: () -> Unit,
    onImportLocalClick: () -> Unit,
    onDeleteModel: (String) -> Unit,
    onSearchHfRepo: (repoId: String, token: String) -> Unit,
    onDownloadHfModel: (HfModelEntry, String) -> Unit,
    onCancelDownload: () -> Unit,
    onCopySnippet: (String) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val touchSize = minTouchSize()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 640.dp)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Model Hub",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(
                onClick = onRefreshModels,
                enabled = !isBusy,
                modifier = Modifier.sizeIn(minWidth = touchSize, minHeight = touchSize),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Rescan on-device models")
            }
        }

        if (downloadState.isDownloading) {
            ActiveDownloadCard(downloadState = downloadState, onCancel = onCancelDownload)
        }

        PrimaryTabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("On-Device") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("HuggingFace Hub") })
        }

        when (selectedTab) {
            0 -> OnDeviceModelsTab(
                selectedPath = config.modelPath,
                discoveredModels = discoveredModels,
                isBusy = isBusy,
                onSelectModel = onSelectModel,
                onImportLocalClick = onImportLocalClick,
                onDeleteModel = onDeleteModel,
                onCopySnippet = onCopySnippet,
            )
            else -> HuggingFaceHubTab(
                catalogModels = catalogModels,
                searchResults = hfSearchResults,
                isSearching = isSearchingHf,
                downloadState = downloadState,
                onSearchRepo = onSearchHfRepo,
                onDownload = onDownloadHfModel,
                onCancelDownload = onCancelDownload,
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ActiveDownloadCard(
    downloadState: ModelDownloadState,
    onCancel: () -> Unit,
) {
    val progress = if (downloadState.totalBytes > 0L) {
        (downloadState.bytesDownloaded.toFloat() / downloadState.totalBytes).coerceIn(0f, 1f)
    } else {
        null
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Downloading ${downloadState.fileName}",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel ${downloadState.fileName}")
                }
            }
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            val mbDown = formatMb(downloadState.bytesDownloaded)
            val mbTotal = if (downloadState.totalBytes > 0L) " / ${formatMb(downloadState.totalBytes)}" else ""
            val speedMb = String.format(Locale.US, "%.1f MB/s", downloadState.speedBytesPerSec / (1024.0 * 1024.0))
            Text(
                text = "$mbDown$mbTotal · $speedMb",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun OnDeviceModelsTab(
    selectedPath: String,
    discoveredModels: List<String>,
    isBusy: Boolean,
    onSelectModel: (String) -> Unit,
    onImportLocalClick: () -> Unit,
    onDeleteModel: (String) -> Unit,
    onCopySnippet: (String) -> Unit,
) {
    val models = remember(discoveredModels, selectedPath) {
        if (selectedPath.isNotBlank() && selectedPath !in discoveredModels) {
            listOf(selectedPath) + discoveredModels
        } else {
            discoveredModels
        }
    }
    val adbSnippet = "adb push model.litertlm /data/local/tmp/llm/"

    LazyColumn(
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "import_local_btn") {
            FilledTonalButton(
                onClick = onImportLocalClick,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth().heightIn(min = minTouchSize()),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Import Local .litertlm (SAF)")
            }
        }

        if (models.isEmpty()) {
            item(key = "empty_models") {
                Text(
                    text = "No .litertlm models found in app storage or /data/local/tmp/llm/.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        } else {
            items(models, key = { it }) { path ->
                LocalModelRow(
                    path = path,
                    isActive = path == selectedPath,
                    canDelete = !path.startsWith("/data/local/tmp/"),
                    onActivate = { onSelectModel(path) },
                    onDelete = { onDeleteModel(path) },
                )
            }
        }

        item(key = "adb_push_hint") {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = adbSnippet,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onCopySnippet(adbSnippet) }) { Text("Copy") }
                }
            }
        }
    }
}

@Composable
private fun LocalModelRow(
    path: String,
    isActive: Boolean,
    canDelete: Boolean,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
) {
    val fileName = path.substringAfterLast('/')
    val sizeText = remember(path) {
        val len = runCatching { File(path).takeIf { it.isFile }?.length() ?: 0L }.getOrDefault(0L)
        if (len > 0L) formatFileSize(len) else "External / Unverified"
    }
    val touchSize = minTouchSize()

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (isActive) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isActive) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary,
                        ) {
                            Text(
                                text = "Active",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    text = "$sizeText · ${path.substringBeforeLast('/')}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!isActive) {
                OutlinedButton(
                    onClick = onActivate,
                    modifier = Modifier
                        .sizeIn(minHeight = touchSize)
                        .semantics { contentDescription = "Activate $fileName" },
                ) {
                    Text("Activate")
                }
            } else {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "$fileName active",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            if (canDelete) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.sizeIn(minWidth = touchSize, minHeight = touchSize),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete $fileName")
                }
            }
        }
    }
}

@Composable
private fun HuggingFaceHubTab(
    catalogModels: List<HfModelEntry>,
    searchResults: List<HfModelEntry>,
    isSearching: Boolean,
    downloadState: ModelDownloadState,
    onSearchRepo: (String, String) -> Unit,
    onDownload: (HfModelEntry, String) -> Unit,
    onCancelDownload: () -> Unit,
) {
    var repoQuery by rememberSaveable { mutableStateOf("litert-community/gemma-4-E2B-it-litert-lm") }
    var hfToken by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "hf_repo_explorer") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = repoQuery,
                    onValueChange = { repoQuery = it },
                    label = { Text("Custom HF Repo (owner/repo)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = hfToken,
                        onValueChange = { hfToken = it },
                        label = { Text("HF Token (optional)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { onSearchRepo(repoQuery, hfToken) },
                        enabled = !isSearching,
                        modifier = Modifier.heightIn(min = minTouchSize()),
                    ) {
                        if (isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Search, contentDescription = "Search HuggingFace repo")
                        }
                    }
                }
            }
        }

        if (searchResults.isNotEmpty()) {
            item(key = "search_header") {
                Text("Repository Files", style = MaterialTheme.typography.titleSmall)
            }
            items(searchResults, key = { "search_${it.repoId}_${it.fileName}" }) { entry ->
                HfModelCard(
                    entry = entry,
                    downloadState = downloadState,
                    onDownload = { onDownload(entry, hfToken) },
                    onCancel = onCancelDownload,
                )
            }
            item(key = "search_divider") { HorizontalDivider() }
        }

        item(key = "catalog_header") {
            Text("Curated LiteRT-LM Models", style = MaterialTheme.typography.titleSmall)
        }
        items(catalogModels, key = { it.id }) { entry ->
            HfModelCard(
                entry = entry,
                downloadState = downloadState,
                onDownload = { onDownload(entry, hfToken) },
                onCancel = onCancelDownload,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HfModelCard(
    entry: HfModelEntry,
    downloadState: ModelDownloadState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val isThisDownloading = downloadState.isDownloading &&
        (downloadState.repoFileKey == "${entry.repoId}/${entry.fileName}" || downloadState.fileName == entry.fileName)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "${entry.repoId} · ${entry.sizeLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = entry.recommendedBackend.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                entry.tags.forEach { tag ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            if (isThisDownloading) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().heightIn(min = minTouchSize()),
                ) {
                    Text("Cancel ${entry.title}")
                }
            } else {
                Button(
                    onClick = onDownload,
                    enabled = !downloadState.isDownloading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = minTouchSize())
                        .semantics { contentDescription = "Download ${entry.title}" },
                ) {
                    Text("Download & Activate")
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    else -> formatMb(bytes)
}

private fun formatMb(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))

private fun resolveUriFileName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
