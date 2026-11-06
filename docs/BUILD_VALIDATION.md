# 建置與驗證紀錄

## 工具鏈

- JDK：Eclipse Temurin 17.0.17+10。
- Gradle Wrapper：官方 Gradle 8.13，distribution SHA-256 保留在 `gradle-wrapper.properties`。
- Android SDK：compile/target SDK 36/35，Build Tools 36.0.0。
- Android Gradle Plugin：8.11.1；Kotlin：2.2.10；Media3：1.11.1。
- UI：Jetpack Compose Material 3；本機索引：Room 2.7.2。

## 本次實際執行

在 Repository 根目錄執行：

```sh
./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug --stacktrace
```

結果：

- `:app:testDebugUnitTest` 成功：17 tests，0 failed、0 error、0 skipped。
- 純 Kotlin 覆蓋固定/不對稱黑邊、片頭全黑、直式來源、旋轉 metadata、動態不穩定、偶數輸出尺寸、時間偏移指紋與 Room codec。
- `:app:compileDebugAndroidTestKotlin` 成功；instrumentation test 會檢查 APK 沒有 INTERNET 權限、宣告 mediaProcessing service，並建立進度/結果通知頻道。
- `:app:assembleDebug` 成功，APK：`app/build/outputs/apk/debug/app-debug.apk`。
- `:app:lintDebug` 成功；只保留 Android SDK/依賴版本提醒與 Compose/Media3 API 相容性警告，沒有 lint error。
- APK SHA-256（本次本機 Debug 產物）：`efe67ef488f5c209124a0a522b8bb1c937cbd10efbad772c8e38174db38b27f6`。
- APK 大小：67,688,471 bytes（約 64.5 MiB）。
- `apksigner verify --verbose --print-certs` 成功：APK Signature Scheme v2；簽章者為 Android Debug，沒有使用正式 Release 私鑰。
- CI workflow 會對 Debug APK 再執行 `apksigner verify`、產生 `SHA256SUMS.txt`，並上傳 `VideoKeeper-debug-APK` artifact。

## 尚未執行

目前 Cloud VM 沒有可用的 Android Emulator 或實體 Android 裝置，故尚未宣稱下列項目通過：

- 真實手機 MediaCodec 硬體解碼/編碼、Transformer Crop 與速度倍數。
- 真實 SAF provider 讀取、長影片背景佇列、Android 15 FGS timeout 行為與通知抽屜顯示。
- HDR/SDR、特殊 MKV/AV1/HEVC、旋轉 metadata 的實際輸出與聲畫播放。
- instrumentation test 的 connected execution；目前只完成測試 APK 編譯。

上述限制已在 App「設定」和 Release notes 說明。沒有可用硬體影片解碼器或 H.264 編碼器時，程式會在開始 Transformer 前失敗並保留來源；輸出尺寸會先對齊硬體常見的偶數限制，AAC 音訊則優先直接複製，非相容音訊才允許 Media3 的 AAC 軟體編碼。若硬體初始化偶發失敗，會在同一硬體路徑重新建立一次，仍失敗才略過該支影片。

## 正式簽章與 Release

Repository 沒有提交任何 JKS、私鑰、密碼或 Token。`.github/workflows/release.yml` 保留四個必要 Secrets：

`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。

目前尚未設定/驗證這四個 Secrets，因此沒有建立 `v1.0.0` tag 或正式 GitHub Release。設定固定 keystore 後，workflow 會先測試、建置、用 `apksigner` 驗證，再附上 APK、SHA256SUMS 與繁體中文更新日誌發布。
