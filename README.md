# Aircraft tracker

A local Kotlin/Ktor service and a compact flight display for a PiAware receiver
and a Google Nest Hub. All integrations used here are free; no paid API or API
key is required.

## Public access with Cloudflare

For the Dynadot domain purchase, Cloudflare nameserver setup, and the Pi tunnel
behind `aircraft.garypope.dev`, see the
[Cloudflare setup guide](deploy/pi/README-cloudflare.md). It explains the intended
direct route to port 9090, verification, and the startup service that keeps the tunnel running
independently of SSH sessions.

## Run

Requires Java 17. Run `./gradlew run`, or run `ApplicationKt` in IntelliJ.
The server listens on port 9090. Stop it with Ctrl+C or IntelliJ’s Stop button.
On macOS, allow IntelliJ Local Network access if the Pi works in a browser but
Java reports “No route to host”.

- Responsive desktop and phone view: http://localhost:9090/
- Nest preview: http://localhost:9090/?view=nest (1024 × 600 logical pixels, no upscaling)
- Cast view: http://localhost:9090/?cast=1&motion=on (fills the tab, minimal margins)
- Demo: http://localhost:9090/?demo=1
- Demo states: add `&scene=dubai`, `departure`, `uncertain`, `quiet`, or `offline`.

Every view automatically features the aircraft closest to home within the 10 km
local sky. The radar circles that same aircraft, including aircraft with no known
route. A farther Dublin arrival or departure never steals focus, and browsing
history cannot pin an old aircraft over the live one. “Closest” means observed
horizontal distance from home; it is not a claim of a directly overhead flight.
The current card is explicitly labelled and matches the ringed aircraft on the radar. Other nearby aircraft remain on the radar; the separate supporting list is removed. The quiet card hides the aircraft identity instead of displaying a place name. The browser title adds - Live only while the real receiver is available.

Desktop and phone views include a collapsed “Recent passes near home” panel.
It contains summaries of observations within **2 km** of home, kept for at most
**7 days or 2,000 passages**. The default view shows the latest 20; there is no
full flight-path archive. Nest and cast views keep the single live display.

Demo data is visibly labelled and is never a fallback for a failed live feed.
Normal motion respects the system’s reduced-motion preference; `motion=on`
explicitly enables the radar sweep. Chrome tab mirroring requires the Mac to
remain running; this project does not launch casting automatically.

## Receiver and location

The default receiver is `192.168.1.200:8080`, using
`/skyaware/data/aircraft.json`. Override it with `PIAWARE_HOST` and `PIAWARE_PORT`,
or edit `src/main/resources/application.conf`.

`GET /api/data/aircraft` returns aircraft sorted by distance. Positions older
than 30 seconds are excluded. The display shows only the 10 km home zone and
always features the closest aircraft; Dublin route classifications only control
labels and colours.
Colours are lime for arrivals, cyan for departures, amber for likely Dublin
movements, and grey for other/unknown traffic.

The home position is currently set in `aircraft/AircraftService.kt` and in the
browser’s `radar.js`; keep both aligned when moving the receiver. Dublin Airport’s
position and the display radius are in `traffic.js`. The backend radius is in
`AircraftService.HOME_RADIUS_KM`.

## Flight log and storage

One server-side recorder samples every five seconds, whether or not a browser is open. **All observed aircraft within 10 km** go into one log, with a **rolling 30-day** retention window. Near-home (2 km) and military evidence remain stored metadata. A gap of ten minutes starts a new movement. Counts describe observed movements, rather than verified airport arrivals or departures. Closest-to-home live focus is unchanged.

Each summary saves first/last seen, closest approach time/distance, altitude, GS, IAS, vertical rate, track, squawk, emergency and category at closest approach, aircraft/callsign/airline, available route/source/flight number, and military status/reason. Missing fields remain null. Stronger military evidence persists within a passage; a pending lookup does not erase a known route for the same callsign. No coordinates, tracks or raw receiver snapshots are stored.

