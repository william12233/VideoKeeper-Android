# 建置環境與驗證紀錄

## 原始碼整理

將儲存庫原有的 `影片管家 Android+Codex 完整交付包.zip` 全部 50 個項目解壓到根目錄，逐檔比對解壓內容，保留 `.github/`、`.gitignore` 等隱藏項目，再移除原 ZIP。

## 官方 Gradle Wrapper

- 版本：Gradle 8.13。
- JAR、Unix 與 Windows 啟動腳本來源：Gradle 官方儲存庫 `https://github.com/gradle/gradle/tree/v8.13.0` 對應根目錄檔案。
- JAR 官方校驗來源：`https://services.gradle.org/distributions/gradle-8.13-wrapper.jar.sha256`。
- JAR SHA-256：`81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`，下載後已實際核對。
- distribution SHA-256：`20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`，已與官方 `gradle-8.13-bin.zip.sha256` 核對，保留於 Wrapper properties，由官方 Wrapper 驗證下載。
- 移除 `build-tools/src/org/gradle/wrapper/GradleWrapperMain.java` 自製啟動器，避免再產生非官方 JAR。

## 雲端工具鏈

- Eclipse Temurin JDK 17.0.17+10（官方 GitHub 發布檔及其 SHA-256 已核對）。
- Android Command-line Tools 23.0，Google `repository2-1.xml` 對應 Linux archive checksum 已核對。
- Android SDK Platform 36、Build Tools 36.0.0 與 AGP 預設使用的 35.0.0、Platform Tools。
- AGP 8.11.1、Kotlin 2.2.10、Media3 1.11.1。原 Kotlin 2.0.21 無法讀取 Media3 1.11.1 的 Kotlin 2.2 metadata，因此升級 Kotlin 插件至 2.2.10。
- 環境啟用：`source /workspace/toolchains/activate.sh`。
- 工具鏈、依賴快取與代理／CA 設定位於 `/workspace/toolchains`，不提交至 Git。
- 不需網站、資料庫、Docker 或正式簽章金鑰。

## 驗證方式

在專案根目錄執行：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug --stacktrace
```

測試報告：`app/build/test-results/testDebugUnitTest/`。
Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。

## 實際結果（2026-11-06）

- `:app:testDebugUnitTest :app:assembleDebug --stacktrace`：成功，39 個任務（18 執行、21 已為最新）。
- JUnit `HashMathUnitTest`：4 項測試，0 失敗、0 錯誤、0 略過；涵蓋相同指紋、輕微轉碼差異、不同畫面及片長過濾。
- APK：12,317,921 bytes。
- APK SHA-256：`0fec1a1910d5126d18d48ae0598abd70baf86f096b999109ef352d42ff43594a`（本次本機 Debug 產物；其他機器的 Debug 簽章可能不同）。
- `apksigner verify --verbose`：成功，APK Signature Scheme v2，一個 Debug signer。
- 修復 `Analyzer.kt` 對 List 使用不支援的 `step`；改用索引逐二項取樣，保留原取樣位置。
- 初次建置的 SDK 缺少／授權狀態與 Kotlin metadata 不相容已處理，最終建置成功。
- 保留非阻擋警告：AGP 讀取新版 SDK XML、系統狀態列／導覽列 API 過期，以及 Media3 `@OptIn` 無效提示；不將這些警告當成功能驗證。
- 可重用安裝腳本已重跑成功：39 個任務皆為最新，APK 非空檢查通過。環境草稿已儲存完整 `install_script` 與 `start_skill`，需由環境設定介面儲存並 Publish 才會發布快照。
- 磁碟空間足夠，以上結果來自實際 Cloud VM，非 GitHub Actions 代跑。GitHub Actions 尚未於本次工作中核對執行結果。

未執行 Android 實機／模擬器影片解碼、裁切、匯出與效能測試；單元測試及 APK 建置不代表這些功能已通過驗證。本階段不建立正式 Release 或版本 tag。
