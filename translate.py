import sys, json, os, re, urllib.request, urllib.parse, subprocess, time, traceback, logging

# use logging module so the file stays open across calls, avoiding per-call open/close overhead
logging.basicConfig(
    filename='/tmp/translate_debug.log',
    level=logging.DEBUG,
    format='[%(asctime)s] %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
log = logging.info

def _preview(s, n=80):
    return s[:n] + '...' if len(s) > n else s

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
    last_err = None
    # retry once on transient network failure; sleep 1s to avoid immediate rate-limit
    for attempt in range(2):
        try:
            req = urllib.request.Request(url, data=data, method='POST')
            req.add_header('Content-Type', 'application/x-www-form-urlencoded')
            with urllib.request.urlopen(req, timeout=15) as resp:
                result_data = json.loads(resp.read().decode('utf-8'))
            return ''.join([item[0] for item in result_data[0] if item[0]])
        except Exception as e:
            last_err = e
            log(f'translate attempt {attempt+1} failed: {e}')
            if attempt == 0:
                time.sleep(1)
    raise last_err

def normalize_text(text):
    paragraphs = re.split(r'\n{2,}', text)
    cleaned = []
    for p in paragraphs:
        lines = [l.strip() for l in p.split('\n') if l.strip()]
        if not lines:
            continue
        # bullet list paragraphs must keep their line breaks so the API receives
        # "- item" on its own line; collapsing them into one line causes the API
        # to strip the "- " markers, losing all list structure after translation
        if any(l.startswith('- ') for l in lines):
            cleaned.append('\n'.join(lines))
        else:
            cleaned.append(' '.join(lines))
    return '\n\n'.join(cleaned)

def show_notification(text):
    script = 'on run argv\ndisplay notification (item 1 of argv) with title "Google Translate"\nend run'
    # truncate here so every caller is safe; macOS notification has a character limit
    subprocess.run(['osascript', '-e', script, '--', text[:200]])

CHARS_PER_PAGE = 1000

def split_into_pages(text, max_chars):
    if len(text) <= max_chars:
        return [text]
    chunks = []
    while len(text) > max_chars:
        chunk = text[:max_chars]
        min_break = max_chars * 9 // 10
        pos = chunk.rfind('\n\n')
        if pos > min_break:
            chunks.append(text[:pos].rstrip())
            text = text[pos+2:].lstrip()
            continue
        pos = chunk.rfind('\n')
        if pos > min_break:
            chunks.append(text[:pos].rstrip())
            text = text[pos+1:].lstrip()
            continue
        best_pos = -1
        for punct in ['。', '！', '？', '. ', '! ', '? ']:
            p = chunk.rfind(punct)
            if p > min_break and p + len(punct) > best_pos:
                best_pos = p + len(punct)
        if best_pos > 0:
            chunks.append(text[:best_pos].rstrip())
            text = text[best_pos:].lstrip()
            continue
        pos = chunk.rfind(' ')
        if pos > min_break:
            chunks.append(text[:pos].rstrip())
            text = text[pos+1:].lstrip()
            continue
        chunks.append(text[:max_chars])
        text = text[max_chars:]
    if text:
        chunks.append(text)
    return chunks

def _run_dialog_page(chunk, title, buttons, default_button):
    btn_str = ', '.join(f'"{b}"' for b in buttons)
    script = f'on run argv\ndisplay dialog (item 1 of argv) with title (item 2 of argv) buttons {{{btn_str}}} default button "{default_button}"\nend run'
    r = subprocess.run(['osascript', '-e', script, '--', chunk, title], capture_output=True, text=True)
    if r.returncode != 0 and r.stderr:
        log(f'osascript dialog error: {r.stderr.strip()}')
    return r

def show_dialog(text):
    chunks = split_into_pages(text, CHARS_PER_PAGE)
    total = len(chunks)
    i = 0
    while 0 <= i < total:
        chunk = chunks[i]
        title = f"Google Translate ({i+1}/{total})" if total > 1 else "Google Translate"
        # compute buttons based on position; single error check handles all branches
        if total == 1:
            buttons, default = ['Close'], 'Close'
        elif i == 0:
            buttons, default = ['Close', 'Next'], 'Next'
        elif i == total - 1:
            buttons, default = ['Previous', 'Close'], 'Close'
        else:
            buttons, default = ['Previous', 'Close', 'Next'], 'Next'
        r = _run_dialog_page(chunk, title, buttons, default)
        if r.returncode != 0:
            # dialog failed (e.g. no UI access in Terminal); fall back to notification
            log('dialog failed, falling back to notification')
            show_notification(text)
            return
        # osascript returns "button returned:Next" — parse the last segment exactly
        clicked = r.stdout.strip().split(':')[-1].strip()
        if clicked == 'Next':
            i += 1
        elif clicked == 'Previous':
            i -= 1
        else:
            break

text = os.environ.get('TRANSLATE_INPUT', '').strip()
if not text:
    log('INPUT empty, exit')
    sys.exit(0)

word_count = len(text.split())
log(f'INPUT ({word_count}w): {_preview(text)}')

# replace underscores and kebab-case dashes (word-word only) for identifier translation;
# bullet "  - item" dashes are preserved because no word char precedes them
text = text.replace('_', ' ')
text = re.sub(r'(?<=\w)-(?=\w)', ' ', text)

try:
    result = translate(text)
except Exception as e:
    log(f'translate ERROR: {traceback.format_exc()}')
    show_dialog(f"Translation failed: {str(e)[:100]}")
    sys.exit(1)

if not result:
    log('translate returned empty')
    show_dialog("Translation failed (empty result)")
    sys.exit(1)

result = normalize_text(result)
result = re.sub(r'(?<=\S)\s*((?:[2-9]|[1-9]\d+)\.)(?!\d)', r'\n\1', result)
# bullet list fallback: if API still produces inline "- item" or "）- item" separators,
# restore the newline (primary fix is in normalize_text, this catches edge cases)
result = re.sub(r'(?<=[^\n]) - (?=[^\s-])', '\n- ', result)
result = re.sub(r'(?<=[）】\)\]])\s*-\s+(?=[^\s-])', '\n- ', result)
result = result.lstrip('\n')

log(f'RESULT: {_preview(result)}')

if word_count <= 3:
    show_notification(result)
else:
    show_dialog(result)
