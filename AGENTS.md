# VideoKeeper Android — Codex 工作規範

- 請一律使用繁體中文回答、撰寫 UI 文案、README、Release notes，程式識別字可用英文。
- 使用 **原生 Kotlin + Media3 Transformer 1.11.1（或核對官方文件後的最新穩定版）** 作為單一影片匯出引擎，不使用 MediaRecorder 即時錄製、WebCodecs 或 FFmpeg.wasm；避免引入 FFmpegKit 已停止維護的依賴。
- 強制優先硬體 MediaCodec 編解碼＋OpenGL 特效；對不能使用硬體加速的情境明確提示不支援，不可暗中退回到慢速逐幀 CPU 轉碼。不得編造匯出速度。
- App 使用繁體中文深海軍藍、淺水藍主色；沿用 `design-reference` 的 Web 色系／操作概念。使用原生介面（推薦 Jetpack Compose Material 3），而非以 WebView 包住原網站。
- 原始影片一律唯讀，不覆蓋、不刪除、不上傳。新影片寫入使用者授權的目的地；編碼前預覽每支影片裁切結果。
- 重複影片比對根據內容，不以檔名、檔案大小或 SHA256 作為唯一依據。所有刪除／清理均需明確使用者互動，第一版不提供自動刪除。
- 不要求 MANAGE_EXTERNAL_STORAGE；使用 SAF / MediaStore 官方權限；不要要求網路權限。
- 功能實作完成必須通過 Gradle build 和單元測試；不能只寫假介面或 mock 任務結果。Release 需 APK 確實產出、簽章有效、能安裝與播放聲畫完整的影片；未做實機測試必須清楚說明。
- 若已建好的 `ExportQueue`, `Analyzer`, `HashMath` 等程式有 API 過期、畫面比例錯誤、非硬體轉檔或中斷後資料損毀等問題，請完整重構。
- 執行 `./gradlew :app:testDebugUnitTest :app:assembleDebug --stacktrace`，確定可建立 APK；使用 `.github/workflows` 驗證 CI 與 Release。
- 不要將 keystore、金鑰、token、個人影片、`local.properties` 或非匿名測試媒體提交到 GitHub。
- 優先保留 source/target 影格率、音訊與旋轉方向；若需要轉碼或降級，顯示真正的理由。
