import sys, json, os, re, urllib.request, urllib.parse, subprocess

def detect_source_lang(text):
    latin = len(re.findall(r'[a-zA-Z]', text))
    chinese = len(re.findall(r'[\u4e00-\u9fff]', text))
    total = latin + chinese
    if total == 0:
        return 'auto'
    return 'en' if (latin / total) > 0.2 else 'auto'

def translate(text):
    sl = detect_source_lang(text)
    url = f"https://translate.googleapis.com/translate_a/single?client=gtx&sl={sl}&tl=zh-TW&dt=t"
    data = urllib.parse.urlencode({'q': text}).encode('utf-8')
    req = urllib.request.Request(url, data=data, method='POST')
    req.add_header('Content-Type', 'application/x-www-form-urlencoded')
    with urllib.request.urlopen(req, timeout=15) as resp:
        result_data = json.loads(resp.read().decode('utf-8'))
    return ''.join([item[0] for item in result_data[0] if item[0]])

def normalize_text(text):
    paragraphs = re.split(r'\n{2,}', text)
    cleaned = []
    for p in paragraphs:
        lines = [line.strip() for line in p.split('\n')]
        lines = [l for l in lines if l]
        if lines:
            cleaned.append(' '.join(lines))
    return '\n\n'.join(cleaned)

def show_notification(text):
    script = 'on run argv\ndisplay notification (item 1 of argv) with title "Google Translate"\nend run'
    subprocess.run(['osascript', '-e', script, '--', text])

CHARS_PER_PAGE = 1000

def split_into_pages(text, max_chars):
    if len(text) <= max_chars:
        return [text]
    chunks = []
    while len(text) > max_chars:
        chunk = text[:max_chars]
        pos = chunk.rfind('\n\n')
        if pos > max_chars * 9 // 10:
            chunks.append(text[:pos].rstrip())
            text = text[pos+2:].lstrip()
            continue
        pos = chunk.rfind('\n')
        if pos > max_chars * 9 // 10:
            chunks.append(text[:pos].rstrip())
            text = text[pos+1:].lstrip()
            continue
        best_pos = -1
        for punct in ['。', '！', '？', '. ', '! ', '? ']:
            p = chunk.rfind(punct)
            if p > max_chars * 9 // 10 and p + len(punct) > best_pos:
                best_pos = p + len(punct)
        if best_pos > 0:
            chunks.append(text[:best_pos].rstrip())
            text = text[best_pos:].lstrip()
            continue
        pos = chunk.rfind(' ')
        if pos > max_chars * 9 // 10:
            chunks.append(text[:pos].rstrip())
            text = text[pos+1:].lstrip()
            continue
        chunks.append(text[:max_chars])
        text = text[max_chars:]
    if text:
        chunks.append(text)
    return chunks

def show_dialog(text):
    chunks = split_into_pages(text, CHARS_PER_PAGE)
    total = len(chunks)
    i = 0
    while i >= 0 and i < total:
        chunk = chunks[i]
        title = f"Google Translate ({i+1}/{total})" if total > 1 else "Google Translate"
        is_first = (i == 0)
        is_last = (i == total - 1)
        if total == 1:
            script = 'on run argv\ndisplay dialog (item 1 of argv) with title (item 2 of argv) buttons {"Close"} default button "Close"\nend run'
            subprocess.run(['osascript', '-e', script, '--', chunk, title])
            break
        elif is_first:
            script = 'on run argv\ndisplay dialog (item 1 of argv) with title (item 2 of argv) buttons {"Close", "Next"} default button "Next"\nend run'
            result = subprocess.run(['osascript', '-e', script, '--', chunk, title], capture_output=True, text=True)
            if 'Next' in result.stdout:
                i += 1
            else:
                break
        elif is_last:
            script = 'on run argv\ndisplay dialog (item 1 of argv) with title (item 2 of argv) buttons {"Previous", "Close"} default button "Close"\nend run'
            result = subprocess.run(['osascript', '-e', script, '--', chunk, title], capture_output=True, text=True)
            if 'Previous' in result.stdout:
                i -= 1
            else:
                break
        else:
            script = 'on run argv\ndisplay dialog (item 1 of argv) with title (item 2 of argv) buttons {"Previous", "Close", "Next"} default button "Next"\nend run'
            result = subprocess.run(['osascript', '-e', script, '--', chunk, title], capture_output=True, text=True)
            if 'Next' in result.stdout:
                i += 1
            elif 'Previous' in result.stdout:
                i -= 1
            else:
                break

text = os.environ.get('TRANSLATE_INPUT', '').strip()
if not text:
    sys.exit(0)

word_count = len(text.split())

try:
    result = translate(text)
except Exception as e:
    show_dialog(f"Translation failed: {str(e)[:100]}")
    sys.exit(1)

if not result:
    show_dialog("Translation failed (empty result)")
    sys.exit(1)

result = normalize_text(result)

if word_count <= 1:
    show_notification(result)
else:
    show_dialog(result)
