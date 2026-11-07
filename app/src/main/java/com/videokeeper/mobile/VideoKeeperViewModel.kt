package com.videokeeper.mobile

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class AppTab { IMPORT, CROP, DUPLICATES, QUEUE, SETTINGS }

data class QueueOverallProgress(
    val completedItems: Int,
    val totalItems: Int,
    val currentItemName: String?,
    val currentItemProgress: Int,
    val percent: Int
)

class VideoKeeperViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val analyzer = Analyzer(application)
    private val stopping = AtomicBoolean(false)
    private var operation: Job? = null
    private var operationToken = 0L
    private var operationKind: LongOperationKind? = null

    val videos = androidx.compose.runtime.mutableStateListOf<VideoEntry>()
    val duplicates = androidx.compose.runtime.mutableStateListOf<DuplicatePair>()
    val queue = androidx.compose.runtime.mutableStateListOf<QueueItem>()

    var activeTab = androidx.compose.runtime.mutableStateOf(AppTab.IMPORT)
        private set
    var sourceFolderUri = androidx.compose.runtime.mutableStateOf<Uri?>(null)
        private set
    var destinationFolderUri = androidx.compose.runtime.mutableStateOf<Uri?>(null)
        private set
    var sourceFolderName = androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    var destinationFolderName = androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    var busy = androidx.compose.runtime.mutableStateOf(false)
        private set
    var progress = androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var status = androidx.compose.runtime.mutableStateOf("請先選擇影片或資料夾")
        private set
    var quality = androidx.compose.runtime.mutableStateOf(ExportQuality.HIGH)
        private set
    var cacheCount = androidx.compose.runtime.mutableIntStateOf(0)
        private set

    private val queueReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == QueueStore.ACTION_QUEUE_UPDATED) reloadQueue()
        }
    }

    init {
        val preferences = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        quality.value = preferences.getString("quality", ExportQuality.HIGH.name)
            ?.let { runCatching { ExportQuality.valueOf(it) }.getOrNull() }
            ?: ExportQuality.HIGH
        sourceFolderUri.value = preferences.getString("sourceFolder", null)?.let(Uri::parse)
        destinationFolderUri.value = preferences.getString("destinationFolder", null)?.let(Uri::parse)
        sourceFolderName.value = preferences.getString("sourceName", null)
        destinationFolderName.value = preferences.getString("destinationName", null)
        reloadQueue()
        cacheCount.value = runCatching { analyzer.fingerprintCacheCount() }.getOrDefault(0)
        val filter = IntentFilter(QueueStore.ACTION_QUEUE_UPDATED)
        ContextCompat.registerReceiver(app, queueReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun selectTab(tab: AppTab) {
        activeTab.value = tab
        if (tab == AppTab.QUEUE) reloadQueue()
    }

    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        selectTab(AppTab.IMPORT)
        val distinct = uris.distinctBy { it.toString() }
        startOperation(LongOperationKind.IMPORT, "正在讀取 ${distinct.size} 支影片…") {
            distinct.forEachIndexed { index, uri ->
                if (stopping.get()) return@forEachIndexed
                persistReadGrant(uri)
                val entry = withContext(Dispatchers.IO) { makeEntry(uri, "") }
                val existing = videos.indexOfFirst { it.id == entry.id }
                if (existing < 0) videos.add(entry) else videos[existing] = entry
                progress.intValue = ((index + 1) * 100 / distinct.size).coerceIn(0, 100)
                status.value = "已加入 ${index + 1}/${distinct.size} 支：${entry.name}"
            }
            status.value = if (stopping.get()) {
                "讀取已取消，保留目前的 ${videos.size} 支影片"
            } else {
                "已加入 ${videos.size} 支影片；可開始分析黑邊或內容。"
            }
            progress.intValue = 100
        }
    }

    fun importFolder(uri: Uri, displayName: String?) {
        operation?.cancel()
        stopping.set(false)
        persistTreeGrant(uri)
        sourceFolderUri.value = uri
        sourceFolderName.value = displayName ?: "影片資料夾"
        destinationFolderUri.value = uri
        destinationFolderName.value = displayName ?: "影片資料夾"
        saveFolders()
        selectTab(AppTab.IMPORT)
        startOperation(LongOperationKind.IMPORT, "正在掃描資料夾與子資料夾…") {
            videos.clear()
            duplicates.clear()
            val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, uri)
            val files = withContext(Dispatchers.IO) {
                val result = mutableListOf<Pair<Uri, String>>()
                if (root != null) collectVideoFiles(root, "", result, 0)
                result
            }
            files.forEachIndexed { index, (fileUri, relativePath) ->
                if (stopping.get()) return@forEachIndexed
                persistReadGrant(fileUri)
                val entry = withContext(Dispatchers.IO) { makeEntry(fileUri, relativePath) }
                videos.add(entry)
                progress.intValue = if (files.isEmpty()) 100 else (index + 1) * 100 / files.size
                status.value = "掃描 ${index + 1}/${files.size}：${entry.name}"
            }
            progress.intValue = 100
            status.value = if (stopping.get()) "掃描已取消，保留已找到的 ${videos.size} 支影片"
            else "掃描完成：找到 ${videos.size} 支影片（包含子資料夾）"
        }
    }

    fun setDestinationFolder(uri: Uri, displayName: String?) {
        persistTreeGrant(uri)
        destinationFolderUri.value = uri
        destinationFolderName.value = displayName ?: "輸出資料夾"
        saveFolders()
        status.value = "輸出位置已設定：${destinationFolderName.value}"
    }

    fun analyzeCrop() {
        if (videos.isEmpty()) {
            status.value = "尚未加入影片"
            return
        }
        startOperation(LongOperationKind.CROP, "準備分析黑邊…") {
            var automatic = 0
            var review = 0
            var failed = 0
            val snapshot = videos.toList()
            snapshot.forEachIndexed { index, original ->
                if (stopping.get()) return@forEachIndexed
                replace(original.copy(cropState = AnalysisState.RUNNING, error = null))
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val withInfo = original.copy(info = original.info ?: analyzer.readInfo(original))
                        val decision = analyzer.detectCrop(
                            withInfo,
                            isCancelled = { stopping.get() }
                        )
                        withInfo to decision
                    }
                }
                result.fold(
                    onSuccess = { (withInfo, decision) ->
                        if (decision.kind == CropKind.AUTO) automatic++
                        if (decision.kind == CropKind.REVIEW) review++
                        replace(
                            withInfo.copy(
                                crop = decision,
                                suggestedCrop = decision,
                                cropState = when (decision.kind) {
                                    CropKind.REVIEW -> AnalysisState.NEEDS_REVIEW
                                    CropKind.UNSUPPORTED -> AnalysisState.UNSUPPORTED
                                    else -> AnalysisState.READY
                                }
                            )
                        )
                    },
                    onFailure = { error ->
                        failed++
                        replace(original.copy(
                            cropState = AnalysisState.FAILED,
                            crop = CropDecision(kind = CropKind.REVIEW, note = "無法分析：${error.message ?: "檔案可能損壞"}"),
                            error = error.message
                        ))
                    }
                )
                progress.intValue = (index + 1) * 100 / snapshot.size
                status.value = "黑邊分析 ${index + 1}/${snapshot.size}　自動 $automatic・待確認 $review・失敗 $failed"
            }
            status.value = if (stopping.get()) "黑邊分析已取消，保留已完成結果"
            else "黑邊分析完成：自動 $automatic 支、待確認 $review 支、失敗 $failed 支"
        }
    }

    fun unselectedMoveCount(): Int = destinationFolderUri.value?.let { destination ->
        moveCandidates(destination).size
    } ?: 0

    fun moveUnselectedToDestination() {
        val destination = destinationFolderUri.value ?: run {
            status.value = "請先選擇輸出資料夾"
            return
        }
        val candidates = moveCandidates(destination)
        if (candidates.isEmpty()) {
            status.value = "沒有需要移動的未勾選影片"
            return
        }
        startOperation(LongOperationKind.MOVE, "準備移動 ${candidates.size} 支未勾選影片…") {
            var moved = 0
            var failed = 0
            candidates.forEachIndexed { index, original ->
                if (stopping.get()) return@forEachIndexed
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        SafFileOperations.move(
                            context = app,
                            source = original.uri,
                            destinationTree = destination,
                            sourceTree = sourceFolderUri.value
                        )
                    }
                }
                result.fold(
                    onSuccess = { movedUri ->
                        persistReadGrant(movedUri)
                        runCatching { analyzer.removeFingerprint(original.uri) }
                        val movedEntry = original.copy(
                            uri = movedUri,
                            relativePath = original.name,
                            error = null
                        )
                        replace(movedEntry)
                        replaceDuplicateReferences(original.id, movedEntry)
                        moved++
                    },
                    onFailure = { error ->
                        failed++
                        replace(original.copy(error = "移動失敗：${error.message ?: "儲存空間拒絕操作"}"))
                    }
                )
                progress.intValue = (index + 1) * 100 / candidates.size
                status.value = "移動未勾選影片 ${index + 1}/${candidates.size}：成功 $moved・失敗 $failed"
            }
            status.value = if (stopping.get()) {
                "移動已取消：已完成 $moved 支、失敗 $failed 支"
            } else {
                "移動完成：成功 $moved 支、失敗 $failed 支"
            }
        }
    }

    fun analyzeDuplicates() {
        if (videos.size < 2) {
            status.value = "至少加入兩支影片才能比對"
            return
        }
        startOperation(LongOperationKind.DUPLICATES, "準備建立內容指紋…") {
            val snapshot = videos.toList()
            val candidates = mutableListOf<Triple<VideoEntry, VideoEntry, SimilarityResult>>()
            snapshot.forEachIndexed { index, original ->
                if (stopping.get()) return@forEachIndexed
                replace(original.copy(fingerprintState = AnalysisState.RUNNING, error = null))
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val withInfo = original.copy(info = original.info ?: analyzer.readInfo(original))
                        val fingerprint = analyzer.fingerprint(withInfo, isCancelled = { stopping.get() })
                        withInfo to fingerprint
                    }
                }
                result.fold(
                    onSuccess = { (withInfo, fingerprint) ->
                        replace(withInfo.copy(
                            fingerprint = fingerprint,
                            fingerprintState = if (fingerprint.size >= 5) AnalysisState.READY else AnalysisState.FAILED,
                            error = if (fingerprint.size >= 5) null else "有效畫格不足，無法分析"
                        ))
                    },
                    onFailure = { error ->
                        replace(original.copy(
                            fingerprint = emptyList(),
                            fingerprintState = AnalysisState.FAILED,
                            error = error.message ?: "影片無法解碼"
                        ))
                    }
                )
                progress.intValue = (index + 1) * 55 / snapshot.size
                status.value = "建立內容指紋 ${index + 1}/${snapshot.size}（低解析度抽樣）"
            }
            val analysed = videos.toList()
            for (i in analysed.indices) {
                val first = analysed[i]
                if (first.fingerprint.size < 5) continue
                for (j in i + 1 until analysed.size) {
                    if (stopping.get()) break
                    val second = analysed[j]
                    if (second.fingerprint.size < 5 ||
                        !HashMath.isDurationComparable(first.durationMs, second.durationMs)) continue
                    val result = HashMath.compare(first.fingerprint, second.fingerprint) ?: continue
                    if (result.score >= 68) candidates += Triple(first, second, result)
                }
            }
            duplicates.clear()
            candidates.forEachIndexed { index, (first, second, fingerprintResult) ->
                if (stopping.get()) return@forEachIndexed
                val verified = withContext(Dispatchers.IO) {
                    analyzer.verifyPair(first, second, isCancelled = { stopping.get() })
                } ?: return@forEachIndexed
                if (verified.score < 72 || verified.matchedFrames < 4) return@forEachIndexed
                duplicates += DuplicatePair(
                    first = first,
                    second = second,
                    score = fingerprintResult.score,
                    verifiedScore = verified.score,
                    offsetSamples = verified.offsetSamples,
                    highConfidence = fingerprintResult.score >= 86 && verified.score >= 82,
                    note = if (verified.offsetSamples == 0) "多段畫面在相同時間位置重疊"
                    else "已用有限時間偏移對齊片頭／片尾後驗證"
                )
                progress.intValue = 55 + ((index + 1) * 45 / candidates.size.coerceAtLeast(1))
                status.value = "第二階段畫面驗證 ${index + 1}/${candidates.size}"
            }
            duplicates.sortWith(compareByDescending<DuplicatePair> { it.highConfidence }.thenByDescending { it.verifiedScore })
            status.value = if (stopping.get()) "去重分析已取消，保留已完成候選"
            else "內容分析完成：找到 ${duplicates.size} 組經過畫面驗證的候選"
        }
    }

    /** Deletes only after the user confirms a duplicate candidate in the UI. */
    fun deleteDuplicateVideo(entry: VideoEntry) {
        if (busy.value) {
            status.value = "分析進行中，請完成或取消分析後再刪除"
            return
        }
        val uriString = entry.uri.toString()
        if (QueueStore.load(app).any {
                it.uriString == uriString &&
                    (it.state == QueueState.QUEUED || it.state == QueueState.RUNNING)
            }
        ) {
            status.value = "${entry.name} 正在工作佇列中，完成後才能刪除"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                check(DocumentsContract.deleteDocument(app.contentResolver, entry.uri)) {
                    "儲存空間拒絕刪除"
                }
            }
            if (result.isSuccess) {
                runCatching { analyzer.removeFingerprint(entry.uri) }
                val remainingCache = runCatching { analyzer.fingerprintCacheCount() }.getOrDefault(cacheCount.intValue)
                withContext(Dispatchers.Main) {
                    videos.removeAll { it.id == entry.id }
                    duplicates.removeAll { pair ->
                        pair.first.id == entry.id || pair.second.id == entry.id
                    }
                    cacheCount.intValue = remainingCache
                    status.value = "已刪除 ${entry.name}；相關相似候選已移除"
                }
            } else {
                withContext(Dispatchers.Main) {
                    val reason = if (result.exceptionOrNull() is SecurityException) {
                        "尚未取得此檔案的刪除權限"
                    } else {
                        "儲存空間拒絕刪除或檔案已不存在"
                    }
                    status.value = "刪除失敗：$reason；可改用資料夾匯入後再試"
                }
            }
        }
    }

    fun toggleCropSelection(id: String, selected: Boolean) {
        find(id)?.let { replace(it.copy(crop = it.crop.withSelection(selected))) }
    }

    fun applyManualCrop(id: String, left: Float, right: Float) {
        find(id)?.let { entry ->
            val safeLeft = CropMath.clampManualCropSide(left, right)
            val safeRight = CropMath.clampManualCropSide(right, safeLeft)
            val crop = CropDecision(
                left = safeLeft,
                right = safeRight,
                kind = CropKind.REVIEW,
                note = "已人工調整，輸出前請再次預覽",
                confidence = 100,
                selected = safeLeft + safeRight > 0.015f
            )
            replace(entry.copy(crop = crop, cropState = AnalysisState.NEEDS_REVIEW))
        }
    }

    fun resetCrop(id: String) {
        find(id)?.let { entry -> entry.suggestedCrop?.let { replace(entry.copy(crop = it)) } }
    }

    fun enqueueSelected(): Boolean {
        val destination = destinationFolderUri.value ?: run {
            status.value = "請先選擇輸出資料夾"
            return false
        }
        val selected = videos.filter { it.crop.selected && it.crop.cropped && it.cropState != AnalysisState.PENDING }
        if (selected.isEmpty()) {
            status.value = "請先完成黑邊分析，並勾選至少一支可裁切影片"
            return false
        }
        val invalid = selected.firstOrNull {
            it.cropState == AnalysisState.FAILED || it.crop.kind == CropKind.UNSUPPORTED ||
                it.width <= 0 || it.height <= 0
        }
        if (invalid != null) {
            status.value = "${invalid.name} 尚未有可用的影片資訊"
            return false
        }
        val newItems = selected.map { entry ->
            QueueItem(
                id = UUID.randomUUID().toString(),
                uriString = entry.uri.toString(),
                name = entry.name,
                destinationUriString = destination.toString(),
                left = entry.crop.left,
                right = entry.crop.right,
                sourceDurationMs = entry.durationMs,
                sourceWidth = entry.width,
                sourceHeight = entry.height
            )
        }
        queue.addAll(newItems)
        QueueStore.save(app, queue.toList())
        val startError = QueueStore.start(app)
        if (startError != null) {
            val reason = "無法啟動本機工作佇列：${startError.message ?: "Android 未允許背景處理"}"
            newItems.forEach { item ->
                val index = queue.indexOfFirst { it.id == item.id }
                if (index >= 0) queue[index] = item.copy(state = QueueState.FAILED, error = reason)
            }
            QueueStore.save(app, queue.toList())
            selectTab(AppTab.QUEUE)
            status.value = reason
            return false
        }
        selectTab(AppTab.QUEUE)
        status.value = "已加入 ${newItems.size} 支影片；正在啟動本機工作佇列"
        return true
    }

    fun cancelQueue() {
        if (!QueueStore.hasActiveQueue(app)) {
            status.value = "目前沒有進行中的工作"
            reloadQueue()
            return
        }
        val error = QueueStore.cancel(app)
        status.value = if (error == null) {
            "已送出取消要求；正在停止目前工作並清除不完整輸出"
        } else {
            "無法取消本機工作：${error.message ?: "Android 未允許背景處理"}"
        }
    }

    fun clearQueue() {
        if (QueueStore.hasActiveQueue(app)) {
            status.value = "目前仍有工作進行中，請先取消工作"
            return
        }
        QueueStore.clearQueue(app)
        queue.clear()
        NotificationHelper.cancelProgress(app)
        status.value = "已清除佇列紀錄；來源與輸出影片未被刪除"
    }

    fun croppedSourceDeleteCount(): Int = queue.count { isCroppedSuccessfulItem(it) && !it.sourceDeleted }

    fun croppedSourceNames(): List<String> = queue
        .filter { isCroppedSuccessfulItem(it) && !it.sourceDeleted }
        .map { it.name }

    fun deleteCroppedSourceVideos() {
        val candidates = queue.filter { isCroppedSuccessfulItem(it) && !it.sourceDeleted }
        if (candidates.isEmpty()) {
            status.value = "沒有可刪除的已裁切原影片"
            return
        }
        startOperation(LongOperationKind.DELETE, "準備刪除 ${candidates.size} 支已裁切原影片…") {
            var deleted = 0
            var failed = 0
            candidates.forEachIndexed { index, item ->
                if (stopping.get()) return@forEachIndexed
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        check(DocumentsContract.deleteDocument(app.contentResolver, Uri.parse(item.uriString))) {
                            "儲存空間拒絕刪除"
                        }
                    }
                }
                val updated = result.fold(
                    onSuccess = {
                        deleted++
                        item.copy(sourceDeleted = true, sourceDeleteError = null, updatedAt = System.currentTimeMillis())
                    },
                    onFailure = { error ->
                        failed++
                        item.copy(
                            sourceDeleteError = sourceDeleteReason(error),
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                )
                replaceQueueItem(updated)
                QueueStore.update(app, updated)
                progress.intValue = (index + 1) * 100 / candidates.size
                status.value = "刪除已裁切原影片 ${index + 1}/${candidates.size}：成功 $deleted・失敗 $failed"
            }
            status.value = if (stopping.get()) {
                "刪除已取消：成功 $deleted 支、失敗 $failed 支"
            } else {
                "刪除完成：成功 $deleted 支、失敗 $failed 支"
            }
        }
    }

    fun setQuality(value: ExportQuality) {
        quality.value = value
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("quality", value.name).apply()
    }

    fun clearFingerprintCache() {
        viewModelScope.launch(Dispatchers.IO) {
            analyzer.clearFingerprintCache()
            withContext(Dispatchers.Main) {
                cacheCount.intValue = 0
                status.value = "已清除本機內容指紋快取；原始影片未被修改"
            }
        }
    }

    fun writeCsv(uri: Uri) {
        val snapshot = duplicates.toList()
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                app.contentResolver.openOutputStream(uri, "w")!!.use { stream ->
                    OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                        writer.write("\uFEFF相似等級,指紋相似度,畫面驗證,時間偏移,檔案A,檔案B,A解析度,B解析度,A片長,B片長,A碼率,B碼率\r\n")
                        snapshot.forEach { pair ->
                            val values = listOf(
                                if (pair.highConfidence) "高度相似" else "待人工確認",
                                pair.score.toString(), pair.verifiedScore.toString(), pair.offsetSamples.toString(),
                                pair.first.relativePath, pair.second.relativePath,
                                "${pair.first.width}x${pair.first.height}", "${pair.second.width}x${pair.second.height}",
                                pair.first.durationMs.toString(), pair.second.durationMs.toString(),
                                pair.first.info?.bitrate?.toString() ?: "", pair.second.info?.bitrate?.toString() ?: ""
                            )
                            writer.write(values.joinToString(",") { quoteCsv(it) })
                            writer.write("\r\n")
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                status.value = result.fold(
                    onSuccess = { "CSV 報告已儲存" },
                    onFailure = { "CSV 儲存失敗：${it.message ?: "無法寫入"}" }
                )
            }
        }
    }

    fun cancelAnalysis() {
        val kind = operationKind
        if (kind != null) {
            operationToken++
            stopping.set(true)
            operation?.cancel()
            operation = null
            QueueStore.stopProcessingSession(app)
            NotificationHelper.cancelProgress(app)
            operationKind = null
        }
        stopping.set(true)
        operation?.cancel()
        operation = null
        busy.value = false
        status.value = when (kind) {
            LongOperationKind.IMPORT -> "已取消匯入，保留目前已完成的結果"
            LongOperationKind.MOVE -> "已取消移動，保留目前已完成的結果"
            LongOperationKind.DELETE -> "已取消刪除，保留目前已完成的結果"
            else -> "已取消分析，保留目前已完成的結果"
        }
    }

    fun reloadQueue() {
        queue.clear()
        queue.addAll(QueueStore.load(app))
    }

    /**
     * Returns progress only while a queue is actually active. Completed queue
     * history must not leave a permanent 100% bar at the top of the app.
     * The persisted batch IDs keep this view correct when the Activity is
     * recreated from the foreground-service notification.
     */
    fun queueOverallProgress(): QueueOverallProgress? {
        val snapshot = queue.toList()
        if (snapshot.isEmpty()) return null
        val activeIds = QueueStore.activeBatchIds(app)
        val persistedBatch = snapshot.filter { it.id in activeIds }
        val batch = if (persistedBatch.any { it.isQueueActive() }) {
            persistedBatch
        } else {
            snapshot.filter { it.isQueueActive() }
        }
        if (batch.isEmpty()) return null
        val completed = batch.count { it.isQueueTerminal() }
        val current = batch.firstOrNull { it.state == QueueState.RUNNING }
        val currentProgress = current?.progress?.coerceIn(0, 100) ?: 0
        return QueueOverallProgress(
            completedItems = completed,
            totalItems = batch.size,
            currentItemName = current?.name,
            currentItemProgress = currentProgress,
            percent = aggregateQueueProgress(batch.size, completed, currentProgress)
        )
    }

    private fun QueueItem.isQueueActive(): Boolean =
        state == QueueState.QUEUED || state == QueueState.RUNNING

    private fun QueueItem.isQueueTerminal(): Boolean =
        state == QueueState.SUCCEEDED ||
            state == QueueState.FAILED ||
            state == QueueState.CANCELLED

    fun hardwareSummary(): String {
        val encoders = HardwareCodecInspector.hardwareEncoderNames(HardwareCodecInspector.H264)
        val decoders = HardwareCodecInspector.hardwareDecoderNames(HardwareCodecInspector.H264)
        val audio = HardwareCodecInspector.hardwareEncoderNames(HardwareCodecInspector.AAC)
        return "H.264 硬體編碼器 ${encoders.size} 個、解碼器 ${decoders.size} 個；AAC 硬體編碼器 ${audio.size} 個（音訊優先複製，必要時可軟編碼）"
    }

    private fun startOperation(
        kind: LongOperationKind,
        initial: String,
        block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit
    ) {
        operation?.cancel()
        operationToken++
        val token = operationToken
        stopping.set(false)
        operationKind = kind
        val sessionStarted = !QueueStore.hasActiveQueue(app) &&
            QueueStore.startProcessingSession(app, kind.label) == null
        operation = viewModelScope.launch {
            busy.value = true
            progress.intValue = 0
            status.value = initial
            NotificationHelper.showProgress(app, kind, initial, 0)
            val notificationTicker = launch {
                while (isActive) {
                    delay(700L)
                    if (token != operationToken) break
                    NotificationHelper.showProgress(app, kind, status.value, progress.intValue)
                }
            }
            try {
                block()
            } catch (cancelled: CancellationException) {
                if (token == operationToken) {
                    status.value = if (stopping.get()) "已取消目前工作，已保留已完成結果" else "工作被中斷"
                }
                throw cancelled
            } catch (error: Throwable) {
                if (token == operationToken) {
                    status.value = "${kind.label}失敗：${error.message ?: "未知錯誤"}"
                }
            } finally {
                notificationTicker.cancel()
                if (token == operationToken) {
                    NotificationHelper.cancelProgress(app)
                    if (sessionStarted) QueueStore.stopProcessingSession(app)
                    busy.value = false
                    operation = null
                    operationKind = null
                }
            }
        }
    }

    private fun replace(entry: VideoEntry) {
        val index = videos.indexOfFirst { it.id == entry.id }
        if (index >= 0) videos[index] = entry
    }

    private fun replaceDuplicateReferences(id: String, updated: VideoEntry) {
        if (duplicates.isEmpty()) return
        val replaced = duplicates.map { pair ->
            pair.copy(
                first = if (pair.first.id == id) updated else pair.first,
                second = if (pair.second.id == id) updated else pair.second
            )
        }
        duplicates.clear()
        duplicates.addAll(replaced)
    }

    private fun replaceQueueItem(item: QueueItem) {
        val index = queue.indexOfFirst { it.id == item.id }
        if (index >= 0) queue[index] = item else queue += item
    }

    private fun moveCandidates(destination: Uri): List<VideoEntry> = videos.filter { entry ->
        !entry.crop.selected &&
            entry.cropState != AnalysisState.PENDING &&
            entry.cropState != AnalysisState.RUNNING &&
            !sourceAndDestinationAreSameFolder(entry.uri, destination)
    }

    private fun isCroppedSuccessfulItem(item: QueueItem): Boolean =
        item.state == QueueState.SUCCEEDED &&
            !item.outputUriString.isNullOrBlank() &&
            item.left + item.right > 0.015f

    private fun sourceDeleteReason(error: Throwable): String = if (error is SecurityException) {
        "沒有刪除權限，請重新授予來源資料夾權限"
    } else {
        "儲存空間拒絕刪除或檔案已不存在"
    }

    private fun find(id: String): VideoEntry? = videos.firstOrNull { it.id == id }

    private fun makeEntry(uri: Uri, relativePath: String): VideoEntry {
        val metadata = queryMetadata(uri)
        val name = metadata.first ?: androidx.documentfile.provider.DocumentFile.fromSingleUri(app, uri)?.name
            ?: "未命名影片"
        val bytes = metadata.second
        val modified = metadata.third
        val info = runCatching { analyzer.readInfo(uri) }.getOrNull()
        return VideoEntry(
            id = uri.toString(),
            uri = uri,
            name = name,
            relativePath = if (relativePath.isBlank()) name else relativePath,
            bytes = bytes,
            modified = modified,
            info = info,
            selected = true,
            error = if (info == null) "無法讀取影片資訊" else null
        )
    }

    private fun queryMetadata(uri: Uri): Triple<String?, Long, Long> {
        var name: String? = null
        var size = -1L
        var modified = 0L
        app.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) modified = cursor.getLong(modifiedIndex)
            }
        }
        return Triple(name, size, modified)
    }

    private fun collectVideoFiles(
        directory: androidx.documentfile.provider.DocumentFile,
        relative: String,
        result: MutableList<Pair<Uri, String>>,
        depth: Int
    ) {
        if (depth > 16 || result.size >= 10_000 || stopping.get()) return
        directory.listFiles().sortedBy { it.name?.lowercase(Locale.ROOT) }.forEach { file ->
            if (stopping.get()) return@forEach
            if (file.isDirectory) {
                if (file.name != "裁切結果") collectVideoFiles(file, relative + (file.name ?: "資料夾") + "/", result, depth + 1)
            } else if (file.isFile) {
                val name = file.name ?: return@forEach
                val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (file.type?.startsWith("video/") == true || extension in VIDEO_EXTENSIONS) {
                    result += file.uri to (relative + name)
                }
            }
        }
    }

    private fun persistReadGrant(uri: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.recoverCatching {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun persistTreeGrant(uri: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    private fun saveFolders() {
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("sourceFolder", sourceFolderUri.value?.toString())
            .putString("destinationFolder", destinationFolderUri.value?.toString())
            .putString("sourceName", sourceFolderName.value)
            .putString("destinationName", destinationFolderName.value)
            .apply()
    }

    private fun quoteCsv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    override fun onCleared() {
        stopping.set(true)
        operation?.cancel()
        runCatching { app.unregisterReceiver(queueReceiver) }
        super.onCleared()
    }

    companion object {
        val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "m4v", "webm", "avi", "3gp", "ts")
    }
}
