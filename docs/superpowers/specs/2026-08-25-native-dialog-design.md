# 原生翻譯視窗 showdialog：取代 osascript display dialog

日期：2026-08-25
狀態：已核准（設計於對話中逐節確認）

## 背景與動機

翻譯結果視窗目前由 `translate.py` spawn `osascript -e 'display dialog ...'` 顯示。2026-08-25 發生事故：
使用者點 Close 完全沒反應，kill 主程式也關不掉，視窗永久留在螢幕上。

根因（`/tmp/translate_debug.log` 有完整證據）：使用者點擊讓 dialog 取得焦點時，注音輸入法（IMK，
Input Method Kit）嘗試掛上這個 osascript process，但它是背景鏈 spawn、沒有正常 GUI session 身分的
process，mach port（macOS 行程間通訊管道）連線失敗，錯誤訊息為
`error messaging the mach port for IMKCFRunLoopWakeUpReliable`，直接把 osascript 的事件迴圈
（run loop）喚醒機制弄壞——視窗還畫在螢幕上（WindowServer 畫的），但 process 再也處理不了任何
點擊。這種凍法連 `giving up after` 逾時參數都救不了（計時器跑在同一個凍住的 run loop 上）。

歷史脈絡：專案起點是純腳本的 Automator workflow，`display dialog` 是零依賴的自然選擇；後來的
`copykey` / `axselect` / `translatenative` 三個 Swift helper 都是 osascript 路線硬失敗後的反應式
產物，而 dialog 一直沒硬失敗過，所以活到現在。`split_into_pages` 分頁整套邏輯本質上是在繞
dialog 的字數限制打補丁。這次凍死事故證明 dialog 路線也到頭了。

## 設計

新增第四個 Swift helper `showdialog`，模式完全比照 `translatenative`：獨立小執行檔、編進 app
bundle、由 `translate.py` 以子程序呼叫。Java 層（`java-trigger/` 主程式）完全不動。

呼叫鏈：`Java（熱鍵/取字）→ Python（翻譯）→ Swift showdialog（顯示視窗）`

### showdialog（新檔 `java-trigger/showdialog.swift`）

- **介面合約**：`showdialog <視窗標題>`，內容文字從 stdin 讀（UTF-8）。視窗正常關閉後 exit 0；
  啟動失敗 exit 非 0 並把原因寫到 stderr。不輸出任何 stdout——分頁退役後呼叫端不需要解析按鈕。
  stdin 為空 → 直接 exit 0（比照 translatenative）。
- **視窗本體**：正規 `NSApplication`（`.accessory` activation policy，不出現在 Dock）+
  `NSWindow`（titled + closable），內容為 `NSScrollView` 包唯讀 `NSTextView`：
  - 全文一次顯示、可捲動、**文字可選取複製**（osascript dialog 做不到的兩件事）
  - 系統字體 14pt，跟隨系統深淺色主題（AppKit 原生行為，不用自己處理）
  - 視窗寬 480pt，高依內容計算、上限為螢幕高的七成，置中顯示
  - 底部一顆 Close 按鈕（keyEquivalent 設 Return，按 Enter 也能關）
- **關閉行為**（四條路，全跑在正規 AppKit run loop 上，輸入法掛上來走正常路徑）：
  1. Close 按鈕 / 標題列紅鈕 / Enter
  2. Esc 鍵
  3. **失焦自動關**：視窗曾成為 key window 後失去焦點（`windowDidResignKey`）→ 關閉。
     查完翻譯點回工作視窗即自動收起。
  4. **孤兒防線**：每 120 秒檢查一次，若視窗**不是** key window → 關閉。
     這精準涵蓋孤兒場景（沒人在看的視窗必然沒有焦點），又不會在使用者專心讀長文時
     半路把視窗收掉（有焦點就一直留著）。
- **焦點時序風險**：失焦自動關若在「視窗還沒拿到焦點」時就觸發會秒關。實作上先
  `activate(ignoringOtherApps:)` 搶焦點、`makeKeyAndOrderFront`，且失焦監聽只在視窗
  **確實成為過 key window** 之後才生效；萬一 activation 失敗（極端背景情境），視窗留在
  螢幕上由孤兒防線在 120 秒內收掉。

### translate.py 的變化（淨減碼）

- `show_dialog(text)` 改寫：從候選路徑找 helper（比照 `NATIVE_HELPER_CANDIDATES` 寫法：
  app bundle 內 → repo 的 `java-trigger/build/`）→ 文字餵 stdin 執行 → helper 找不到、
  丟例外、或 exit 非 0 → 走既有的 `show_notification` fallback 並記 log。
- **整段移除**：`CHARS_PER_PAGE`、`split_into_pages`、`_run_dialog_page`、分頁迴圈與
  Previous/Close/Next 三種按鈕組合、osascript dialog 呼叫（含 2026-08-25 剛加的
  `giving up after 120`，被本設計取代）。
- 「≤3 個字走通知、否則走 dialog」的分流邏輯不變。
- `Google翻譯.workflow` 內嵌複本由既有 pre-commit hook（`scripts/build_workflow.py`）自動同步。

### build_app.sh

加一行：`swiftc showdialog.swift -O -o "$OUT_DIR/$APP_NAME.app/Contents/MacOS/showdialog"`，
並更新 helper 清單的說明文字。

### 錯誤處理總表

| 情況 | 行為 |
|------|------|
| helper 不存在（未 rebuild / workflow 單獨使用） | log + 通知 fallback |
| helper 啟動失敗（exit 非 0） | log stderr + 通知 fallback |
| 視窗開著沒人理 | 失焦即關；從未取得焦點則 120 秒內由孤兒防線收掉 |
| python 等待期間使用者慢慢讀 | 正常——python 等 helper 結束才退出，與現行 osascript 行為相同 |

## 實作計畫

1. **`java-trigger/showdialog.swift`**：依上述規格實作（預估 ~120 行）。
   驗證：`swiftc showdialog.swift -O -o java-trigger/build/showdialog`，然後
   `echo "測試文字" | java-trigger/build/showdialog "Google Translate"`——逐項驗
   Esc / Enter / Close 鈕 / 點別的視窗失焦關 / 文字可選取複製 / 長文可捲動 /
   深淺色主題。
2. **`java-trigger/build_app.sh`**：加 showdialog 編譯行。
3. **`translate.py`**：改寫 `show_dialog`、移除分頁整段。
   驗證：`TRANSLATE_INPUT="a long english paragraph ..." python3 translate.py`
   走完整翻譯流程開出原生視窗；把 helper 改名暫時藏起來重跑，確認 fallback 成通知。
4. **README.md**：更新 dialog 相關描述（分頁說明、「按鈕樣式由系統 dialog 控制」的限制條目
   改為原生視窗行為）。
5. **commit**（pre-commit hook 會自動同步 .wflow）。
6. **部署**：`java-trigger/build_app.sh` rebuild → 換掉 `/Applications/QuickTranslate.app`
   → `launchctl kickstart -k gui/$UID/com.quicktranslate` 重啟
   → **到系統設定重新勾輔助使用權限**（rebuild 簽章改變會讓 axselect 的授權失效——已知代價）
   → 端到端按 cmd+' 驗證。

## 不做的事（YAGNI）

- 不做視窗大小記憶、字級設定、複數視窗管理。
- 不動 Java 層、不動翻譯邏輯、不動通知路徑。
- 不為 helper 寫自動化測試（純 UI 行為，repo 亦無測試基礎設施）；以上述手動驗證清單為準。
