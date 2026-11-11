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

## GitHub 同步與分支基準（永久規則）

- GitHub 遠端目標分支上已提交的程式碼是 Codex Cloud 實作基準。明知工作區落後遠端時，不得繼續以舊程式碼開始實作。
- 每次新的實作、修改或修復回合開始前（包括同一 Cloud 任務的後續回合），都必須重新檢查遠端；不得沿用上一回合或其他 Cloud 工作區的同步結論，也不得假設目標分支一定是 `main`。
  1. 在專案根目錄讀取 `git status`、目前 `HEAD`、目前分支、upstream、所有 remote 與分支追蹤資訊，辨識本次任務真正的遠端目標分支。若沒有 upstream，必須依目前分支、明確的 push 設定、遠端 refs 與 GitHub 預設分支判斷，不能自行猜測。
  2. 執行 `git fetch origin --prune` 取得最新遠端提交資訊。若 `origin` 的 fetch refspec 沒有涵蓋已辨識的目標分支，必須再明確抓取該分支的 remote-tracking ref 或以遠端查詢確認，不能只依賴過時的本地 refs。
  3. 比較目前 `HEAD`、對應遠端目標分支及必要時的遠端預設分支，判斷工作區是落後、超前、已分歧或相同；可用 `git rev-list --left-right --count` 與 `git merge-base` 驗證，並記錄同步前的起始 commit。
  4. 只有在工作樹乾淨、位於正確的追蹤分支且可 fast-forward 時，才可在修改前使用 `git pull --ff-only <remote> <target-branch>`（已設定正確 upstream 時可使用等價的 `git pull --ff-only`）完成同步。
  5. 若有未提交變更、detached `HEAD`、分支分歧或未合併的 PR，先以可復原方式保護現有工作，依實際分支關係選擇安全整合方式；不得直接覆蓋、丟棄或重寫既有成果。無法安全同步時，停止可能基於舊版的實作並明確回報阻礙。
  6. 實作完成後執行適當驗證。只有在本次任務已獲授權推送且確認具有權限時才推送；推送後以 `git ls-remote <remote> refs/heads/<target-branch>` 核對遠端目標分支的 commit 與本地 `HEAD` 一致，並明確區分功能分支與 `main`，不得把推送功能分支誤報為已更新 `main`。
  7. 同一 Cloud 任務的每次新實作回合都要重新執行上述遠端檢查；不同 Cloud 工作區不會自動互相同步，不能假設其檔案或 refs 已更新。
  8. 禁止為了同步執行 `git reset --hard`、強制推送、隨意刪除分支或清除未提交修改。除非使用者明確授權，不得執行這些破壞性操作；即使獲授權，也必須先確認精確目標與可恢復性。
  9. 同步成功且沒有衝突時直接繼續任務，不必再次詢問使用者或要求使用者提醒同步。
  10. 最終回報精簡列出使用的分支、同步後的起始 commit、實作結果與推送結果；若未推送，說明未推送的原因。
