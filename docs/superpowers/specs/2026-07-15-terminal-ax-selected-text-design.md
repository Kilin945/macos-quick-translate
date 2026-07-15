# Design: terminal 內用 AXSelectedText 讀取選字

日期：2026-07-15
狀態：已核准

## 問題

在 terminal 白名單 app（`terminal_apps`，預設 `com.apple.Terminal`）裡，熱鍵不送合成 Cmd+C（避免沒選字時的錯誤嗶聲，見 commit b38ed30），只讀剪貼簿現有內容。這讓 Claude Code（copy-on-select 會自動把選取放上剪貼簿）能正常翻譯，但在**一般 shell**（沒跑 Claude Code）選字不會自動複製，按熱鍵只會翻到舊的剪貼簿內容或什麼都沒有。

以 app 為單位的白名單無法解決：Claude Code 不是獨立 macOS app，它是跑在 Terminal.app 裡的 TUI，「Claude Code 內」和「一般 shell」對系統來說是同一個 bundle id。

## 方案

在 terminal 分支加一層 Accessibility 查詢：讀前景 app 焦點元件的 `AXSelectedText` 屬性。

- Terminal 原生選取（一般 shell 反白）→ `AXSelectedText` 讀得到字 → 直接翻譯，不碰剪貼簿
- Claude Code TUI 選取（滑鼠事件被 mouse-reporting 攔走，Terminal 無原生選取）→ 讀到空 → 沿用現有剪貼簿邏輯
- 沒選字 → 讀到空 → 同上，行為不變

已在使用者環境以 osascript 手動驗證：一般 Terminal 反白的文字可經由 `AXSelectedText` 讀出。

## 改動範圍

只改 `java-trigger/src/main/java/quicktranslate/TranslateRunner.java` 的 terminal 分支：

1. 新增 `axSelectedText()` 方法，執行以下 AppleScript（經 `osascript -e` 多段傳入）：

   ```applescript
   tell application "System Events"
     set p to first application process whose frontmost is true
     set fe to value of attribute "AXFocusedUIElement" of p
     value of attribute "AXSelectedText" of fe
   end tell
   ```

2. terminal 分支流程改為：先呼叫 `axSelectedText()`；非空白 → 以其為 `selected` 直接翻譯；空白／失敗 → 照舊讀剪貼簿。

非 terminal app 的合成 Cmd+C 流程完全不變。`config.sample` 不加新設定。

## 錯誤處理

- `osascript` 逾時上限 1 秒；逾時、非零 exit code、或任何例外 → 記 log、視為「沒讀到」退回剪貼簿邏輯。最壞情況等於現行行為。
- 權限面：讀 `AXSelectedText` 用的是 app 既有的輔助使用（Accessibility）授權，不會觸發新的授權視窗。

## Log

現有 `translate front=... terminal=...` log 增加 `ax=hit|miss|error` 欄位。

## 驗證（手動，四情境）

| 情境 | 預期 |
|------|------|
| 一般 terminal 選字 → 熱鍵 | 直接翻選取的字（新行為） |
| Claude Code 選字 → 熱鍵 | 照常翻（走剪貼簿，不變） |
| terminal 沒選字 → 熱鍵 | 翻剪貼簿最後內容（不變） |
| 一般 app（如瀏覽器）選字 → 熱鍵 | 照常翻（合成 Cmd+C，不變） |

## 捨棄的替代方案

- **檢查「編輯 > 拷貝」選單 enabled 狀態再決定送不送 Cmd+C**：選單名稱隨系統語言變（Copy／拷貝），要靠快捷鍵字元枚舉選單項目，脆且慢，又會覆寫剪貼簿。
- **terminal 內一律送 Cmd+C**：把 b38ed30 修掉的嗶聲問題請回來。

---

# 實作計畫

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

## Global Constraints

- 不得在 repo 出現個人姓名或家目錄絕對路徑（設定一律走 `.sample` 範本）。
- 子程序輸出一律以 UTF-8 解碼；launchd 環境沒有 `LANG`，spawn 前必須設 `LANG=en_US.UTF-8`（同 `readClipboard()` 的既有做法，否則 zh-TW 系統會退回 Big5 造成亂碼）。
- 本專案沒有單元測試基礎設施；驗證一律走「編譯 + 重新部署 + 手動情境測試 + log 檢查」（`logs/quicktranslate.log`）。
- Commit 訊息格式 `{type}: {說明}`（本次為 `feat:`）。

### Task 1: TranslateRunner 加入 axSelectedText() 並接進 terminal 分支

