package com.videokeeper.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val stopping = AtomicBoolean(false)
    private val analyzer by lazy { Analyzer(this) }
    private val videos = mutableListOf<VideoEntry>()
    private val duplicates = mutableListOf<DuplicatePair>()
    private var folder: DocumentFile? = null
    private var folderUri: Uri? = null
    private var busy = false
    private var exporter: ExportQueue? = null
    private var currentView = 0 // 0 = introduction; 1 = crop results; 2 = duplicate results
    private lateinit var folderText: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var results: LinearLayout
    private lateinit var chooseButton: Button
    private lateinit var cropButton: Button
    private lateinit var dupButton: Button
    private lateinit var exportButton: Button
    private lateinit var csvButton: Button
    private lateinit var cancelButton: Button
    private val blue = Color.rgb(23, 116, 168)
    private val dark = Color.rgb(27, 39, 54)
    private val muted = Color.rgb(104, 116, 130)
    private val bg = Color.rgb(250, 251, 253)
    private val green = Color.rgb(11, 123, 83)
    private val REQUEST_FOLDER = 101
    private val REQUEST_CSV = 102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        buildUi()
        val stored = getSharedPreferences("ui", MODE_PRIVATE).getString("folder", null)
        if (stored != null) {
            runCatching {
                val uri = Uri.parse(stored)
                folder = DocumentFile.fromTreeUri(this, uri)
                folderUri = uri
                folderText.text = "上次資料夾：${folder?.name ?: "已選取"}（重新掃描才會讀取影片）"
            }
        }
    }

    private fun d(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun txt(s: String, sp: Float = 14f, color: Int = dark, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = sp
            setTextColor(color)
            setLineSpacing(d(2).toFloat(), 1f)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun rounded(color: Int, radius: Int = 16, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = d(radius).toFloat()
            if (stroke != null) setStroke(d(1), stroke)
        }

    private fun pad(v: View, all: Int) { v.setPadding(d(all), d(all), d(all), d(all)) }

    private fun button(label: String, primary: Boolean = false): Button =
        Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            setTextColor(if (primary) Color.WHITE else blue)
            background = rounded(if (primary) blue else Color.rgb(231, 243, 250), 12)
            minHeight = d(48)
            minimumHeight = d(48)
        }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(d(16), d(12), d(16), d(8))
        }
        setContentView(root)
        root.addView(txt("影片管家", 26f, dark, true))
        root.addView(txt("批次去黑邊 × 內容辨識去重  ·  全程手機本機處理", 12f, muted), margin(0, 2, 0, 10))
        folderText = txt("尚未選取資料夾", 13f, muted)
        root.addView(folderText, margin(0, 0, 0, 8))
        chooseButton = button("選擇影片資料夾", true)
        root.addView(chooseButton, LinearLayout.LayoutParams(-1, d(48)))
        chooseButton.setOnClickListener { chooseFolder() }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cropButton = button("① 自動偵測黑邊")
        dupButton = button("② 尋找重複影片")
        row1.addView(cropButton, LinearLayout.LayoutParams(0, d(48), 1f))
        row1.addView(dupButton, LinearLayout.LayoutParams(0, d(48), 1f).apply { leftMargin = d(8) })
        root.addView(row1, margin(0, 9, 0, 8))
        cropButton.setOnClickListener { startCropScan() }
        dupButton.setOnClickListener { startDuplicateScan() }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        exportButton = button("③ 輸出勾選影片", true)
        csvButton = button("匯出重複清單 CSV")
        row2.addView(exportButton, LinearLayout.LayoutParams(0, d(48), 1f))
        row2.addView(csvButton, LinearLayout.LayoutParams(0, d(48), 1f).apply { leftMargin = d(8) })
        root.addView(row2, margin(0, 0, 0, 8))
        exportButton.setOnClickListener { startExport() }
        csvButton.setOnClickListener { exportCsv() }
        cancelButton = button("停止目前工作")
        cancelButton.setOnClickListener {
            stopping.set(true)
            exporter?.cancel()
            exporter = null
            statusText.text = "已要求停止；掃描會在目前影片處理完後停止"
        }
        root.addView(cancelButton, LinearLayout.LayoutParams(-1, d(40)))
        statusText = txt("先選擇包含影片的資料夾；會搜尋其子資料夾。", 13f, muted)
        root.addView(statusText, margin(0, 8, 0, 4))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.progressTintList = ColorStateList.valueOf(blue)
        root.addView(progress, LinearLayout.LayoutParams(-1, d(6)))
        val scroll = ScrollView(this).apply { isFillViewport = true }
        results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, d(10), 0, d(48))
        }
        scroll.addView(results)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        showIntroduction()
        updateBusy(false)
    }

    private fun margin(l: Int, t: Int, r: Int, b: Int) = LinearLayout.LayoutParams(-1, -2).apply {
        setMargins(d(l), d(t), d(r), d(b))
    }

    private fun showIntroduction() {
        results.removeAllViews()
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 16, Color.rgb(230, 234, 241))
            pad(this, 16)
        }
        block.addView(txt("裁切功能", 16f, dark, true))
        block.addView(txt("抽取影片不同時間點，判斷兩側黑邊是否固定。可預覽、手動調整左右裁切比例，再批次輸出 MP4。", 13f, muted), margin(0, 4, 0, 14))
        block.addView(txt("內容去重功能", 16f, dark, true))
        block.addView(txt("對多個影格產生 pHash / dHash 內容特徵，找出不同檔名、解析度、壓縮率的疑似重複影片。只顯示候選，不會刪除原檔。", 13f, muted), margin(0, 4, 0, 10))
        results.addView(block, margin(0, 4, 0, 0))
    }

    private fun updateBusy(value: Boolean) {
        busy = value
        chooseButton.isEnabled = !value
        cropButton.isEnabled = !value
        dupButton.isEnabled = !value
        exportButton.isEnabled = !value && videos.any { it.crop.selected && it.crop.cropped }
        csvButton.isEnabled = !value && duplicates.isNotEmpty()
        cancelButton.isEnabled = value
        window.decorView.keepScreenOn = value
        if (!value && progress.progress != 100) progress.progress = 0
    }

    private fun status(message: String, percent: Int? = null) {
        runOnUiThread {
            statusText.text = message
            if (percent != null) progress.progress = percent.coerceIn(0, 100)
        }
    }

    private fun chooseFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_FOLDER)
    }

    @Deprecated("Handled for platform Activity without adding a UI framework dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        if (requestCode == REQUEST_FOLDER) {
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
            folderUri = uri
            folder = DocumentFile.fromTreeUri(this, uri)
            getSharedPreferences("ui", MODE_PRIVATE).edit().putString("folder", uri.toString()).apply()
            videos.clear(); duplicates.clear()
            currentView = 0
            showIntroduction()
            folderText.text = "已選：${folder?.name ?: "影片資料夾"}"
            scanFiles()
        } else if (requestCode == REQUEST_CSV) {
            val content = makeCsv()
            io.execute {
                try {
                    contentResolver.openOutputStream(uri, "w")!!.use { stream ->
                        OutputStreamWriter(stream, Charsets.UTF_8).use { it.write("\uFEFF" + content) }
                    }
                    status("CSV 匯出完成")
                } catch (e: Exception) { status("CSV 儲存失敗：${e.message}") }
            }
        }
    }

    private val extensions = setOf("mp4", "mkv", "mov", "m4v", "webm", "avi", "3gp")

    private fun scanFiles() {
        val root = folder ?: return
        if (busy) return
        stopping.set(false)
        updateBusy(true)
        status("正在讀取資料夾…", 0)
        io.execute {
            val found = mutableListOf<VideoEntry>()
            try {
                fun walk(dir: DocumentFile, relative: String, depth: Int) {
                    if (depth > 12 || found.size >= 10_000 || stopping.get()) return
                    dir.listFiles().forEach { file ->
                        if (stopping.get()) return@forEach
                        if (file.isDirectory) {
                            if (file.name != "裁切結果") walk(file, relative + (file.name ?: "") + "/", depth + 1)
                        } else if (file.isFile) {
                            val name = file.name ?: "未命名影片"
                            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                            if (file.type?.startsWith("video/") == true || ext in extensions) {
                                found.add(VideoEntry(file.uri, name, relative + name, file.length(), file.lastModified()))
                            }
                        }
                    }
                }
                walk(root, "", 0)
                runOnUiThread {
                    videos.clear(); videos.addAll(found)
                    duplicates.clear()
                    statusText.text = "已找到 ${videos.size} 支影片；可開始偵測黑邊或找重複內容。"
                    progress.progress = 100
                    updateBusy(false)
                }
            } catch (e: Exception) {
                status("讀取失敗：${e.message}")
                runOnUiThread { updateBusy(false) }
            }
        }
    }

    private fun startCropScan() {
        if (videos.isEmpty()) { toast("請先選擇含影片的資料夾"); return }
        stopping.set(false); updateBusy(true)
        currentView = 1
        results.removeAllViews()
        status("準備分析 ${videos.size} 支影片…", 0)
        io.execute {
            var auto = 0; var needs = 0; var errors = 0
            videos.forEachIndexed { i, item ->
                if (stopping.get()) return@forEachIndexed
                try {
                    item.crop = analyzer.detectCrop(item)
                    item.error = null
                    if (item.crop.kind == CropKind.AUTO) auto++
                    if (item.crop.kind == CropKind.REVIEW) needs++
                } catch (e: Exception) {
                    item.crop = CropDecision(kind = CropKind.REVIEW, note = "無法分析：${e.message}")
                    item.error = e.message
                    errors++
                }
                status("偵測黑邊 ${i + 1}/${videos.size}　自動 $auto｜待確認 $needs｜失敗 $errors",
                    (100.0 * (i + 1) / videos.size).toInt())
            }
            runOnUiThread {
                updateBusy(false)
                statusText.text = if (stopping.get()) "掃描已停止。完成部分分析。" else
                    "分析完成：自動建議 $auto 支、待人工確認 $needs 支、讀取失敗 $errors 支"
                progress.progress = 100
                showCropResults()
            }
        }
    }

    private fun startDuplicateScan() {
        if (videos.size < 2) { toast("至少需要兩支影片"); return }
        stopping.set(false); updateBusy(true)
        currentView = 2
        results.removeAllViews()
        status("準備辨識影片內容…", 0)
        io.execute {
            var errors = 0
            videos.forEachIndexed { i, item ->
                if (stopping.get()) return@forEachIndexed
                try {
                    item.fingerprint = analyzer.fingerprint(item)
                    item.error = if (item.fingerprint.size < 5) "可辨識畫格不足" else null
                } catch (e: Exception) {
                    item.fingerprint = emptyList()
                    item.error = e.message
                    errors++
                }
                status("擷取影像指紋 ${i + 1}/${videos.size}（未讀取完整影片檔案）",
                    (85.0 * (i + 1) / videos.size).toInt())
            }
            val found = mutableListOf<DuplicatePair>()
            if (!stopping.get()) {
                for (i in 0 until videos.size) {
                    val a = videos[i]
                    if (a.fingerprint.size < 5) continue
                    for (j in i + 1 until videos.size) {
                        val b = videos[j]
                        if (b.fingerprint.size < 5 || !HashMath.isDurationComparable(a.durationMs, b.durationMs)) continue
                        val score = HashMath.similarity(a.fingerprint, b.fingerprint) ?: continue
                        if (score >= 78) found.add(DuplicatePair(a, b, score, score >= 86))
                    }
                    if (i % 25 == 0) status("正在比對影片指紋 ${i + 1}/${videos.size}",
                        85 + (15.0 * (i + 1) / videos.size).toInt())
                    if (stopping.get()) break
                }
            }
            runOnUiThread {
                duplicates.clear(); duplicates.addAll(found.sortedByDescending { it.score })
                updateBusy(false)
                progress.progress = 100
                statusText.text = if (stopping.get()) "已停止；清單僅包含已完成的結果" else
                    "內容比對完成：找到 ${found.size} 組疑似重複；無法讀取 $errors 支"
                showDuplicates()
            }
        }
    }

    private fun showCropResults() {
        currentView = 1
        results.removeAllViews()
        val auto = videos.count { it.crop.kind == CropKind.AUTO }
        results.addView(txt("裁切候選  ·  自動建議 $auto / 共 ${videos.size} 支", 17f, dark, true), margin(0, 0, 0, 4))
        results.addView(txt("只有一致的固定黑邊會預設勾選；可點「預覽／修改」手動調整。", 12f, muted), margin(0, 0, 0, 12))
        videos.sortedWith(compareBy<VideoEntry> { it.crop.kind != CropKind.AUTO }
            .thenBy { it.crop.kind != CropKind.REVIEW }).take(400).forEach { item ->
            val decision = item.crop
            val card = card()
            val check = CheckBox(this).apply {
                text = item.name
                textSize = 13f
                setTextColor(dark)
                isChecked = decision.selected
                setOnCheckedChangeListener { _, checked ->
                    decision.selected = checked
                    updateBusy(false)
                }
            }
            card.addView(check, margin(0, 0, 0, 2))
            val label = when (decision.kind) {
                CropKind.AUTO -> "自動建議"
                CropKind.REVIEW -> "待確認／手動調整"
                CropKind.NONE -> "未發現固定黑邊"
            }
            val percent = "左 %.1f%% ｜右 %.1f%%".format(Locale.US, decision.left * 100, decision.right * 100)
            card.addView(txt("$label　·　$percent", 12f,
                if (decision.kind == CropKind.AUTO) green else muted, decision.kind == CropKind.AUTO))
            card.addView(txt(decision.note, 12f, muted), margin(0, 2, 0, 6))
            val previewButton = button("預覽／修改裁切位置")
            card.addView(previewButton, LinearLayout.LayoutParams(-1, d(40)))
            previewButton.setOnClickListener { openPreview(item) }
            results.addView(card, margin(0, 0, 0, 9))
        }
        if (videos.size > 400) results.addView(txt("僅顯示前 400 支；所有影片仍保留分析結果。", 12f, muted))
        updateBusy(false)
    }

    private fun showDuplicates() {
        currentView = 2
        results.removeAllViews()
        results.addView(txt("疑似重複影片  ·  ${duplicates.size} 組", 17f, dark, true), margin(0, 0, 0, 5))
        results.addView(txt("相似度來自多段影格內容特徵，非檔名／容量；請實際播放確認。", 12f, muted), margin(0, 0, 0, 10))
        if (duplicates.isEmpty()) {
            results.addView(txt("未發現符合門檻的重複候選。這不代表所有影片都必然不同。", 13f, muted))
            return
        }
        duplicates.take(200).forEach { pair ->
            val card = card()
            card.addView(txt("${if (pair.highConfidence) "高度相似" else "待人工確認"}  ·  ${pair.score}%", 14f,
                if (pair.highConfidence) green else blue, true), margin(0, 0, 0, 6))
            card.addView(txt("A：${pair.first.relativePath}", 13f, dark))
            card.addView(txt("B：${pair.second.relativePath}", 13f, dark), margin(0, 3, 0, 6))
            val suggested = if (qualityRank(pair.first) >= qualityRank(pair.second)) pair.first.name else pair.second.name
            card.addView(txt("優先檢查保留：$suggested（僅依解析度和檔案量粗估，不代表畫質一定較佳）", 11f, muted))
            results.addView(card, margin(0, 0, 0, 9))
        }
        if (duplicates.size > 200) results.addView(txt("僅顯示前 200 組；CSV 會包含全部結果。", 12f, muted))
    }

    private fun qualityRank(e: VideoEntry): Double =
        e.width.toDouble() * e.height.toDouble() + (e.bytes.toDouble() / 1_000_000) * 10_000

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.WHITE, 14, Color.rgb(228, 234, 240))
        pad(this, 12)
    }

    private fun openPreview(item: VideoEntry) {
        status("正在擷取影片預覽影格…")
        io.execute {
            val bmp = try { analyzer.preview(item.uri) } catch (_: Exception) { null }
            runOnUiThread {
                if (bmp == null) { toast("無法取得預覽畫面"); return@runOnUiThread }
                showPreviewDialog(item, bmp)
            }
        }
    }

    private fun showPreviewDialog(item: VideoEntry, frame: Bitmap) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; pad(this, 14) }
        body.addView(txt(item.name, 14f, dark, true), margin(0, 0, 0, 6))
        val label = txt("", 12f, blue, true)
        body.addView(label)
        val img = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
        body.addView(img, LinearLayout.LayoutParams(-1, d(270)))
        var left = item.crop.left
        var right = item.crop.right
        fun refresh() {
            label.text = "預覽裁切後｜左 %.1f%%，右 %.1f%%".format(Locale.US, left * 100, right * 100)
            val x = (frame.width * left).roundToInt().coerceIn(0, frame.width - 2)
            val x2 = (frame.width * (1 - right)).roundToInt().coerceIn(x + 2, frame.width)
            val cropped = Bitmap.createBitmap(frame, x, 0, x2 - x, frame.height)
            img.setImageBitmap(cropped)
        }
        val leftBar = SeekBar(this).apply { max = 88; progress = (left * 200).roundToInt().coerceIn(0, 88) }
        val rightBar = SeekBar(this).apply { max = 88; progress = (right * 200).roundToInt().coerceIn(0, 88) }
        body.addView(txt("左邊裁切", 12f, muted))
        body.addView(leftBar)
        body.addView(txt("右邊裁切", 12f, muted))
        body.addView(rightBar)
        leftBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                left = value / 200f; refresh()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        rightBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                right = value / 200f; refresh()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        refresh()
        val scroll = ScrollView(this).apply { addView(body) }
        AlertDialog.Builder(this)
            .setTitle("裁切預覽與手動調整")
            .setView(scroll)
            .setPositiveButton("套用並勾選") { _, _ ->
                item.crop = CropDecision(left, right, CropKind.REVIEW,
                    "已人工確認裁切位置", selected = (left > 0.015f || right > 0.015f))
                showCropResults()
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener { frame.recycle() }
            .show()
    }

    private fun startExport() {
        val selected = videos.filter { it.crop.selected && it.crop.cropped }
        if (selected.isEmpty()) { toast("尚未勾選可裁切的影片"); return }
        val dir = folder ?: return
        val target = dir.findFile("裁切結果") ?: dir.createDirectory("裁切結果")
        if (target == null || !target.isDirectory) { toast("無法建立裁切結果資料夾，請選可寫入的資料夾"); return }
        AlertDialog.Builder(this)
            .setTitle("確認批次裁切")
            .setMessage("將裁切 ${selected.size} 支影片。\n\n輸出至所選資料夾內的「裁切結果」，原始影片不會刪除或覆蓋。轉檔期間請保持畫面開啟，並確保有足夠的剩餘空間。")
            .setNegativeButton("取消", null)
            .setPositiveButton("開始輸出") { _, _ ->
                stopping.set(false); updateBusy(true)
                status("準備開始裁切…", 0)
                exporter = ExportQueue(this, target, selected,
                    onStatus = { msg -> status(msg) },
                    onFinish = { done, failed, cancelled ->
                        exporter = null
                        updateBusy(false)
                        progress.progress = 100
                        statusText.text = if (cancelled) "已停止。成功 $done，失敗 $failed" else
                            "批次完成：成功 $done 支、失敗 $failed 支；已存到「裁切結果」資料夾"
                    })
                exporter!!.start()
            }.show()
    }

    private fun exportCsv() {
        if (duplicates.isEmpty()) { toast("請先完成重複影片掃描"); return }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "影片重複候選.csv")
        }
        startActivityForResult(intent, REQUEST_CSV)
    }

    private fun makeCsv(): String {
        fun quote(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val b = StringBuilder("相似等級,相似度,檔案A,檔案B,A尺寸,B尺寸,A容量bytes,B容量bytes\r\n")
        duplicates.forEach { p ->
            b.append(listOf(
                if (p.highConfidence) "高度相似" else "待確認",
                p.score.toString(), p.first.relativePath, p.second.relativePath,
                "${p.first.width}x${p.first.height}", "${p.second.width}x${p.second.height}",
                p.first.bytes.toString(), p.second.bytes.toString()
            ).joinToString(",") { quote(it) }).append("\r\n")
        }
        return b.toString()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        stopping.set(true)
        exporter?.cancel()
        io.shutdown()
        super.onDestroy()
    }
}
