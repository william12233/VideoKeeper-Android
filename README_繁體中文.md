# 影片管家 Android v1.0（Android Studio 專案）

> 專案已於 Cloud VM 完成 4 項單元測試及 Debug APK 編譯；尚未完成實機影片測試。完整紀錄見 `docs/BUILD_VALIDATION.md`。

## 功能

1. **批次偵測左右黑邊**：對每支影片抽取 13 個分散時間點的畫格，測量邊緣黑色區域的比例與穩定度。只有高一致性者預設勾選；其餘可查看預覽並手動調整左右邊界。
2. **批次還原直立畫面**：使用 Android Jetpack Media3 Transformer Crop 特效，重新編碼為 H.264 MP4，放到來源資料夾內新建的「裁切結果」資料夾。原始檔只讀、不覆蓋。
3. **依影片內容找重複**：取 12 個時間點的畫格，計算 pHash、dHash 感知指紋，依對應時間點比較；可跨不同檔名、解析度、常見壓縮率。黑邊可穩定辨識時會先做正規化。重複結果標示相似度與人工確認建議，並可匯出 CSV。
4. **不刪檔、不上傳**：沒有網路權限，也不提供任何自動刪除功能。支援選定資料夾及其子資料夾（不掃描「裁切結果」）。

## 如何產出 APK（需先在 Windows 電腦操作一次）

1. 安裝 **Android Studio**，確保能使用 JDK 17 以上，下載 Android SDK Platform 36。
2. 將 ZIP 解壓縮後，在 Android Studio 點 **Open**，選擇 `VideoKeeper_Android` 專案根目錄（有 `settings.gradle.kts` 的地方）。
3. 等待 Gradle Sync，首次使用需要連網下載 Gradle 8.13、Android Gradle Plugin 8.11.1、Kotlin 2.2.10 和 Media3 1.11.1。
4. 點 **Build > Build Bundle(s) / APK(s) > Build APK(s)**，或在專案根目錄執行 `gradlew.bat assembleDebug`。
5. 成功後 APK 在 `app/build/outputs/apk/debug/app-debug.apk`。複製到 Android 手機安裝即可。

專案使用官方 Gradle 8.13 Wrapper；已移除原本的自製啟動器。來源、校驗及本次實際建置結果見 `docs/BUILD_VALIDATION.md`。

## 在手機使用

1. 開啟「影片管家」並選擇影片資料夾，例如 `Movies/我的影片` 或 `DCIM/某個子資料夾`。
2. 點 **① 自動偵測黑邊**，在結果中點「預覽／修改裁切位置」檢查不確定的影片。
3. 點 **③ 輸出勾選影片**，輸出放在所選資料夾內的 `裁切結果/`。
4. 點 **② 尋找重複影片**，查看依畫面內容匹配的疑似重複檔案；可按「匯出重複清單 CSV」。

Android 11 以上的系統資料夾選擇器不允許選擇內部儲存空間根目錄或 `Download` 根目錄；請選可以存取的子資料夾。手機儲存空間須可容納轉檔的暫存 MP4 檔與最終輸出檔。

## 重要限制

- **適合固定純黑的左右黑邊**：若是動態模糊背景、LOGO 覆蓋黑邊、純黑場景很多或黑邊比例常變，可能無法準確自動偵測，請預覽／手動修正。
- **去重不等於逐幀鑑定**：這是抽樣的感知指紋比對，無法保證找出所有剪輯、變速、片頭片尾不同、嚴重調色的重複影片；相似但非相同影片也可能出現誤判。配對限制片長差異約 4.5%，因此不能可靠偵測任意剪裁的相同片段。
- 影片讀取依手機硬體與 Android 媒體解碼器支援格式而定；AVI、部分 MKV/AV1/HDR、異常編碼可能讀取或匯出失敗。
- 所有裁切輸出都會重新編碼 H.264，**不是無損位元資料複製**；影片畫質和編碼速度依裝置而異。
- 目前沒有背景轉檔服務。操作轉檔時請保持 App 在前景、不要鎖屏；關閉 App 可能中斷工作。
- 批次轉檔以本機暫存檔為中繼，請保留足夠剩餘儲存空間。
- 未支援 HEVC 10-bit 等特殊編碼的無損保留。

## 專案技術

- 語言：Kotlin；支援 Android 9（API 28）以上；編譯 SDK 36。
- 動畫／UI：Android 原生 View（無 Compose）
- 文件存取：Android Storage Access Framework + DocumentFile，不需要要求整台手機的儲存空間權限。
- 影格抽樣：`MediaMetadataRetriever`
- 輸出：`androidx.media3:media3-transformer:1.11.1` + `media3-effect`
- 相似指紋：32x32 灰階 DCT（pHash）、9x8 差分（dHash），相同抽樣時間點比對。
- 快取：本機 SharedPreferences 儲存指紋，依 URI / 容量 / 修改日期 / 片長 / 手動裁切比例建立鍵。

## 已執行的檢查

- 官方 Gradle Wrapper JAR 與 distribution SHA-256 均已核對。
- Gradle 單元測試：4 項通過，0 失敗、0 錯誤、0 略過。
- Debug APK 編譯與 `apksigner` 簽章驗證通過。
- 尚未以真實手機影片驗證裁切、解碼、匯出及效能；首次使用請先以少量備份影片測試。
