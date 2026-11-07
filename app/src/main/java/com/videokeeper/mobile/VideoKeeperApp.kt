package com.videokeeper.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreTime
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoKeeperApp(vm: VideoKeeperViewModel = viewModel()) {
    val context = LocalContext.current
    val activeTab by vm.activeTab
    var pendingNotificationAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val destinationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val name = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name
            vm.setDestinationFolder(uri, name)
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> if (uri != null) vm.writeCsv(uri) }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Start the user-requested operation only after the permission dialog is
        // gone. Android 13+ can reject an FGS start while that dialog is visible.
        val action = pendingNotificationAction
        pendingNotificationAction = null
        action?.invoke()
    }

    fun runWithNotificationPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotificationAction = action
            runCatching {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }.onFailure {
                pendingNotificationAction = null
                action()
            }
        } else {
            action()
        }
    }

    fun startExport() = runWithNotificationPermission { vm.enqueueSelected() }

    val multipleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            runWithNotificationPermission { vm.importFiles(uris) }
        }
    }
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val name = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name
            runWithNotificationPermission { vm.importFolder(uri, name) }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = VideoKeeperPalette.background,
        topBar = {
            Column(Modifier.background(VideoKeeperPalette.background)) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(R.drawable.ic_launcher_foreground),
                                contentDescription = "影片管家標誌",
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(Color(0xFF17384B))
                                    .padding(6.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("影片管家", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                                Text("VideoKeeper · 本機處理", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    actions = {
                        AssistChip(
                            onClick = { },
                            label = { Text("離線", fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Outlined.CheckCircle, null, Modifier.size(15.dp)) }
                        )
                        Spacer(Modifier.width(10.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = VideoKeeperPalette.background)
                )
                if (vm.busy.value) {
                    LinearProgressIndicator(
                        progress = { vm.progress.intValue / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = VideoKeeperPalette.accent,
                        trackColor = VideoKeeperPalette.cardAlt
                    )
                }
                Text(
                    vm.status.value,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        bottomBar = {
            BottomAppBar(
                containerColor = VideoKeeperPalette.card,
                modifier = Modifier.navigationBarsPadding()
            ) {
                val items = listOf(
                    Triple(AppTab.IMPORT, "匯入", Icons.Outlined.VideoLibrary),
                    Triple(AppTab.CROP, "裁切", Icons.Outlined.Analytics),
                    Triple(AppTab.DUPLICATES, "去重", Icons.Outlined.ContentCopy),
                    Triple(AppTab.QUEUE, "佇列", Icons.Outlined.MoreTime),
                    Triple(AppTab.SETTINGS, "設定", Icons.Outlined.Settings)
                )
                items.forEach { (tab, label, icon) ->
                    NavigationBarItem(
                        selected = activeTab == tab,
                        onClick = { vm.selectTab(tab) },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, fontSize = 10.sp) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (activeTab) {
                AppTab.IMPORT -> ImportScreen(vm, multipleLauncher, folderLauncher, destinationLauncher)
                AppTab.CROP -> CropScreen(
                    vm = vm,
                    startExport = { startExport() },
                    startAnalysis = { runWithNotificationPermission(vm::analyzeCrop) },
                    moveUnselected = { runWithNotificationPermission(vm::moveUnselectedToDestination) }
                )
                AppTab.DUPLICATES -> DuplicateScreen(
                    vm = vm,
                    csvLauncher = csvLauncher,
                    startAnalysis = { runWithNotificationPermission(vm::analyzeDuplicates) }
                )
                AppTab.QUEUE -> QueueScreen(vm)
                AppTab.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
}

@Composable
private fun ImportScreen(
    vm: VideoKeeperViewModel,
    multipleLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    folderLauncher: androidx.activity.result.ActivityResultLauncher<android.net.Uri?>,
    destinationLauncher: androidx.activity.result.ActivityResultLauncher<android.net.Uri?>
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            HeroCard(
                title = "把影片交給管家",
                body = "選取多部影片或整個資料夾。影片只在手機內處理；匯出不修改來源，移動或刪除只在你確認後執行。"
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        text = "選取多部影片",
                        onClick = { multipleLauncher.launch(arrayOf("video/*")) },
                        modifier = Modifier.weight(1f)
                    )
                    SecondaryButton(
                        text = "選取資料夾",
                        onClick = { folderLauncher.launch(null) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        item {
            FolderCard(
                title = "來源資料夾",
                name = vm.sourceFolderName.value ?: "尚未選取（也可直接選取多部影片）",
                icon = Icons.Outlined.FolderOpen
            )
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = VideoKeeperPalette.card),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Storage, null, tint = VideoKeeperPalette.accent)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text("輸出位置", fontWeight = FontWeight.Bold)
                            Text(
                                vm.destinationFolderName.value ?: "請選擇輸出資料夾",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        SecondaryButton("選擇", { destinationLauncher.launch(null) })
                    }
                    Text(
                        "輸出會建立為新的 MP4，不會覆蓋或刪除來源；轉檔失敗時會清除不完整檔案。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (vm.videos.isEmpty()) {
            item { EmptyCard("尚未加入影片", "從上方選取影片後，這裡會顯示縮圖、解析度、片長、編碼與分析狀態。") }
        } else {
            item {
                SectionHeader("影片清單", "${vm.videos.size} 支 · 可切換到底部的裁切或去重工具")
            }
            items(vm.videos, key = { it.id }) { entry -> VideoInfoCard(entry) }
        }
    }
}

@Composable
private fun CropScreen(
    vm: VideoKeeperViewModel,
    startExport: () -> Unit,
    startAnalysis: () -> Unit,
    moveUnselected: () -> Unit
) {
    var editorEntry by remember { mutableStateOf<VideoEntry?>(null) }
    val selectedCount = vm.videos.count { it.crop.selected && it.crop.cropped }
    val moveCount = vm.unselectedMoveCount()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            ToolHeader(
                eyebrow = "TOOL 01 · OPENGL CROP",
                title = "智慧黑邊裁切",
                body = "抽樣多個時間點，以穩健統計確認左右固定黑邊；不確定的影片不會自動勾選。",
                actionText = if (vm.busy.value) "取消目前分析" else "分析全部影片",
                enabled = vm.busy.value || vm.videos.isNotEmpty(),
                onAction = if (vm.busy.value) vm::cancelAnalysis else startAnalysis
            )
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = VideoKeeperPalette.cardAlt)) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("已選取 $selectedCount 支可裁切影片", fontWeight = FontWeight.Bold, color = VideoKeeperPalette.accent)
                    Text(
                        "匯出前檢查硬體 H.264 影片路徑；音訊優先直接複製，必要時才以 AAC 軟體編碼，並驗證輸出完整性。未勾選影片可另行移動到輸出資料夾。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (vm.videos.isEmpty()) item { EmptyCard("先加入影片", "回到「匯入」頁選取影片或資料夾。") }
        else items(vm.videos, key = { it.id }) { entry ->
            CropEntryCard(
                entry = entry,
                onChecked = { vm.toggleCropSelection(entry.id, it) },
                onEdit = { editorEntry = entry }
            )
        }
        item {
            SecondaryButton(
                text = "移動未勾選影片到輸出資料夾（$moveCount 支）",
                enabled = !vm.busy.value && moveCount > 0 && vm.destinationFolderUri.value != null,
                onClick = moveUnselected,
                modifier = Modifier.fillMaxWidth()
            )
            PrimaryButton(
                text = "加入本機工作佇列（$selectedCount 支）",
                enabled = !vm.busy.value && selectedCount > 0 && vm.destinationFolderUri.value != null,
                onClick = startExport,
                modifier = Modifier.fillMaxWidth()
            )
            if (vm.destinationFolderUri.value == null) {
                Text("請先在「匯入」頁設定輸出資料夾。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    editorEntry?.let { entry ->
        CropEditorDialog(
            entry = entry,
            onDismiss = { editorEntry = null },
            onApply = { left, right ->
                vm.applyManualCrop(entry.id, left, right)
                editorEntry = null
            },
            onReset = { vm.resetCrop(entry.id) }
        )
    }
}

@Composable
private fun DuplicateScreen(
    vm: VideoKeeperViewModel,
    csvLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    startAnalysis: () -> Unit
) {
    val groups = remember(vm.duplicates.toList()) { buildDuplicateGroups(vm.duplicates.toList()) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            ToolHeader(
                eyebrow = "TOOL 02 · LOCAL FINGERPRINT",
                title = "依內容尋找相似影片",
                body = "先以多畫格指紋縮減候選，再以時間偏移與 SSIM 等效畫面比對驗證；相似度不是數學機率。",
                actionText = if (vm.busy.value) "取消目前分析" else "掃描全部影片",
                enabled = vm.busy.value || vm.videos.size >= 2,
                onAction = if (vm.busy.value) vm::cancelAnalysis else startAnalysis
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${vm.duplicates.size} 組已驗證候選", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                SecondaryButton(
                    text = "匯出 CSV",
                    enabled = vm.duplicates.isNotEmpty(),
                    onClick = { csvLauncher.launch("影片相似候選.csv") }
                )
            }
        }
        if (vm.duplicates.isEmpty()) {
            item { EmptyCard("等待內容比對", "加入至少兩支影片並開始掃描。未知編碼或有效畫格不足會明確標示，不能直接推論為沒有重複。") }
        } else {
            groups.forEachIndexed { index, group ->
                item(key = "group-$index") {
                    DuplicateGroupCard(
                        index = index + 1,
                        group = group,
                        deleteEnabled = !vm.busy.value,
                        onDelete = vm::deleteDuplicateVideo
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueScreen(vm: VideoKeeperViewModel) {
    val active = vm.queue.any { it.state == QueueState.RUNNING || it.state == QueueState.QUEUED }
    val deletableCount = vm.croppedSourceDeleteCount()
    var confirmDeleteSources by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            if (!active && deletableCount > 0) {
                SecondaryButton(
                    text = "一鍵刪除已裁切原影片（$deletableCount 支）",
                    enabled = !vm.busy.value,
                    onClick = { confirmDeleteSources = true },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "只會刪除已成功產生輸出的來源影片，不會刪除裁切結果；按下後仍會再次確認。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            ToolHeader(
                eyebrow = "WORK QUEUE · FOREGROUND SERVICE",
                title = "工作佇列",
                body = "循序使用 Media3 Transformer；畫面關閉或 Activity 重建後，狀態由 mediaProcessing 服務保留。",
                actionText = "取消目前工作",
                enabled = active,
                onAction = vm::cancelQueue,
                destructive = true
            )
        }
        if (vm.queue.isEmpty()) item { EmptyCard("佇列是空的", "在黑邊裁切頁完成分析、勾選影片後加入工作。") }
        else items(vm.queue, key = { it.id }) { QueueEntryCard(it) }
    }
    if (confirmDeleteSources && deletableCount > 0) {
        val names = vm.croppedSourceNames()
        AlertDialog(
            onDismissRequest = { confirmDeleteSources = false },
            title = { Text("刪除已裁切原影片？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("這會永久刪除 $deletableCount 支來源影片，只保留新的裁切輸出。")
                    names.take(10).forEach { Text("• $it", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (names.size > 10) Text("另有 ${names.size - 10} 支影片…", fontSize = 12.sp)
                    Text("此動作無法復原。若來源沒有 SAF 寫入權限，該支會保留並顯示失敗原因。", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteSources = false
                    vm.deleteCroppedSourceVideos()
                }) { Text("確認刪除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSources = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SettingsScreen(vm: VideoKeeperViewModel) {
    val hardware = remember { runCatching { vm.hardwareSummary() }.getOrDefault("無法取得硬體能力") }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            HeroCard("設定與裝置能力", "輸出前仍會針對每支來源檢查硬體影片路徑；沒有硬體影片路徑時會失敗並說明原因。音訊優先複製，必要時才使用 AAC 軟體編碼。") {}
        }
        item {
            SettingsCard(title = "輸出品質") {
                Text("預設 H.264 MP4；AAC 音訊優先直接複製，非相容音訊才轉 AAC。HEVC 不會因為檔案較小就強行啟用。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                    ExportQuality.values().forEach { option ->
                        FilterChip(
                            selected = vm.quality.value == option,
                            onClick = { vm.setQuality(option) },
                            label = { Text(qualityLabel(option), fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        item {
            SettingsCard(title = "硬體路徑") {
                Icon(Icons.Outlined.Memory, null, tint = VideoKeeperPalette.accent)
                Text(hardware, fontSize = 12.sp)
                Text("實際輸出仍會驗證 encoder 名稱、影片軌、音訊軌與片長。速度倍數只在真正完成後計算。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SettingsCard(title = "本機資料") {
                Text("Room 內容指紋快取：${vm.cacheCount.intValue} 筆", fontSize = 12.sp)
                SecondaryButton("清除指紋快取", vm::clearFingerprintCache)
                Text("清除只會移除本機索引，不會刪除或修改任何影片。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SettingsCard(title = "隱私與權限") {
                Text("✓ 使用 Android SAF / MediaStore 授權\n✓ 不要求 MANAGE_EXTERNAL_STORAGE\n✓ App 不宣告 INTERNET\n✓ 匯出不修改來源；去重刪除需逐次確認\n✓ 背景工作進度通知，完成後自動關閉（需允許通知權限）", fontSize = 12.sp, color = VideoKeeperPalette.safe)
            }
        }
    }
}

@Composable
private fun VideoInfoCard(entry: VideoEntry) {
    Card(colors = CardDefaults.cardColors(containerColor = VideoKeeperPalette.card), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            VideoThumbnail(entry, Modifier.size(92.dp, 62.dp))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.relativePath, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(videoMetadata(entry), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                StatusBadge(analysisLabel(entry), statusTone(entry))
            }
        }
    }
}

@Composable
private fun CropEntryCard(entry: VideoEntry, onChecked: (Boolean) -> Unit, onEdit: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = VideoKeeperPalette.card), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VideoThumbnail(entry, Modifier.size(90.dp, 60.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(videoMetadata(entry), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val crop = entry.crop
                    Text(
                        if (crop.sampleCount > 0) "左 ${percent(crop.left)} · 右 ${percent(crop.right)} · 信心 ${crop.confidence}%" else "尚未分析",
                        fontSize = 11.sp,
                        color = if (crop.kind == CropKind.AUTO) VideoKeeperPalette.safe else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Checkbox(checked = entry.crop.selected, onCheckedChange = onChecked, enabled = entry.crop.cropped)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(cropLabel(entry), cropTone(entry), Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                SecondaryButton("預覽／調整", onEdit, enabled = entry.info != null)
            }
            if (entry.crop.note.isNotBlank()) Text(entry.crop.note, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DuplicateGroupCard(
    index: Int,
    group: List<DuplicatePair>,
    deleteEnabled: Boolean,
    onDelete: (VideoEntry) -> Unit
) {
    val first = group.first()
    val members = buildList {
        add(first.first)
        add(first.second)
        group.drop(1).forEach { pair -> if (none { it.id == pair.first.id }) add(pair.first); if (none { it.id == pair.second.id }) add(pair.second) }
    }
    val memberKey = members.joinToString("|") { it.id }
    var pendingDelete by remember(index, memberKey) { mutableStateOf<VideoEntry?>(null) }
    val shorter = shorterDuplicateCandidate(members)
    val smaller = smallerDuplicateCandidate(members)
    Card(colors = CardDefaults.cardColors(containerColor = VideoKeeperPalette.card), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("候選群組 $index", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                StatusBadge(if (first.highConfidence) "高度相似" else "待人工確認", if (first.highConfidence) Tone.GOOD else Tone.WARN)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoThumbnail(first.first, Modifier.weight(1f).aspectRatio(1.45f))
                VideoThumbnail(first.second, Modifier.weight(1f).aspectRatio(1.45f))
            }
            members.forEach { member ->
                Text("• ${member.name}  ${videoMetadata(member)}", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "指紋 ${first.score}% · 畫面驗證 ${first.verifiedScore}% · ${first.note}",
                fontSize = 12.sp,
                color = VideoKeeperPalette.accent
            )
            val keep = betterQuality(first.first, first.second)
            Text("建議先保留：${keep.name}（依解析度、碼率、音訊與片長的品質排序；仍請人工播放確認）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("可依片長或檔案大小選擇刪除候選；每次都會先確認，且只刪除來源影片。", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(
                    text = "刪除較短者",
                    onClick = { shorter?.let { pendingDelete = it } },
                    enabled = deleteEnabled && shorter != null,
                    modifier = Modifier.weight(1f)
                )
                SecondaryButton(
                    text = "刪除較小者",
                    onClick = { smaller?.let { pendingDelete = it } },
                    enabled = deleteEnabled && smaller != null,
                    modifier = Modifier.weight(1f)
                )
            }
            if (shorter == null || smaller == null) {
                Text("片長或檔案大小不足以判斷時，刪除按鈕會停用。", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("確認刪除影片？") },
            text = {
                Text(
                    "將從手機刪除「${target.name}」。\n\n${videoMetadata(target)}\n此動作無法復原，原始影片不會移到垃圾桶。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(target)
                }) { Text("刪除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun QueueEntryCard(item: QueueItem) {
    Card(colors = CardDefaults.cardColors(containerColor = VideoKeeperPalette.card), shape = RoundedCornerShape(15.dp)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusBadge(queueLabel(item.state), queueTone(item.state))
            }
            if (item.state == QueueState.RUNNING || item.state == QueueState.QUEUED) {
                LinearProgressIndicator({ item.progress / 100f }, Modifier.fillMaxWidth(), color = VideoKeeperPalette.accent, trackColor = VideoKeeperPalette.cardAlt)
            }
            if (item.state == QueueState.SUCCEEDED) {
                Text("完成 ${formatDuration(item.elapsedMs)} · 實際處理速度 ${"%.2f".format(Locale.US, item.speed)}x", fontSize = 12.sp, color = VideoKeeperPalette.safe)
            }
            item.error?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
            if (item.sourceDeleted) {
                Text("原始影片已刪除，裁切輸出已保留", fontSize = 11.sp, color = VideoKeeperPalette.safe)
            }
            item.sourceDeleteError?.let { Text("原始影片刪除失敗：$it", fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
            Text("裁切左 ${percent(item.left)} · 右 ${percent(item.right)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CropEditorDialog(
    entry: VideoEntry,
    onDismiss: () -> Unit,
    onApply: (Float, Float) -> Unit,
    onReset: () -> Unit
) {
    var fraction by remember(entry.id) { mutableFloatStateOf(0.5f) }
    var left by remember(entry.id) { mutableFloatStateOf(entry.crop.left) }
    var right by remember(entry.id) { mutableFloatStateOf(entry.crop.right) }
    val bitmap = rememberVideoFrame(entry.uri, fraction)
    val cropped = remember(bitmap, left, right) { bitmap?.let { makeCroppedBitmap(it, left, right) } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(14.dp).heightIn(max = 720.dp),
            shape = RoundedCornerShape(22.dp),
            color = VideoKeeperPalette.card
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("裁切預覽與手動確認", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(entry.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("時間點 ${formatDuration((entry.durationMs * fraction).toLong())}", fontSize = 11.sp, color = VideoKeeperPalette.accent)
                Slider(value = fraction, onValueChange = { fraction = it }, valueRange = 0f..1f)
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("原始畫面與裁切框", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (bitmap != null) OriginalCropPreview(bitmap, left, right)
                        else PreviewPlaceholder()
                    }
                    Column(Modifier.weight(1f)) {
                        Text("裁切後畫面", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (cropped != null) AfterCropPreview(cropped) else PreviewPlaceholder()
                    }
                }
                Text("左側裁切 ${percent(left)}", fontSize = 12.sp)
                Slider(value = left, onValueChange = { left = minOf(it, 0.44f - right) }, valueRange = 0f..0.44f)
                Text("右側裁切 ${percent(right)}", fontSize = 12.sp)
                Slider(value = right, onValueChange = { right = minOf(it, 0.44f - left) }, valueRange = 0f..0.44f)
                Text(
                    "預估輸出：${CropMath.outputDimension(entry.width, left, right)} × ${entry.height - entry.height % 2}",
                    fontSize = 12.sp,
                    color = VideoKeeperPalette.accent
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        onReset()
                        entry.suggestedCrop?.let { left = it.left; right = it.right }
                    }) { Text("重設偵測值") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(onClick = { onApply(left, right) }) { Text("套用並勾選") }
                }
            }
        }
    }
}

@Composable
private fun VideoThumbnail(entry: VideoEntry, modifier: Modifier, fraction: Float = 0.5f) {
    val bitmap = rememberVideoFrame(entry.uri, fraction)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(VideoKeeperPalette.cardAlt),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = VideoKeeperPalette.accent)
        else Image(bitmap.asImageBitmap(), contentDescription = "${entry.name} 縮圖", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun rememberVideoFrame(uri: android.net.Uri, fraction: Float): Bitmap? {
    val context = LocalContext.current
    val analyzer = remember(context) { Analyzer(context) }
    val bitmap by produceState<Bitmap?>(initialValue = null, uri, fraction) {
        value = withContext(Dispatchers.IO) { runCatching { analyzer.preview(uri, fraction) }.getOrNull() }
    }
    DisposableEffect(bitmap) {
        val current = bitmap
        onDispose { if (current != null && !current.isRecycled) current.recycle() }
    }
    return bitmap
}

@Composable
private fun OriginalCropPreview(bitmap: Bitmap, left: Float, right: Float) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(1.05f).clip(RoundedCornerShape(10.dp)).background(VideoKeeperPalette.cardAlt)
    ) {
        Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Canvas(Modifier.fillMaxSize()) {
            val frameAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
            val boxAspect = size.width / size.height
            val imageWidth = if (boxAspect > frameAspect) size.height * frameAspect else size.width
            val imageHeight = if (boxAspect > frameAspect) size.height else size.width / frameAspect
            val originX = (size.width - imageWidth) / 2f
            val originY = (size.height - imageHeight) / 2f
            val cropLeft = originX + imageWidth * left
            val cropRight = originX + imageWidth * (1f - right)
            drawRect(
                color = VideoKeeperPalette.accent,
                topLeft = Offset(cropLeft, originY),
                size = Size(cropRight - cropLeft, imageHeight),
                style = Stroke(width = 3.dp.toPx())
            )
            drawLine(VideoKeeperPalette.accent, Offset(cropLeft, originY), Offset(cropLeft, originY + imageHeight), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
            drawLine(VideoKeeperPalette.accent, Offset(cropRight, originY), Offset(cropRight, originY + imageHeight), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        }
    }
}

@Composable
private fun AfterCropPreview(bitmap: Bitmap) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(1.05f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B3B50)),
        contentAlignment = Alignment.Center
    ) {
        Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize().padding(5.dp), contentScale = ContentScale.Fit)
    }
}

@Composable
private fun PreviewPlaceholder() {
    Box(Modifier.fillMaxWidth().aspectRatio(1.05f).background(VideoKeeperPalette.cardAlt), contentAlignment = Alignment.Center) {
        Text("讀取預覽中", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun makeCroppedBitmap(bitmap: Bitmap, left: Float, right: Float): Bitmap {
    val x = (bitmap.width * left).toInt().coerceIn(0, bitmap.width - 2)
    val end = (bitmap.width * (1f - right)).toInt().coerceIn(x + 2, bitmap.width)
    return Bitmap.createBitmap(bitmap, x, 0, end - x, bitmap.height)
}

@Composable
private fun HeroCard(title: String, body: String, content: @Composable ColumnScope.() -> Unit = {}) {
    Card(
        colors = CardDefaults.cardColors(containerColor = VideoKeeperPalette.card),
        shape = RoundedCornerShape(21.dp),
        modifier = Modifier.shadow(10.dp, RoundedCornerShape(21.dp), ambientColor = Color.Black.copy(alpha = .3f))
    ) {
        Column(Modifier.padding(19.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text("VIDEOKEEPER", fontSize = 10.sp, letterSpacing = 2.sp, color = VideoKeeperPalette.accent, fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(body, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
            content()
        }
    }
}

@Composable
private fun ToolHeader(
    eyebrow: String,
    title: String,
    body: String,
    actionText: String,
    enabled: Boolean,
    onAction: () -> Unit,
    destructive: Boolean = false
) {
    HeroCard(title, body) {
        Text(eyebrow, fontSize = 10.sp, letterSpacing = 1.5.sp, color = VideoKeeperPalette.accent, fontWeight = FontWeight.Bold)
        if (destructive) SecondaryButton(actionText, onAction, enabled, Modifier.fillMaxWidth())
        else PrimaryButton(actionText, onAction, enabled, Modifier.fillMaxWidth())
    }
}

@Composable
private fun FolderCard(title: String, name: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Card(colors = CardDefaults.cardColors(containerColor = VideoKeeperPalette.card), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = VideoKeeperPalette.accent)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = VideoKeeperPalette.card), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun SectionHeader(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyCard(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = VideoKeeperPalette.cardAlt), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(23.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(Icons.Outlined.PlayCircle, null, tint = VideoKeeperPalette.accent, modifier = Modifier.size(36.dp))
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 46.dp),
        colors = ButtonDefaults.buttonColors(containerColor = VideoKeeperPalette.accent, contentColor = Color(0xFF062433)),
        shape = RoundedCornerShape(12.dp)
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 42.dp),
        shape = RoundedCornerShape(11.dp),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = VideoKeeperPalette.accent)
    ) { Text(text, fontSize = 11.sp) }
}

private enum class Tone { GOOD, WARN, BAD, NEUTRAL }

@Composable
private fun StatusBadge(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val color = when (tone) {
        Tone.GOOD -> VideoKeeperPalette.safe
        Tone.WARN -> Color(0xFFF6D194)
        Tone.BAD -> MaterialTheme.colorScheme.error
        Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text,
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = .12f))
            .border(1.dp, color.copy(alpha = .45f), CircleShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        color = color,
        fontSize = 10.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

private fun analysisLabel(entry: VideoEntry): String = when (entry.cropState) {
    AnalysisState.PENDING -> "待分析黑邊"
    AnalysisState.RUNNING -> "分析中"
    AnalysisState.READY -> if (entry.crop.kind == CropKind.AUTO) "建議裁切" else "已分析"
    AnalysisState.NEEDS_REVIEW -> "需要人工確認"
    AnalysisState.FAILED -> entry.error ?: "分析失敗"
    AnalysisState.UNSUPPORTED -> "格式不支援"
}

private fun statusTone(entry: VideoEntry): Tone = when (entry.cropState) {
    AnalysisState.READY -> if (entry.crop.kind == CropKind.AUTO) Tone.GOOD else Tone.NEUTRAL
    AnalysisState.NEEDS_REVIEW -> Tone.WARN
    AnalysisState.FAILED, AnalysisState.UNSUPPORTED -> Tone.BAD
    else -> Tone.NEUTRAL
}

private fun cropLabel(entry: VideoEntry): String = when (entry.crop.kind) {
    CropKind.AUTO -> "自動建議 · 已安全勾選"
    CropKind.REVIEW -> "待人工確認 · 不會強制裁切"
    CropKind.NONE -> "未發現固定黑邊"
    CropKind.UNSUPPORTED -> "無法分析"
}

private fun cropTone(entry: VideoEntry): Tone = when (entry.crop.kind) {
    CropKind.AUTO -> Tone.GOOD
    CropKind.REVIEW -> Tone.WARN
    CropKind.UNSUPPORTED -> Tone.BAD
    CropKind.NONE -> Tone.NEUTRAL
}

private fun queueLabel(state: QueueState): String = when (state) {
    QueueState.QUEUED -> "等待中"
    QueueState.RUNNING -> "處理中"
    QueueState.SUCCEEDED -> "完成"
    QueueState.FAILED -> "失敗"
    QueueState.CANCELLED -> "已取消"
}

private fun queueTone(state: QueueState): Tone = when (state) {
    QueueState.SUCCEEDED -> Tone.GOOD
    QueueState.FAILED -> Tone.BAD
    QueueState.CANCELLED -> Tone.WARN
    else -> Tone.NEUTRAL
}

private fun videoMetadata(entry: VideoEntry): String {
    val info = entry.info ?: return formatBytes(entry.bytes)
    val resolution = if (info.displayWidth > 0) "${info.displayWidth}×${info.displayHeight}" else "尺寸未知"
    val fps = if (info.frameRate > 0) " · %.1f fps".format(Locale.US, info.frameRate) else ""
    return "$resolution · ${formatDuration(info.durationMs)}$fps · ${info.videoMime?.substringAfter('/') ?: "未知編碼"} · ${formatBytes(entry.bytes)}"
}

private fun qualityLabel(quality: ExportQuality): String = when (quality) {
    ExportQuality.STANDARD -> "標準"
    ExportQuality.HIGH -> "高品質"
    ExportQuality.MAXIMUM -> "最高"
}

private fun percent(value: Float): String = "%.1f%%".format(Locale.US, value * 100)

private fun formatDuration(value: Long): String {
    val total = (value / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}

private fun formatBytes(value: Long): String = when {
    value < 0 -> "大小未知"
    value >= 1_000_000_000 -> "%.2f GB".format(Locale.US, value / 1_000_000_000.0)
    value >= 1_000_000 -> "%.1f MB".format(Locale.US, value / 1_000_000.0)
    value >= 1_000 -> "%.1f KB".format(Locale.US, value / 1_000.0)
    else -> "$value B"
}

private fun betterQuality(a: VideoEntry, b: VideoEntry): VideoEntry {
    fun rank(entry: VideoEntry): Long {
        val info = entry.info
        return (info?.displayWidth ?: 0).toLong() * (info?.displayHeight ?: 0) * 100L +
            (info?.bitrate ?: 0).toLong() + if (info?.hasAudio == true) 1_000_000 else 0
    }
    return if (rank(a) >= rank(b)) a else b
}

private fun shorterDuplicateCandidate(members: List<VideoEntry>): VideoEntry? {
    val known = members.filter { it.durationMs > 0L }
    val shortest = known.minByOrNull { it.durationMs } ?: return null
    val longest = known.maxOfOrNull { it.durationMs } ?: return null
    return shortest.takeIf { shortest.durationMs < longest }
}

private fun smallerDuplicateCandidate(members: List<VideoEntry>): VideoEntry? {
    val known = members.filter { it.bytes > 0L }
    val smallest = known.minByOrNull { it.bytes } ?: return null
    val largest = known.maxOfOrNull { it.bytes } ?: return null
    return smallest.takeIf { smallest.bytes < largest }
}

private fun buildDuplicateGroups(pairs: List<DuplicatePair>): List<List<DuplicatePair>> {
    val groups = mutableListOf<MutableList<DuplicatePair>>()
    pairs.forEach { pair ->
        val matching = groups.filter { group -> group.any { it.first.id == pair.first.id || it.second.id == pair.first.id || it.first.id == pair.second.id || it.second.id == pair.second.id } }
        if (matching.isEmpty()) groups += mutableListOf(pair)
        else {
            val target = matching.first()
            target += pair
            matching.drop(1).forEach { other -> target += other; groups.remove(other) }
        }
    }
    return groups
}