**Files:**
- Modify: `java-trigger/src/main/java/quicktranslate/TranslateRunner.java`

**Interfaces:**
- Consumes: 既有的 `Log.line(String)`、`nz(String)`、`readClipboard()`、`config.isTerminal(String)`。
- Produces: `private String axSelectedText()` — 成功回傳選取文字（可能為空字串 = 沒選取），**失敗（逾時／例外／osascript 非零結束）回傳 `null`**。Task 2 依賴的 log 欄位格式：`ax=hit|miss|error|n/a`。

- [ ] **Step 1: 加 import**

`TranslateRunner.java` 開頭 import 區（`java.util.concurrent.atomic.AtomicBoolean` 之後）加一行：

```java
import java.util.concurrent.TimeUnit;
```

- [ ] **Step 2: 新增 axSelectedText() 方法**

加在 `copyViaRobot()` 與 `readClipboard()` 之間：

```java
    /**
     * Ask Accessibility for the frontmost app's focused element's AXSelectedText. In a terminal
     * this is the native mouse selection (plain shell), which lets "select -> hotkey" translate
     * without any copy. TUIs that own the mouse (e.g. Claude Code's mouse-reporting mode) leave
     * the terminal with no native selection, so this reads "" and callers fall back to the
     * clipboard that copy-on-select already filled.
     *
     * Returns the selected text ("" when nothing is selected), or null on any failure — timeout,
     * osascript error (e.g. the focused element has no AXSelectedText attribute), or exception —
     * so callers treat failure exactly like "no selection". Uses the app's existing Accessibility
     * grant; no new permission prompt.
     */
    private String axSelectedText() {
        try {
            ProcessBuilder pb = new ProcessBuilder("/usr/bin/osascript",
                    "-e", "tell application \"System Events\"",
                    "-e", "set p to first application process whose frontmost is true",
                    "-e", "set fe to value of attribute \"AXFocusedUIElement\" of p",
                    "-e", "value of attribute \"AXSelectedText\" of fe",
                    "-e", "end tell");
            // launchd provides no LANG; without it osascript can emit the legacy system
            // encoding (Big5 on zh-TW Macs) — force UTF-8 to match the decode below
            pb.environment().put("LANG", "en_US.UTF-8");
            Process p = pb.start();
            if (!p.waitFor(1, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                Log.line("axSelectedText timeout");
                return null;
            }
            if (p.exitValue() != 0) return null;
            String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            // osascript terminates the result with a newline that is not part of the selection
            return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
        } catch (Exception e) {
            Log.line("axSelectedText error: " + e);
            return null;
        }
    }
```

- [ ] **Step 3: 改寫 run() 的 terminal 分支與 log**

把 `run()` 裡這段（現為 77–99 行）：

```java
            String selected;
            boolean changed;
            if (terminal) {
                // In a terminal the selection is owned by the app (e.g. Claude Code's copy-on-select
                // already put it on the clipboard). A synthetic Cmd+C would only hit Terminal's
                // disabled Copy menu and beep, so we skip it and use what's already there.
                selected = before;
                changed = false;
            } else {
                // Normal app: send Cmd+C WITHOUT clearing first (see class doc), then wait for the
                // clipboard to change so "select -> hotkey" works without a manual copy.
                copySelection();
                selected = waitForClipboardChange(before);
                changed = !selected.equals(before);
            }

            if (selected.isBlank()) {
                Log.line("no selection (clipboard empty) front=" + front + " terminal=" + terminal);
                return;
            }

            Log.line("translate front=" + front + " terminal=" + terminal + " changed=" + changed
                    + " len=" + selected.length() + " text=\"" + Log.preview(selected) + "\"");
```

整段換成：

```java
            String selected;
            boolean changed;
            String ax = "n/a";
            if (terminal) {
                // A synthetic Cmd+C would only hit the terminal's disabled Copy menu and beep, so
                // we never send one here. Instead, prefer the terminal's NATIVE selection read via
                // Accessibility (plain shell: select -> hotkey, no copy needed). TUIs that own the
                // mouse (e.g. Claude Code) leave no native selection — AX reads empty and we fall
                // back to the clipboard their copy-on-select already filled.
                String axSel = axSelectedText();
                ax = (axSel == null) ? "error" : (axSel.isBlank() ? "miss" : "hit");
                if ("hit".equals(ax)) {
                    selected = axSel;
                } else {
                    selected = before;
                }
                changed = false;
            } else {
                // Normal app: send Cmd+C WITHOUT clearing first (see class doc), then wait for the
                // clipboard to change so "select -> hotkey" works without a manual copy.
                copySelection();
                selected = waitForClipboardChange(before);
                changed = !selected.equals(before);
            }

            if (selected.isBlank()) {
                Log.line("no selection (clipboard empty) front=" + front + " terminal=" + terminal
                        + " ax=" + ax);
                return;
            }

            Log.line("translate front=" + front + " terminal=" + terminal + " ax=" + ax
                    + " changed=" + changed
                    + " len=" + selected.length() + " text=\"" + Log.preview(selected) + "\"");
```

