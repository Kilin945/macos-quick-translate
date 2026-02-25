# macOS Quick Translate

A macOS Automator Quick Action that lets you instantly translate selected text to Traditional Chinese using Google Translate — triggered by a system-wide keyboard shortcut (**⌘/**).

## Features

- **One shortcut, anywhere** — works in any app that supports text selection
- **Auto language detection** — detects English vs Chinese input automatically
- **Mixed content support** — translates English portions in mixed Chinese/English text
- **Paginated results** — long translations split into pages with Previous / Next navigation
- **Smart page splitting** — cuts at natural boundaries (paragraphs, sentences, spaces) instead of mid-word
- **Clean output** — removes unnecessary line breaks from translation API responses
- Single word → macOS notification bubble
- Multiple words → dialog with pagination

## File Location

```
~/Library/Services/Google翻譯.workflow/Contents/document.wflow
```

The Python logic lives inside the `COMMAND_STRING` of the Automator plist.
The standalone script is at `translate.py` in this repo.

## Installation

### 1. Copy the workflow

```bash
cp -r "Google翻譯.workflow" ~/Library/Services/
```

### 2. Set the keyboard shortcut

1. Open **System Settings → Keyboard → Keyboard Shortcuts**
2. Select **Services** in the left panel
3. Under **Text**, find **Google翻譯** and enable it
4. Click the shortcut area and press **⌘/**

### 3. Grant permission (first use)

On first use, macOS will ask for permission to run the script. Click **Allow**.

## Usage

1. Select any text in any app
2. Press **⌘/**
3. Translation appears in a dialog

### Pagination buttons

| Page | Buttons |
|---|---|
| Single page | Close |
| First page | Close / **Next** |
| Middle page | Previous / Close / **Next** |
| Last page | Previous / **Close** |

Blue button = primary action for that page.

## Configuration

Edit `translate.py` (or the `COMMAND_STRING` inside `document.wflow`) to adjust:

```python
CHARS_PER_PAGE = 1000   # characters per page (increase for larger screens)
```

> **Note:** When editing `document.wflow` directly, XML-escape special characters:
> `>` → `&gt;` · `<` → `&lt;` · `&` → `&amp;`

## How It Works

```
Selected text
    ↓
detect_source_lang()   — counts Latin vs Chinese chars, picks sl=en or sl=auto
    ↓
translate()            — POST to Google Translate unofficial API (no key needed)
    ↓
normalize_text()       — merges mid-sentence line breaks from API response
    ↓
split_into_pages()     — splits at paragraph → line → sentence → space → hard cut
    ↓
show_dialog() / show_notification()
```

## Requirements

- macOS 12+
- Python 3 (pre-installed on macOS)
- Internet connection

## Known Limitations

- Uses Google Translate's unofficial API — no API key needed, but rate limits may apply
- Button layout uses macOS `display dialog` — buttons cannot be repositioned or given custom colors
- Right-side text alignment is ragged (system dialog limitation)
