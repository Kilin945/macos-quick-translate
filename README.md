# macOS Quick Translate

> 按下 ⌘/ 立即翻譯選取文字為繁體中文　|　Press ⌘/ to instantly translate selected text to Traditional Chinese

---

## 中文說明

### 功能特色

- **任何 App 都能用** — 只要能選取文字，按 ⌘/ 就能翻譯
- **自動偵測語言** — 英文、中文、中英混合都能正確翻譯
- **分頁顯示** — 長文翻譯結果自動分頁，支援上一頁 / 下一頁瀏覽
- **智慧斷頁** — 優先在段落、句子結尾斷頁，不會切斷句子
- **單字** → macOS 通知泡泡；**多字** → 對話框

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
3. 翻譯結果出現在對話框

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

> ⚠️ 若直接編輯 `document.wflow`，特殊字元需 XML 轉義：
> `>` → `&gt;` · `<` → `&lt;` · `&` → `&amp;`

### 檔案位置

```
~/Library/Services/Google翻譯.workflow/Contents/document.wflow
```

---

## English

### Features

- **Works anywhere** — any app that supports text selection
- **Auto language detection** — handles English, Chinese, and mixed content correctly
- **Paginated results** — long translations split into pages with Previous / Next navigation
- **Smart page splitting** — breaks at paragraphs, sentences, or spaces — never mid-word
- **Single word** → macOS notification; **Multiple words** → dialog with pagination

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
3. Translation appears in a dialog

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

> ⚠️ When editing `document.wflow` directly, XML-escape special characters:
> `>` → `&gt;` · `<` → `&lt;` · `&` → `&amp;`

### File Location

```
~/Library/Services/Google翻譯.workflow/Contents/document.wflow
```

### How It Works

```
Selected text
    ↓
detect_source_lang()  — counts Latin vs Chinese chars → picks sl=en or sl=auto
    ↓
translate()           — POST to Google Translate unofficial API (no API key needed)
    ↓
normalize_text()      — merges mid-sentence line breaks from API response
    ↓
split_into_pages()    — splits at paragraph → line → sentence → space → hard cut
    ↓
show_dialog() / show_notification()
```

### Requirements

- macOS 12+
- Python 3 (pre-installed on macOS)
- Internet connection

### Known Limitations

- Uses Google Translate's unofficial API — no API key needed, but may have rate limits
- Button layout is controlled by macOS system dialog — positions and colors cannot be fully customized
