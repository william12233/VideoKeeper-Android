# 影片管家 Android 專案說明

這是 VideoKeeper 的 Android 原生 Repository。請先閱讀 `AGENTS.md`；`design-reference/` 保存原 Web 版的色系、操作概念和 Logo 資產，App 本身使用 Compose 原生介面，不包裝 WebView。

主要程式位於 `app/src/main/java/com/videokeeper/mobile/`：

- `VideoKeeperApp.kt` / `Theme.kt`：繁體中文 Material 3 UI。
- `VideoKeeperViewModel.kt`：SAF 匯入、掃描、分析、去重、CSV 與佇列狀態。
- `Analyzer.kt` / `BlackBarDetector.kt`：低記憶體抽樣、黑邊安全決策、指紋與第二階段畫面驗證。
- `ExportQueue.kt` / `MediaProcessingService.kt`：Media3 Transformer 硬體-only 輸出與 Android 15 mediaProcessing 背景服務。
- `FingerprintDatabase.kt`：Room 本機指紋快取。

Debug APK 由 `.github/workflows/build-apk.yml` 在每次 push/PR 建置並上傳 artifact。正式 `release.yml` 保留固定簽章流程，只有 `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 四個 Secrets 都存在且 APK 簽章驗證成功時才發布；目前未設定 Secrets 時不得發布正式版。

本地驗證指令：

```sh
./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug --stacktrace
```

請不要提交 `local.properties`、JKS、keystore、密碼、Token、APK 或私人影片。沒有 emulator/實體手機時，只能回報單元測試、Android test 編譯與 APK 建置，不得宣稱硬體轉檔速度或真實影片輸出已驗證。
