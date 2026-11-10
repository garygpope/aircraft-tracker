#!/usr/bin/env python3
"""Add the Over the Roof indicator to the existing PiAware status page."""
from pathlib import Path
from html.parser import HTMLParser
import hashlib
import os
import re
import shutil
import tempfile

if os.geteuid() != 0:
    raise SystemExit('Run with sudo python3 install-status.py')

source = Path(__file__).resolve().parent
for line in (source / 'SHA256SUMS').read_text().splitlines():
    expected, name = line.split('  ', 1)
    if Path(name).name != name:
        raise SystemExit('Invalid manifest filename')
    if hashlib.sha256((source / name).read_bytes()).hexdigest() != expected:
        raise SystemExit('Checksum failed: ' + name)

page = Path('/var/www/html/index.html')
script = page.parent / 'overtheroof-status.js'
html = page.read_text()

class Indicators(HTMLParser):
    found = False
    def handle_starttag(self, tag, attrs):
        if dict(attrs).get('id') == 'indicators':
            self.found = True

parser = Indicators()
parser.feed(html)
if not parser.found or not re.search(r'</body\s*>', html, re.I):
    raise SystemExit('Unexpected PiAware page; nothing changed.')

payload = (source / 'overtheroof-status.js').read_bytes()
version = hashlib.sha256(payload).hexdigest()[:12]
tag = '<script id="overtheroof-status-script" src="./overtheroof-status.js?v=' + version + '"></script>'
html = re.sub(r'<script\b[^>]*\bid=[\'\"]overtheroof-status-script[\'\"][^>]*>\s*</script>', '', html, flags=re.I)
html = re.sub(r'</body\s*>', lambda match: tag + '\n' + match.group(), html, count=1, flags=re.I)

backup = Path(tempfile.mkdtemp(prefix='piaware-status-button-', dir='/var/backups'))
shutil.copy2(page, backup / 'index.html')
had_script = script.exists()
if had_script:
    shutil.copy2(script, backup / script.name)
try:
    script.write_bytes(payload)
    script.chmod(0o644)
    page.write_text(html)
except BaseException:
    shutil.copy2(backup / 'index.html', page)
    if had_script:
        shutil.copy2(backup / script.name, script)
    elif script.exists():
        script.unlink()
    raise
print('SUCCESS: Over the Roof health button added to http://piaware.local/')
print('Green = tracker and aircraft feed responding; red = unavailable.')
print('Backup:', backup)
print('Refresh the PiAware page. No service restart is needed.')
