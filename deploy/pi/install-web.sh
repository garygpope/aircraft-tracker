#!/bin/bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
[[ $EUID -eq 0 ]] || { echo 'Run with sudo bash install-web.sh'; exit 1; }
sha256sum -c SHA256SUMS
page=/var/www/html/index.html
jar=/opt/aircraft-tracker/aircraft-tracker.jar
conf=/etc/lighttpd/conf-enabled/90-overtheroof.conf
[[ -f $page && -f $jar ]]
command -v python3 >/dev/null
command -v lighty-enable-mod >/dev/null
lighttpd -tt -f /etc/lighttpd/lighttpd.conf
curl -fsS --max-time 10 http://127.0.0.1:9090/ >/dev/null
backup=$(mktemp -d /var/backups/aircraft-tracker-web-XXXXXXXX)
cp -p "$page" "$backup/index.html"
cp -p "$jar" "$backup/aircraft-tracker.jar"
[[ ! -e $conf ]] || cp -p "$conf" "$backup/proxy.conf"
proxy_was_enabled=false
[[ ! -e /etc/lighttpd/conf-enabled/10-proxy.conf ]] || proxy_was_enabled=true
rollback() {
  trap - ERR
  set +e
  cp -p "$backup/index.html" "$page"
  cp -p "$backup/aircraft-tracker.jar" "$jar"
  if [[ -e $backup/proxy.conf ]]; then
    cp -p "$backup/proxy.conf" "$conf"
  else
    rm -f "$conf"
  fi
  if [[ $proxy_was_enabled == false ]]; then
    rm -f /etc/lighttpd/conf-enabled/10-proxy.conf
  fi
  systemctl restart aircraft-tracker
  lighttpd -tt -f /etc/lighttpd/lighttpd.conf && systemctl reload lighttpd
  echo "Update failed; previous files restored. Backup: $backup"
  exit 1
}
trap rollback ERR

# Restore only if the earlier link was actually added.
python3 - <<'PY'
from pathlib import Path
import shutil
page = Path('/var/www/html/index.html')
marker = 'id="over-the-roof-link"'
if marker in page.read_text():
    candidates = sorted(Path('/var/backups').glob('piaware-index-*.html'), reverse=True)
    original = next((p for p in candidates if marker not in p.read_text() and 'piaware' in p.read_text().lower()), None)
    if original is None:
        raise SystemExit('Original PiAware backup not found; stopping without changing the page.')
    shutil.copy2(original, page)
    print('Restored original PiAware page from', original)
else:
    print('PiAware page has no added link; leaving it as it is.')
PY
install -m 644 aircraft-tracker.jar "$jar"
systemctl restart aircraft-tracker
healthy=false
for attempt in {1..30}; do
  if curl -fsS --max-time 2 http://127.0.0.1:9090/api/data/aircraft >/dev/null; then
    healthy=true; break
  fi
  sleep 1
done
[[ $healthy == true ]]
lighty-enable-mod proxy
install -m 644 90-overtheroof.conf "$conf"
# mod_redirect is part of the normal PiAware configuration. Load it if absent.
if ! lighttpd -p -f /etc/lighttpd/lighttpd.conf | grep -q '"mod_redirect"'; then
  sed -i '1i server.modules += ( "mod_redirect" )' "$conf"
fi
lighttpd -tt -f /etc/lighttpd/lighttpd.conf
systemctl reload lighttpd
for asset in '' styles.css app.js traffic.js radar.js layout.js airlines.js city-photos.js city-fallback.svg logos/EIN.png api/data/aircraft; do
  curl -fsS --max-time 15 "http://127.0.0.1/overtheroof/$asset" >/dev/null
done
curl -fsS --max-time 10 http://127.0.0.1/ >/dev/null
trap - ERR
echo "SUCCESS: http://piaware.local/overtheroof/"
echo "Original PiAware page: http://piaware.local/"
echo "Backup of previous files: $backup"
