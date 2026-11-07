# 影片管家 VideoKeeper Android

原生 Kotlin + Jetpack Compose Material 3 的離線影片工具。支援 Android 9（API 28）以上，使用 AndroidX Media3 Transformer 1.11.1；不使用 WebView、FFmpeg、WebCodecs 或 MediaRecorder。影片畫面維持硬體 H.264 路徑；音訊優先直接複製，非相容音訊才允許 AAC 軟體編碼。

## 已完成功能

- SAF 多部影片選取、整個資料夾與子資料夾掃描；保存持久化 URI 授權，不要求 `MANAGE_EXTERNAL_STORAGE`，App 不宣告 `INTERNET`。
- 顯示縮圖、檔名、相對路徑、解析度、片長、fps、編碼、容量與分析狀態。
- 多時間點低解析度抽樣的左右黑邊辨識：排除片頭/片尾全黑、低亮度、彩色邊、動態背景、旋轉 metadata 和直式來源；不確定時只要求人工確認。
- 原始畫面裁切框與藍灰外框的裁切後預覽；左右滑桿、時間點、重設偵測值、偶數輸出尺寸預估。
- 黑邊分析完成後，可將未勾選、不需裁切的影片移動到輸出資料夾；優先使用 SAF 原子移動，必要時驗證複製後再刪除來源。
- Media3 Transformer OpenGL `Crop`、硬體解碼器/H.264 影片編碼器 preflight、H.264 MP4；AAC 音訊優先直接複製，非相容音訊才使用 AAC（可接受軟體音訊編碼），並驗證音訊與輸出軌完整性。
- Android 15 `mediaProcessing` Foreground Service：循序批次佇列、進度、取消、失敗略過、通知、真正完成後的耗時與速度倍數；匯入、黑邊分析、去重也會在背景顯示進度，完成後自動關閉通知。
- Room 本機內容指紋快取；pHash、dHash、亮度/色彩特徵、有限時間偏移、SSIM 等效畫面驗證、相似影片分組、品質比較與 CSV 匯出；可在確認後刪除較短或較小的來源影片，不會自動刪除。
- 沿用 Web 左上角 Logo 的 adaptive icon、monochrome vector 與 PNG launcher assets。

## 手機操作

1. 進入「匯入」，選取多部影片或資料夾。資料夾會遞迴掃描，略過 `裁切結果`。
2. 選擇輸出資料夾（可與來源不同；不同資料夾會保留原始檔名，同資料夾才會加防覆蓋後綴）。
3. 在「裁切」點「分析全部影片」，只勾選已分析且確認安全、需要裁切的項目；不需裁切的未勾選影片可選擇移動到輸出資料夾。
4. 點「加入本機工作佇列」。工作會由系統媒體處理通知執行；原始檔不會被覆蓋。佇列完成後可在佇列頁確認並一鍵刪除已裁切原影片。Android 13 以上請允許「通知」權限，才能在背景看到進度；工作結束後通知會自動關閉。
5. 在「去重」掃描內容並匯出 CSV；掃描期間會顯示持續通知。結果是候選與畫面驗證，不代表逐幀證明，請自行播放確認。

## 建置與 Debug APK

本專案使用官方 Gradle Wrapper 8.13、JDK 17、Android SDK 36。Debug 變體使用 Android Gradle Plugin 自動產生的 debug keystore；私鑰不在 Repository。

```sh
./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug --stacktrace
```

本地 APK：`app/build/outputs/apk/debug/app-debug.apk`。

GitHub Actions 的 `Android CI` 會在每次 push、Pull Request 或手動執行時測試、建置、驗證 debug signer，並上傳 `VideoKeeper-debug-APK` artifact（包含 `app-debug.apk` 與 `SHA256SUMS.txt`）。下載 artifact 後即可傳到 Android 手機安裝測試。

## 正式 Release 簽章

正式版不能使用每次重建的 debug key。Release workflow 只接受固定的四個 GitHub Actions Secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

請依 [`docs/RELEASE_SIGNING.md`](docs/RELEASE_SIGNING.md) 在自己的安全環境建立並保存 JKS，再設定 Secrets。未設定或簽章驗證失敗時 workflow 會停止，不會建立正式 Release；不得把 JKS、密碼或 Token 提交到 Git。

## 測試與限制

- 純 Kotlin 測試涵蓋固定/不對稱黑邊、全黑片頭、低亮度、動態不穩定、旋轉/直式來源、偶數裁切尺寸、時間偏移指紋、快取編解碼與不同內容閘門。
- Android instrumentation 測試檢查沒有 INTERNET 權限與 mediaProcessing service 宣告。
- 長時間匯入、黑邊分析與去重會啟動 user-initiated mediaProcessing 前景服務，並使用單一進度通知；工作完成、取消或失敗後通知會自動移除。若使用者拒絕 Android 通知權限，工作仍可執行但系統通知抽屜不會顯示。
- 本開發環境沒有可用 Android emulator 或實體硬體，因此硬體 codec 速度、HDR/SDR、特殊容器、真實音訊輸出仍需在手機上驗證；不能由本地單元測試推論實機速度。
- 去重刪除會先要求確認，並透過 SAF 寫入授權刪除來源；若單檔匯入的供應者只提供讀取權限，請改用資料夾授權後再刪除。
- 佇列的原影片刪除按鈕只針對成功輸出且確實有裁切的項目；刪除前會列出檔案並再次確認，失敗的項目會保留。
- HEVC 不會自動啟用；只有日後有裝置實測優勢且硬體/音訊/相容性條件明確時才適合增加。

詳細建置紀錄見 [`docs/BUILD_VALIDATION.md`](docs/BUILD_VALIDATION.md)，正式 Release 內容見 [`docs/RELEASE_NOTES_v1.0.0.md`](docs/RELEASE_NOTES_v1.0.0.md)。
