# PiAware web address

The display uses relative asset and API URLs so it can run at `/` directly or
behind the PiAware lighttpd server at `/overtheroof/`. The proxy strips that
prefix before forwarding requests to the tracker on `127.0.0.1:9090`.

`90-overtheroof.conf` belongs in `/etc/lighttpd/conf-enabled/`, with mod_proxy
and mod_redirect enabled. `/overtheroof` redirects to `/overtheroof/` so relative
URLs resolve correctly. PiAware's existing `/` and `/skyaware/` are preserved.

To create the update bundle from the repository root:

```sh
./gradlew test shadowJar
mkdir -p build/pi-web
cp deploy/pi/install-web.sh deploy/pi/90-overtheroof.conf build/pi-web/
cp build/libs/aircraft-tracker-all.jar build/pi-web/aircraft-tracker.jar
(cd build/pi-web && shasum -a 256 aircraft-tracker.jar install-web.sh 90-overtheroof.conf > SHA256SUMS)
COPYFILE_DISABLE=1 tar -czf build/aircraft-tracker-web.tar.gz -C build pi-web
```

Transfer and extract that bundle on the Pi, then run `sudo bash install-web.sh`
from its directory. It requires the tracker to already be installed. It restores
the original status page from the earlier `piaware-index-*.html` backup only if
the added `over-the-roof-link` is present. It saves the current page, JAR and
any existing proxy configuration in `/var/backups/aircraft-tracker-web-*`.

The installer checks the configuration before reloading lighttpd, checks all
display resources and the API through the new path, and restores the previous
files if a step fails. Neither PiAware's feeder nor its radio services are
restarted. The Java runtime and tracker service file are unchanged.

Open `http://piaware.local/overtheroof/`, or use the Pi's IP address on devices
without `.local` name resolution. The direct port 9090 address still works.

## PiAware status button

`overtheroof-status.js` adds a fifth indicator next to MLAT using PiAware's own
Bootstrap styles. It checks `/overtheroof/api/data/aircraft` immediately and then
10 seconds after each completed check, with a 5-second timeout. A successful
JSON array response (including an empty sky) is green; a failed, invalid or
unavailable response is red. The initial check is amber. Clicking the button
opens `/overtheroof/` even if the last check failed.

The script is served by PiAware, so the indicator still appears if the tracker
stops. A MutationObserver keeps it present when PiAware rebuilds its status row.
No changes to PiAware's original JavaScript or CSS are needed.

Bundle `install-status.py`, `overtheroof-status.js` and a `SHA256SUMS` manifest
containing both files. On the Pi, run `sudo python3 install-status.py` from that
directory. It backs up the current page and any previous custom script under
`/var/backups/piaware-status-button-*`, installs the script and adds a single
cache-versioned script tag to `/var/www/html/index.html`. Refresh the status
page; no service restart is needed. A PiAware package upgrade may overwrite the
HTML page; rerun this installer to restore the indicator.
