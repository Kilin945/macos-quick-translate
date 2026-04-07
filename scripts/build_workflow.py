#!/usr/bin/env python3
"""
Build script: sync translate.py into Google翻譯.workflow and deploy to ~/Library/Services/.
Run directly or triggered via .git/hooks/pre-commit.
"""
import re
import shutil
from pathlib import Path
from xml.sax.saxutils import escape

REPO_ROOT = Path(__file__).parent.parent
TRANSLATE_PY = REPO_ROOT / "translate.py"
WFLOW = REPO_ROOT / "Google翻譯.workflow/Contents/document.wflow"
SERVICE = Path.home() / "Library/Services/Google翻譯.workflow/Contents/document.wflow"

def build():
    python_code = TRANSLATE_PY.read_text(encoding='utf-8')
    escaped_code = escape(python_code)

    wflow_content = WFLOW.read_text(encoding='utf-8')

    pattern = r"(python3 &lt;&lt; 'PYEOF'\n).*?(\nPYEOF\n)"
    new_content, count = re.subn(pattern, lambda m: m.group(1) + escaped_code + m.group(2), wflow_content, flags=re.DOTALL)

    if count == 0:
        print("ERROR: Could not find PYEOF markers in .wflow")
        raise SystemExit(1)

    WFLOW.write_text(new_content, encoding='utf-8')
    print(f"✓ {WFLOW.relative_to(REPO_ROOT)} updated")

    shutil.copy2(WFLOW, SERVICE)
    print(f"✓ Deployed to ~/Library/Services/")

if __name__ == '__main__':
    build()