The table supports rolling last-hour, three-hour, seven-day, fourteen-day and thirty-day windows; today from Dublin midnight; last night from 23:00 yesterday to 07:00 today; date/time ranges (minute precision); and individual nights. Date controls have their own row above airline/origin/destination dropdowns, the movement-type dropdown (all, Dublin departure, Dublin arrival, unknown), and a flight-number/callsign text box. The table shows flight numbers separately from callsigns and aircraft type, with pagination. Tapping a movement opens a full-width details panel below its row; only one movement can be open at a time. A keyboard-accessible callsign button and Close details button offer the same interaction. Open details are preserved during automatic refresh when the movement remains on the page. The live route-source note is removed; provenance remains in the log table and details. All four categorical pickers offer Unknown; missing airline, origin and destination use the explicit `__unknown__` filter value. Ground speed is omitted from the live card, log table and visible statistics; stored observations and CSV retain it. The replacement Busiest hour card counts movements in each dated Dublin clock hour across the complete filtered selection, choosing the latest hour on ties. Dropdown choices cover the full retained log and match exactly; flight-number searches allow partial matches. Other field refinements and sorting controls are not exposed in the page. Statistics remain together with the log and follow the same selected dates and filters. Statistics and CSV cover **every matching record**, independently of the visible page. Unknown measurements are excluded from averages, with sample counts shown. CSV protects against spreadsheet formula injection and includes Dublin UTC offsets to disambiguate clock changes. Night is based on **closestAt**, from 23:00 inclusive to 07:00 exclusive in **Europe/Dublin**, labelled by its starting date. Clock-change nights therefore span seven or nine real hours as appropriate.

Incomplete saved metadata is retried in a separate background task, at most once per movement every six hours and one movement per second, sharing the live lookup's two-request concurrency limit and cache. Operator names are preserved across route-only fallbacks and can be filled using the free adsbdb ICAO airline endpoint. A current callsign database route is applied only to movements seen within the last 24 hours, must include Dublin and pass the saved climb/descent check, and is labelled as a refreshed database match. Known routes and recorded timings, distances and speeds are preserved. These sources do not provide dated flight plans or missing receiver measurements. Relative date windows are recalculated on the server for each table, statistics and CSV request.

Aircraft type comes from the free adsbdb aircraft registry with HexDB as fallback, matching the returned six-character hex identifier before accepting a type. Descriptive model, ICAO type code and source are saved with each movement and included in CSV. A separate background pass fills missing types across retained movements for the same hex, including those without a callsign. Lookups are nonblocking for the live display, have a 512-entry cache and two-request concurrency limit, cache successes for 24 hours, and retry missing saved types every six hours. Only the public aircraft identifier is sent; no coordinates are shared. A registered owner is not treated as the flight's airline.

The canonical file is `flight-log.json` beside `HISTORY_FILE` (default legacy location `data/overflights.json`), or the explicit `LOG_FILE` / `history.log-file`. On first start, the previous near-home and military files are imported and overlapping records for the same aircraft are merged. Originals are never modified or deleted. A failed import preserves the originals and reports a storage problem rather than creating a partial archive. A small metadata sidecar records when all-aircraft 10 km logging began and any safety-limit clipping. Older imported records cover the old 2 km passes / 10 km military policy; older speed fields cannot be reconstructed.

Storage safeguards are **50,000 movements / 32 MiB** plus an atomic temporary copy. These replace the former 2,000 / 2 MiB limits; clipping is explicitly flagged in the UI and persists across restart for the affected 30-day window. Current traffic volume is far below these ceilings. Records age out by closest approach. Writes remain batched every five minutes, plus graceful shutdown, so sudden power loss may lose the latest five minutes. Corrupt existing logs are preserved; disk failures keep new observations in memory and show a warning. `HISTORY_ENABLED=false` disables recording.

`GET /api/data/logbook` returns filtered rows, complete-window stats, source health, available observation range and full-coverage start time. Parameters: `period=all|hour|3hours|today|last-night|7days|14days`, `from`, `to` (Dublin local ISO datetime, end exclusive), `night` (ISO start date), `nightOnly`, `scope=all|home`, `military=all|military|possible|unknown`, `search`, `flight`, `hex`, `airline`, `origin`, `destination`, `squawk`, `category`, `emergency`, `basis`, `source`, `route=all|found|unknown|arrival|departure`, numeric `distance|altitude|gs|ias|vertical|track` plus `Min|Max`, `sort=time|distance|altitude|gs|ias|flight|airline`, `order=asc|desc`, `page`, `pageSize` (1–200, default 50). Custom nonexistent DST times are rejected; ambiguous range endpoints include both occurrences. `/api/data/logbook.csv` uses the same filters and exports the complete selection. Legacy `/history` and `/military-history` endpoints are filtered views of this one log, not separate recorders or files.

