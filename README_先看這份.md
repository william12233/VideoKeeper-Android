# 影片管家 — Codex Android 原生版交付專案

這份 ZIP 是 **從先前 Android v1.0 原始碼延伸的可開發起始專案**，不是已完成實機驗證的正式 APK。內附完整的既有裁切、去重、批次與檔案介面程式，以及 Web 品牌素材、Android adaptive icons、GitHub Actions 和兩階段 Codex 完整任務指令。Codex 需要依需求重構與驗證後才能發布。目前已改用官方 Gradle 8.13 Wrapper，來源與 SHA-256 見 `docs/BUILD_VALIDATION.md`。

## GitHub 操作
1. 在自己的 GitHub 建立一個新 Public Repository：`VideoKeeper-Android`；不要勾選 Initialize with README/License/.gitignore（因 ZIP 已有）。
2. 將本 ZIP 解壓縮後，**上傳解壓縮後的目錄內容**到 Repository 根目錄；GitHub repo 根目錄必須看見 `settings.gradle.kts`, `app/`, `AGENTS.md`, `.github/workflows/`。不要只上傳 ZIP 一個檔案。
3. 在 Codex Cloud（網頁/桌面版）建立 environment，選取這個 repository，貼上 `CODEX_01_建立環境.txt`，檢查結果後 Publish。
4. 建立新的 Codex Cloud Task，貼上 `CODEX_02_發布環境後的正式開發任務.txt`。等它發出 PR／ commits、CI 通過後才建立版本 tag。
5. 正式發佈需要固定的 Android Release 簽章金鑰；請依 `docs/RELEASE_SIGNING.md` 加入 4 個 GitHub Actions Secrets。**不要把 JKS 或密碼上傳 GitHub Repository。**

## Repo 檔案導覽
- `app/`: Android Kotlin 原始碼與轉檔、指紋比對基礎實作。
- `design-reference/`: 先前 Web 版的公開 UI 參考原始碼；不包含私人影片或測試樣片。
- `app/src/main/res/mipmap-*/`, `mipmap-anydpi-v26/`, `drawable/`: 從 Web 左上角 Logo 製作的應用 icon，包含舊版 PNG 與 Android 8+ adaptive icon。
- `.github/workflows/build-apk.yml`: PR/commit 建置 Debug APK、單元測試。
- `.github/workflows/release.yml`: 以 git tag 發布簽章 APK，沒有 secrets 會阻止發布。
- `AGENTS.md`: Codex 工程規範。

**目前驗證**：Cloud VM 已完成 4 項單元測試及 Debug APK 編譯，Debug 簽章驗證通過。完整版本、雜湊與限制見 `docs/BUILD_VALIDATION.md`。影片解碼、裁切、匯出及硬體效能仍需實機驗證；本階段未發布正式 Release。
