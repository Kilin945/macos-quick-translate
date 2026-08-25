# macOS Quick Translate

> Press ⌘/ to instantly translate selected text to Traditional Chinese　|　按下 ⌘/ 立即翻譯選取文字為繁體中文

---

## English

### Features

- **Two ways to run** — a macOS Service, or a global-hotkey background app (`QuickTranslate.app`)
- **Customizable shortcut** — defaults to ⌘/; both modes can be rebound to whatever combo you prefer
- **Wide app coverage** — the Service covers apps that support the Services menu; the global-hotkey app isn't bound to the Services menu and works in any app where ⌘C can copy the selection (selectable, copyable text — not images or copy-blocked fields)
- **Auto language detection** — handles English, Chinese, and mixed content correctly
- **Variable name translation** — `snake_case` and `kebab-case` (word-word dashes only) are split into words before translating; standalone `- item` bullet markers are preserved
- **Bullet list formatting** — bullet list structure is preserved through translation so `- item` lines stay on separate lines
- **Numbered list formatting** — numbered list items in translated output are automatically placed on new lines
- **Native result window** — always-on-top, scrollable, text selectable/copyable; page the document behind it and compare
- **Smart page splitting** — breaks at paragraphs, sentences, or spaces — never mid-word
- **Stable** — retries once on transient API failure; falls back to notification if dialog is unavailable
- **3 words or fewer** → macOS notification; **More than 3 words** → native scrollable window

### Installation

**1. Copy the workflow**
```bash
cp -r "Google翻譯.workflow" ~/Library/Services/
```

