# Engine 方案與驗收選擇（2026-11-06）

| 工作 | 最佳預設 | 不採用的路線 / 原因 |
|---|---|---|
| 裁切與輸出 | AndroidX Media3 Transformer + hardware MediaCodec + OpenGL Crop | MediaRecorder 即時錄影受片長約束；FFmpeg.wasm 偏慢；重複開發 native MediaCodec pipeline 維護成本高 |
| 視覺預覽 | Media3 ExoPlayer GPU effects 或相符的預覽繪圖路徑 | 先裁到黑底固定框會造成假黑邊；必須用中性色的外部背景 |
| 黑邊分析 | MediaMetadataRetriever 小尺寸縮圖抽樣；若大量影片需優化，改成單次解碼流 MediaExtractor + MediaCodec | 不要整支影片逐畫格讀入記憶體 |
| 內容去重 | 黑邊正規化 + 時序 pHash/dHash + 亮度、色彩特徵 + 第二階段 SSIM | 單張縮圖、檔名、大小與 SHA256 不可靠；不應不加驗證直接投入大模型 |
| 本機索引 | Room / 持久化 URI + 檔案 metadata + 算法版本 | 全部重新掃描浪費 CPU / 電池 |
| 背景處理 | MediaProcessing Foreground Service 依法管理；序列化硬體轉檔 | 批量並行 encoder 容易爭硬體、發熱、失敗 |

官方資料：
- https://developer.android.com/jetpack/androidx/releases/media3
- https://developer.android.com/media/media3/transformer
- https://developer.android.com/media/media3/transformer/transformations
- https://developer.android.com/about/versions/15/changes/foreground-service-types

目標：不保證固定 10x、20x 加速，而是依來源、輸出、裝置真正 hardware codec capability 進行測試。不能高速處理的檔案應顯示原因。
