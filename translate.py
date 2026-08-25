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

# Offline fallback: Apple's on-device Translation framework via the bundled `translatenative`
# helper (macOS 26+). Used when the Google endpoint is unreachable (e.g. Errno 8 DNS failure
# with no network). Requires the en->zh-Hant language pack downloaded in System Settings >
# General > Language & Region > Translation Languages.
NATIVE_HELPER_CANDIDATES = [
    '/Applications/QuickTranslate.app/Contents/MacOS/translatenative',
    os.path.join(os.path.dirname(os.path.abspath(__file__)),
                 'java-trigger', 'build', 'translatenative'),
]

def translate_offline(text):
    if detect_source_lang(text) != 'en':
        # the native session needs an explicit *installed* source language; we only keep the
        # en->zh-Hant pack, so non-English input has no offline path
        log('offline fallback skipped: source not detected as en')
        return None
    helper = next((p for p in NATIVE_HELPER_CANDIDATES if os.access(p, os.X_OK)), None)
    if helper is None:
        log('offline fallback unavailable: translatenative helper not found')
        return None
    try:
        # helper self-kills after 30s (watchdog); this outer timeout is the safety net
        r = subprocess.run([helper, 'en', 'zh-Hant'], input=text.encode('utf-8'),
                           capture_output=True, timeout=35)
    except Exception as e:
        log(f'offline fallback error: {e}')
        return None
    if r.returncode != 0:
        log(f'offline fallback exit={r.returncode} err={r.stderr.decode("utf-8", "replace").strip()}')
        return None
    return r.stdout.decode('utf-8', 'replace').strip() or None

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

# Result window: native showdialog helper (scrollable/selectable window, closes on Esc /
# focus loss / orphan sweep). Replaces `osascript display dialog`, whose run loop the input
# method (IMK) could wedge via a failed mach port handshake — window visible, clicks dead.
# The full text goes in one window, so no pagination is needed.
DIALOG_HELPER_CANDIDATES = [
    '/Applications/QuickTranslate.app/Contents/MacOS/showdialog',
    os.path.join(os.path.dirname(os.path.abspath(__file__)),
                 'java-trigger', 'build', 'showdialog'),
]

def show_dialog(text):
    helper = next((p for p in DIALOG_HELPER_CANDIDATES if os.access(p, os.X_OK)), None)
    if helper is None:
        log('showdialog helper not found, falling back to notification')
        show_notification(text)
        return
    try:
        # blocks until the user closes the window (same lifetime as the old osascript dialog);
        # the helper self-closes on focus loss, so this cannot hang unattended forever
        r = subprocess.run([helper, 'Google Translate'], input=text.encode('utf-8'),
                           capture_output=True)
    except Exception as e:
        log(f'showdialog error: {e}')
        show_notification(text)
        return
    if r.returncode != 0:
        log(f'showdialog exit={r.returncode} err={r.stderr.decode("utf-8", "replace").strip()}')
        show_notification(text)

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
    result = translate_offline(text)
    if result:
        log(f'offline fallback OK: {_preview(result)}')
    else:
        msg = str(e)[:100]
        # Errno 8 = getaddrinfo failure: no usable network/DNS at all, say so plainly
        if 'Errno 8' in str(e):
            msg = "網路未連線（DNS 解析失敗），且離線翻譯不可用。\n\n" + msg
        show_dialog(f"Translation failed: {msg}")
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