- [ ] **Step 4: 更新類別 javadoc 的設計說明**

類別註解最後一段（`Copy is performed by...` 之前）加一段，讓下一個讀的人知道 terminal 分支現在有兩層：

```java
 * Terminal special case (see Config.terminalApps): we never synthesize Cmd+C there. First we ask
 * Accessibility for the terminal's native selection (AXSelectedText) so a plain-shell
 * "select -> hotkey" translates directly; if there is none (TUIs like Claude Code intercept the
 * mouse and copy-on-select instead), we translate whatever is already on the clipboard.
 *
```

- [ ] **Step 5: 編譯確認**

```bash
cd /Users/yeqilin/Workspace/macos-quick-translate/java-trigger && ./gradlew compileJava --console=plain
```

預期：`BUILD SUCCESSFUL`。失敗就修到過。

- [ ] **Step 6: Commit**

```bash
cd /Users/yeqilin/Workspace/macos-quick-translate
git add java-trigger/src/main/java/quicktranslate/TranslateRunner.java
git commit -m "feat: translate plain-terminal selections via AXSelectedText"
```

### Task 2: 重建 app、重新安裝並手動驗證四情境

**Files:**
- 無程式碼改動；產物為重新部署的 `/Applications/QuickTranslate.app` 與驗證紀錄。

**Interfaces:**
- Consumes: Task 1 的 log 欄位 `ax=hit|miss|error|n/a`；`scripts/install.sh`（會呼叫 `java-trigger/build_app.sh` 重建、換掉 /Applications 裡的 app、重啟 launchd agent）。
- Produces: 驗證過的部署。

- [ ] **Step 1: 重建並重新安裝**

```bash
cd /Users/yeqilin/Workspace/macos-quick-translate && ./scripts/install.sh
```

預期：`build_app.sh` 走完 jpackage、`launchctl bootstrap/kickstart` 成功、腳本結尾無錯誤。
注意：換新 binary 後若 macOS 重新要求輔助使用授權，到「系統設定 > 隱私權與安全性 > 輔助使用」重新勾 QuickTranslate。

- [ ] **Step 2: 開著 log 準備驗證**

```bash
tail -f logs/quicktranslate.log
```

- [ ] **Step 3: 情境 1 — 一般 terminal 選字（新行為）**

開一個沒跑 Claude Code 的 Terminal 視窗 → 反白畫面上一段英文 → 按熱鍵。
預期：跳出反白那段字的翻譯；log 出現 `translate front=com.apple.Terminal terminal=true ax=hit`；剪貼簿內容不變（可先 `pbcopy` 放個標記字串，事後 `pbpaste` 確認還在）。

- [ ] **Step 4: 情境 2 — Claude Code 內選字（不變）**

在 Claude Code 裡用滑鼠選字 → 按熱鍵。
預期：照常翻譯；log 為 `terminal=true ax=miss`（走剪貼簿）。

- [ ] **Step 5: 情境 3 — terminal 沒選字（不變）**

Terminal 前景、不選任何字 → 按熱鍵。
預期：翻剪貼簿最後內容（剪貼簿有東西）或 log 記 `no selection`（剪貼簿空）；`ax=miss`；無嗶聲。

- [ ] **Step 6: 情境 4 — 一般 app 選字（不變）**

在瀏覽器等一般 app 反白 → 按熱鍵。
預期：照常翻譯；log 為 `terminal=false ax=n/a changed=true`。

- [ ] **Step 7: 任一情境失敗的處置**

看 log 的 `ax=` 值與 `axSelectedText timeout/error` 行判斷是權限（重勾輔助使用）、逾時（調查 osascript 卡住原因）還是選取讀不到（該 app 焦點元件無 AXSelectedText，屬預期 fallback）。修正後回到 Step 1 重跑。

- [ ] **Step 8: 全數通過後收尾 commit（若驗證期間有改動）**

```bash
cd /Users/yeqilin/Workspace/macos-quick-translate && git status
```

有修正就以 `fix:` commit；無改動則跳過。