## Route and identity limitations

Routes first come from [adsbdb](https://www.adsbdb.com/). Missing routes and matches without Dublin from any airline also consult free [ADSB.lol-hosted Virtual Radar Server standing data](https://github.com/vradarserver/standing-data), preferring a two-airport Dublin match. If neither supplies one, free [HexDB](https://hexdb.io/) is tried. Same-airport loops, malformed codes and ambiguous multi-leg results are rejected. Both are callsign databases rather than
verified current flight plans. Lookups run in the background, with at most two
requests in flight. Successful Dublin matches are cached for one hour, missing or unrelated routes
for five minutes and failures for one minute, with at most 512 entries. The nearest
eight aircraft within 30 km and aircraft in the 10 km zone are considered, up to
32 aircraft per response. A failed route service does not block local positions.

Only routes with Dublin as an endpoint are displayed. Unrelated database matches are marked uncertain regardless of heading, altitude or vertical-rate availability; their airports and passenger flight numbers are withheld. Historical unrelated matches are hidden in responses without deleting sightings. Conflicting Dublin routes are flagged. A low aircraft climbing away from or
descending towards the airport may be labelled “Likely”; this is an estimate,
not proof of an arrival/departure, and never supplies a guessed airport.
Passenger flight numbers are shown only when the database supplies a numeric
IATA identifier; otherwise the radio callsign is labelled explicitly. Airline
logos are local assets; their source is in `display/logos/SOURCES.txt`.

## Images and external requests

- Route queries send callsigns to adsbdb and, when needed, ADSB.lol standing data and HexDB; neither receives receiver or aircraft coordinates. The aircraft API and live route note identify the source. Neither database guarantees a current flight plan; ambiguous multi-leg fallback routes are rejected.
- City-photo lookups send city names to Wikipedia/Wikimedia Commons. Photographer
  and licence credits accompany photos. A labelled city illustration is used
  when a suitable photo cannot be loaded.
- The satellite radar requests the home-centred map bounds from
  [EOX](https://maps.eox.at/). EOxCloudless imagery contains modified Copernicus
  Sentinel data (2025) and is used under CC BY-NC-SA 4.0 for this personal display.
  Attribution stays visible. Satellite-service failure leaves the radar usable.

## Structure

- `config/`: application-owned HTTP clients and lifecycle
- `aircraft/`: PiAware input and aircraft processing
- `routes/`: route-provider models, caching and consistency checks
- `http/`: public aircraft endpoint
- `model/`: aircraft response and geographic distance calculation
- `src/main/resources/display/`: static HTML/CSS and browser modules

The UI has no build step. `app.js` orchestrates rendering; `radar.js`, `layout.js`,
`airlines.js`, `city-photos.js` and `traffic.js` own individual responsibilities.

## Check before committing

```sh
./gradlew clean test build
node src/test/js/traffic.test.js
node src/test/js/layout.test.js
```

The tests use local fixture servers and do not need a Pi or external API.
Review `git diff HEAD` to include both previously staged work and later changes.

## Military tags

Receiver military flags and the bundled identifier subset of [tar1090-db](https://github.com/wiedehopf/tar1090-db/tree/csv) provide a database military tag. WOLF callsigns without a database match receive **possible military**, with the reason retained. Tags share the same log and fields as all other flights; they do not change live priority. Database flags and callsign hints can be incomplete or outdated and do not prove a current mission or role.

Ground speed comes from the receiver's dump1090 `gs` field (knots), with km/h converted at 1.852 per knot. Unknown speeds show a dash; zero is retained. This is ground speed, not airspeed.
EAI identity is corrected to Aer Lingus Regional (Emerald Airlines); the primary database incorrectly identifies this reassigned code as Elite Air. Current EAI allocation: https://www.faa.gov/air_traffic/publications/atpubs/cnt_html/chap3_section_3.html

Indicated airspeed uses receiver `ias`, separately from ground speed; absent IAS is never inferred from GS. Both show knots and km/h.

Fallbacks also run if the primary Dublin route conflicts with a low aircraft climbing/descending. Arrival/departure cache entries are separate so a previous movement cannot suppress a new lookup. These are consistency filters, not flight-plan verification.