**2. Set the keyboard shortcut**
1. Open **System Settings → Keyboard → Keyboard Shortcuts**
2. Select **Services** in the left panel
3. Under **Text**, find **Google翻譯** and enable it
4. Click the shortcut area and press **⌘/**

**3. Grant permission on first use**
macOS will ask for permission to run the script. Click **Allow**.

### Usage

1. Select any text in any app
2. Press **⌘/**
3. Translation appears in a notification or dialog

### Global Hotkey App — works in *every* app (`java-trigger/`)

The macOS Service above only works in apps that hand selected text to the Services menu. Some apps (claude.ai in the browser, Mail, the Claude desktop app) don't, so ⌘/ does nothing there.

`java-trigger/` solves this: a small background app (`QuickTranslate.app`) that listens for a global hotkey and feeds the selected text to the same `translate.py` — unchanged — showing the same dialog / notification.

How it grabs the selection:
- **Normal apps** — it simulates ⌘C to copy the selection, then translates the result.
- **Terminal apps** (those listed in `terminal_apps`, e.g. Claude Code running in Terminal.app) — the terminal owns the selection itself (copy-on-select / Ctrl+C), so the app does **not** send ⌘C (which would only hit the terminal's disabled Copy menu and beep). It just translates what the terminal already placed on the clipboard.

It never clears the clipboard, so a selection you copied yourself is never destroyed.

**Install — double-click `install.command`** in Finder (or run `./scripts/install.sh`). It builds the app, installs it to Applications, sets up auto-start at login + crash auto-restart, and opens the Accessibility settings pane — just switch **QuickTranslate** on. First time, if macOS blocks it: right-click → Open. Remove everything with `./scripts/uninstall.sh`.

<details><summary>Build &amp; install by hand instead</summary>

```bash
cd java-trigger
./build_app.sh                                              # → build/jpackage/QuickTranslate.app
ditto build/jpackage/QuickTranslate.app /Applications/QuickTranslate.app
open /Applications/QuickTranslate.app
```
</details>

The two sections below (Accessibility, Auto-start) are what `install.command` automates — read on only if installing by hand.

**Grant Accessibility:** System Settings → Privacy & Security → Accessibility → enable `QuickTranslate.app` (needed so it can send ⌘C to copy the selection in normal apps; the global hotkey itself is registered via macOS Carbon and needs no permission). The grant is path-based and usually survives rebuilds; if copy stops working in normal apps after a rebuild, remove and re-add `QuickTranslate.app` in that list.

**Auto-start at login + crash auto-restart:** install the bundled LaunchAgent template (run from the repo root; `sed` fills in this repo's path).
```bash
sed "s#__PROJECT_DIR__#$(pwd)#g" scripts/com.quicktranslate.plist.sample > ~/Library/LaunchAgents/com.quicktranslate.plist
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.quicktranslate.plist
```
It starts at login (`RunAtLoad`) and relaunches within seconds if it ever crashes (`KeepAlive`). Stop it with `launchctl bootout gui/$(id -u)/com.quicktranslate`. (A built-in single-instance lock means it's safe even if an old Login Item also launches it.)

**Disable the old Service binding** to avoid a key conflict: System Settings → Keyboard → Keyboard Shortcuts → Services → uncheck ⌘/ on Google翻譯.

**Configure the hotkey** — edit `~/.quicktranslate.conf` (auto-created on first run), then restart with `pkill -f QuickTranslate.app && open /Applications/QuickTranslate.app`:
```properties
hotkey = cmd+'              # also e.g. cmd+shift+t, ctrl+alt+space
python = /opt/homebrew/bin/python3
script = /path/to/translate.py
copy_delay_ms = 150         # max wait for the clipboard to update after ⌘C
terminal_apps = com.apple.Terminal   # comma-separated bundle ids where ⌘C is NOT sent
                                     # (selection owned by the app, e.g. copy-on-select);
                                     # add others, e.g. com.googlecode.iterm2
```

> In normal apps it sends ⌘C and translates the fresh selection; in `terminal_apps` it translates whatever the terminal already copied. The clipboard is never cleared. A selection that can't be copied (images, copy-blocked fields) can't be translated; pressing the hotkey with nothing selected may translate whatever is currently on the clipboard.

**Logs:** every trigger is recorded to `logs/quicktranslate.log` (frontmost app, whether the clipboard changed, the copied text), rotated daily and auto-pruned after 30 days. Raw stdout/stderr (crashes) go to `logs/quicktranslate.out.log`.

### Result window

The full translation is shown in one native scrollable window (`showdialog` helper) — text is selectable and copyable. The window **floats above all normal windows**, so you can scroll or page the document behind it and compare against the translation. It closes on: the Close button / Return, Esc (when the window has focus), the title-bar red button, or a new translation replacing it (one window at a time — a new hotkey press swaps the content in place of stacking windows). If the parent process dies, the window closes itself within seconds, so an abandoned window can never stay on screen.

### Development

Only edit `translate.py`. The pre-commit hook automatically syncs changes to `document.wflow` and deploys to `~/Library/Services/` on every commit.

```
translate.py  ──(git commit triggers hook)──▶  document.wflow  ──(auto deploy)──▶  ~/Library/Services/
```

To sync manually:
```bash
python3 scripts/build_workflow.py
```

### How It Works

```
Selected text
    ↓
normalize input   — replaces _ with spaces; replaces word-word dashes (kebab-case)
                    with spaces; bullet "- item" markers are preserved
    ↓
normalize_text()      — collapses multi-line paragraphs into one line,
                        but keeps bullet list items on separate lines so the
                        API receives proper list structure
    ↓
detect_source_lang()  — counts Latin vs Chinese chars → picks sl=en or sl=auto
    ↓
translate()           — POST to Google Translate unofficial API (no API key needed)
                        retries once on transient failure
    ↓
normalize_text()      — merges mid-sentence line breaks from API response
    ↓
numbered list fix     — inserts newlines before list item numbers (2. 3. …)
bullet list fix       — restores inline "- item" separators to newlines (fallback)
    ↓
show_notification() / show_dialog()   — ≤3 input words → notification, else → native window
                                        (showdialog helper); falls back to notification if
                                        the helper is missing or fails
```

### Debugging

Translation events are logged to `/tmp/translate_debug.log`:

```bash
tail -f /tmp/translate_debug.log
```

Each run logs the input, result, and any errors.

### Requirements

- macOS 12+
- Python 3 (pre-installed on macOS)
- Internet connection

### Known Limitations

- Uses Google Translate's unofficial API — no API key needed, but may have rate limits
- **Global-hotkey app only translates what ⌘C can copy** — selections that can't be copied (images, copy-blocked fields, scanned PDFs without a text layer) cannot be translated

---

## 中文說明

### 功能特色

- **兩種執行方式** — macOS 服務，或全域熱鍵背景 App（`QuickTranslate.app`）
- **快捷鍵可自訂** — 預設 ⌘/，兩種方式都能改成你習慣的組合鍵
- **適用範圍廣** — 服務支援有「服務選單」的 App；全域熱鍵 App 不受服務選單限制，支援任何「⌘C 複製得到文字」的 App（可選取、可複製的文字；圖片或禁止複製的欄位則不行）
- **自動偵測語言** — 英文、中文、中英混合都能正確翻譯
- **變數名稱翻譯** — `snake_case` 和 `kebab-case`（只替換字母之間的 `-`）自動拆字翻譯；bullet `- item` 的 `-` 不受影響
- **Bullet list 格式保留** — 翻譯後 `- item` 清單結構保持換行，不會被合併成一行
- **編號清單換行** — 翻譯結果中的編號清單自動換行
- **原生結果視窗** — 永遠置頂、可捲動、文字可選取複製；後面的文件可以邊翻頁邊對照
- **智慧斷頁** — 優先在段落、句子結尾斷頁，不會切斷句子
- **穩定性** — API 失敗自動重試一次；對話框無法顯示時 fallback 為通知泡泡
- **3 個字以內** → macOS 通知泡泡；**超過 3 個字** → 原生可捲動視窗

### 安裝方式

**1. 複製 Workflow**
```bash
cp -r "Google翻譯.workflow" ~/Library/Services/
```

**2. 設定快捷鍵**
1. 打開 **系統設定 → 鍵盤 → 鍵盤快速鍵**
2. 左側選 **服務**
3. 在「文字」分類找到 **Google翻譯**，勾選啟用
4. 點擊右側空白處，按下 **⌘/**

**3. 第一次使用授權**
首次執行時 macOS 會詢問是否允許執行 Script，點 **允許** 即可。

### 使用方法

1. 在任何 App 中選取文字
2. 按下 **⌘/**
3. 翻譯結果出現在通知或對話框

### 全域熱鍵 App — 真正在「每個」App 都能用（`java-trigger/`）

上面的 macOS 服務只在「會把選取文字交給服務選單」的 App 生效。有些 App（瀏覽器裡的 claude.ai、Mail、Claude 桌面 App）不交，所以在那邊按 ⌘/ 沒反應。

`java-trigger/` 解決這個問題：一個小的背景 App（`QuickTranslate.app`），監聽全域熱鍵，把選取文字交給同一支 `translate.py`（完全沒改），跳出一樣的對話框 / 通知。

取得選取文字的方式：
- **一般 App** — 模擬 ⌘C 複製選取，再翻譯結果。
- **終端機 App**（列在 `terminal_apps` 裡的，例如在 Terminal.app 裡跑的 Claude Code）— 選取是由 App 自己管的（選取即複製 / Ctrl+C），所以**不送** ⌘C（送了也只會打到終端機反灰的拷貝選單而「兜」一聲）。直接翻譯終端機已經放進剪貼簿的內容。

它**不會清空剪貼簿**，所以你自己複製好的東西不會被破壞。

**安裝 — 在 Finder 點兩下 `install.command`**（或執行 `./scripts/install.sh`）。它會自動 build、把 App 裝進「應用程式」、設定開機自啟 + 崩潰自動重啟，並跳出「輔助使用」設定頁——把 **QuickTranslate** 的開關打開即可。第一次若被 macOS 擋下：對檔案按右鍵 → 打開。移除用 `./scripts/uninstall.sh`。

<details><summary>改用手動建置與安裝</summary>

```bash
cd java-trigger
./build_app.sh                                              # → build/jpackage/QuickTranslate.app
ditto build/jpackage/QuickTranslate.app /Applications/QuickTranslate.app
open /Applications/QuickTranslate.app
```
</details>

下面兩段（輔助使用、開機自動啟動）就是 `install.command` 幫你自動做的——想手動裝才需要看。

**授權輔助使用：** 系統設定 → 隱私權與安全性 → 輔助使用 → 開啟 `QuickTranslate.app`（在一般 App 送出 ⌘C 複製選取文字需要這個權限；全域熱鍵本身透過 macOS Carbon 註冊，不需要權限）。授權是綁路徑的，通常重新打包後仍有效；若重建後一般 App 複製失效，把 `QuickTranslate.app` 從清單移除再重新加入即可。

**開機自動啟動 + 崩潰自動重啟：** 安裝內附的 LaunchAgent 範本（在 repo 根目錄執行，`sed` 會填入這個 repo 的路徑）。
```bash
sed "s#__PROJECT_DIR__#$(pwd)#g" scripts/com.quicktranslate.plist.sample > ~/Library/LaunchAgents/com.quicktranslate.plist
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.quicktranslate.plist
```
它會開機自啟（`RunAtLoad`），崩潰時幾秒內自動重啟（`KeepAlive`）。停止：`launchctl bootout gui/$(id -u)/com.quicktranslate`。（內建單例鎖，就算還留著舊的登入項目也不會跑出兩份。）

**停用舊的服務綁定** 以免搶鍵：系統設定 → 鍵盤 → 鍵盤快速鍵 → 服務 → 取消「Google翻譯」的 ⌘/。

**設定熱鍵** — 編輯 `~/.quicktranslate.conf`（第一次執行會自動產生），改完用 `pkill -f QuickTranslate.app && open /Applications/QuickTranslate.app` 重啟：
```properties
hotkey = cmd+'              # 也可以是 cmd+shift+t、ctrl+alt+space
python = /opt/homebrew/bin/python3
script = /path/to/translate.py
copy_delay_ms = 150         # ⌘C 後等剪貼簿更新的最長時間
terminal_apps = com.apple.Terminal   # 不送 ⌘C 的終端機 bundle id（選取由 App 自己管，
                                     # 例如選取即複製）；逗號分隔，可加 com.googlecode.iterm2
```

> 一般 App 會送 ⌘C 翻譯當下選取；`terminal_apps` 裡的則直接翻譯終端機已經複製好的內容。**不會清空剪貼簿。** 無法複製的選取（圖片、禁止複製的欄位）翻不了；沒選任何文字就按熱鍵，可能會翻到剪貼簿裡現有的內容。

**Log：** 每次觸發都記錄到 `logs/quicktranslate.log`（前景 App、剪貼簿有沒有變、複製到的文字），每日輪替、超過 30 天自動清除。原始 stdout/stderr（崩潰）寫到 `logs/quicktranslate.out.log`。

### 結果視窗說明

完整翻譯以單一原生可捲動視窗顯示（`showdialog` helper），文字可選取複製。視窗**永遠浮在最上層**——可以邊捲動、翻頁後面的文件邊對照翻譯。關閉方式：Close 按鈕 / Enter、Esc（視窗有焦點時）、標題列紅鈕，或新的翻譯直接取代它（同時只有一個視窗，再按熱鍵是換內容不是疊視窗）。父程序死亡時視窗數秒內自動收掉，被遺棄的視窗不會永遠留在螢幕上。

### 開發說明

只需要編輯 `translate.py`，commit 時 pre-commit hook 會自動同步到 `.wflow` 並部署到 `~/Library/Services/`。

```
translate.py  ──(git commit 自動觸發)──▶  document.wflow  ──(自動部署)──▶  ~/Library/Services/
```

手動執行同步：
```bash
python3 scripts/build_workflow.py
```

### 運作原理

```
選取文字
    ↓
正規化輸入   — 將 _ 替換為空白；只替換字母之間的 kebab-case `-`
              bullet "- item" 的 `-` 保留不動
    ↓
normalize_text()      — 多行段落合併成一行，但 bullet list 段落
                        保留換行，讓 API 看到完整的清單結構
    ↓
detect_source_lang()  — 統計英文 / 中文字元比例 → 決定 sl=en 或 sl=auto
    ↓
translate()           — POST 到 Google Translate 非官方 API（不需要 API key）
                        失敗自動重試一次
    ↓
normalize_text()      — 合併 API 回傳結果中的句中換行
    ↓
編號清單修正  — 在清單項目編號（2. 3. …）前自動插入換行
Bullet 修正   — 翻譯結果中仍有 inline "- item" 時補插換行（fallback）
    ↓
show_notification() / show_dialog()   — 輸入 ≤3 個字 → 通知泡泡，否則 → 原生視窗
                                        （showdialog helper）；helper 缺失或失敗時
                                        fallback 為通知泡泡
```

### 除錯

翻譯事件會記錄到 `/tmp/translate_debug.log`：

```bash
tail -f /tmp/translate_debug.log
```

每次觸發都會記錄輸入內容、翻譯結果、以及所有錯誤訊息。

### 系統需求

- macOS 12+
- Python 3（macOS 內建）
- 網路連線

### 已知限制

- 使用 Google Translate 非官方 API — 不需要 API key，但可能有請求頻率限制
- **全域熱鍵 App 只能翻「⌘C 複製得到的東西」** — 無法複製的選取（圖片、禁止複製的欄位、沒有文字層的掃描 PDF）就無法翻譯
