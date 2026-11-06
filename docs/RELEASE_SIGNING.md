# Android 正式 APK 簽章與 GitHub Secrets

Android 每次升級安裝同一 App，必須使用**同一把簽章金鑰**。GitHub CI 產生的臨時 Debug 金鑰不能當正式 Release 金鑰。

## 在自己的電腦生成、離線備份 keystore

安裝 JDK 後執行：

```bash
keytool -genkeypair -v -keystore videokeeper-release.jks -alias videokeeper -keyalg RSA -keysize 3072 -validity 10000
```

依畫面設定密碼與證書資訊。**自行安全保存** `videokeeper-release.jks` 和密碼；不放到 repo 或 issue。

將 JKS 以 base64 編碼成單行：

Linux/macOS：`base64 -w0 videokeeper-release.jks`（macOS 可用 `base64 < videokeeper-release.jks | tr -d '\n'`）。

Windows PowerShell：`[Convert]::ToBase64String([IO.File]::ReadAllBytes('videokeeper-release.jks'))`

GitHub Repository → Settings → Secrets and variables → Actions → New repository secret，建立：
- `ANDROID_KEYSTORE_BASE64`：JKS 檔案的 base64 單行字串
- `ANDROID_KEYSTORE_PASSWORD`：keystore 密碼
- `ANDROID_KEY_ALIAS`： `videokeeper`（若生成時另取名稱，填實際名稱）
- `ANDROID_KEY_PASSWORD`：key 密碼（生成時填入的）

只有確定 CI 與 Debug APK 功能通過後，才推送 `v1.0.0` 標籤。工作流程會驗證 APK 簽章，並用 `gh release create` 發布。

**請保留 keystore 的離線備份。遺失 Release 簽章私鑰就可能無法以同一套件名稱持續更新既有使用者安裝的 APK。**
