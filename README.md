# macOS Quick Translate

> Press ⌘/ to instantly translate selected text to Traditional Chinese　|　按下 ⌘/ 立即翻譯選取文字為繁體中文

---

## English

### Features

- **Works anywhere** — any app that supports text selection
- **Auto language detection** — handles English, Chinese, and mixed content correctly
- **Variable name translation** — `snake_case` and `kebab-case` are split into words before translating (`_` and `-` treated as spaces)
- **Paginated results** — long translations split into pages with Previous / Next navigation
- **Smart page splitting** — breaks at paragraphs, sentences, or spaces — never mid-word
- **Numbered list formatting** — numbered list items in translated output are automatically placed on new lines
- **3 words or fewer** → macOS notification; **More than 3 words** → dialog with pagination

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

### Pagination

| Page | Buttons |
|---|---|
| Single page | Close |
| First page | Close / **Next** |
| Middle page | Previous / Close / **Next** |
| Last page | Previous / **Close** |

### Configuration

Edit `translate.py` to adjust characters per page:
```python
CHARS_PER_PAGE = 1000   # increase for larger screens
```

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
normalize input   — replaces _ and - with spaces
    ↓
detect_source_lang()  — counts Latin vs Chinese chars → picks sl=en or sl=auto
    ↓
translate()           — POST to Google Translate unofficial API (no API key needed)
    ↓
normalize_text()      — merges mid-sentence line breaks from API response
    ↓
numbered list fix     — inserts newlines before list item numbers
    ↓
split_into_pages()    — splits at paragraph → line → sentence → space → hard cut
    ↓
show_notification() / show_dialog()   — ≤3 input words → notification, else → dialog
```

### Requirements

- macOS 12+
- Python 3 (pre-installed on macOS)
- Internet connection

### Known Limitations

- Uses Google Translate's unofficial API — no API key needed, but may have rate limits
- Button layout is controlled by macOS system dialog — positions and colors cannot be fully customized

---

## 中文說明

### 功能特色

- **任何 App 都能用** — 只要能選取文字，按 ⌘/ 就能翻譯
- **自動偵測語言** — 英文、中文、中英混合都能正確翻譯
- **變數名稱翻譯** — `snake_case` 和 `kebab-case` 自動拆字翻譯（`_` 和 `-` 視為空白）
- **分頁顯示** — 長文翻譯結果自動分頁，支援上一頁 / 下一頁瀏覽
- **智慧斷頁** — 優先在段落、句子結尾斷頁，不會切斷句子
- **智慧斷行** — 翻譯結果中的編號清單自動換行
- **3 個字以內** → macOS 通知泡泡；**超過 3 個字** → 對話框

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

### 分頁按鈕說明

| 頁面 | 按鈕 |
|---|---|
| 單頁 | Close |
| 第一頁 | Close / **Next** |
| 中間頁 | Previous / Close / **Next** |
| 最後一頁 | Previous / **Close** |

### 設定調整

編輯 `translate.py` 可調整每頁字數：
```python
CHARS_PER_PAGE = 1000   # 增加數字 = 每頁顯示更多字
```

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
正規化輸入   — 將 _ 和 - 替換為空白
    ↓
detect_source_lang()  — 統計英文 / 中文字元比例 → 決定 sl=en 或 sl=auto
    ↓
translate()           — POST 到 Google Translate 非官方 API（不需要 API key）
    ↓
normalize_text()      — 合併 API 回傳結果中的句中換行
    ↓
編號清單修正  — 在清單項目編號前自動插入換行
    ↓
split_into_pages()    — 依段落 → 句子 → 空白 → 強制截斷的順序分頁
    ↓
show_notification() / show_dialog()   — 輸入 ≤3 個字 → 通知泡泡，否則 → 對話框
```

### 系統需求

- macOS 12+
- Python 3（macOS 內建）
- 網路連線

### 已知限制

- 使用 Google Translate 非官方 API — 不需要 API key，但可能有請求頻率限制
- 對話框按鈕樣式由 macOS 系統控制，位置與顏色無法自訂
