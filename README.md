# Aircraft tracker

A local Kotlin/Ktor service and a compact flight display for a PiAware receiver
and a Google Nest Hub. All integrations used here are free; no paid API or API
key is required.

## Run

Requires Java 17. Run `./gradlew run`, or run `ApplicationKt` in IntelliJ.
The server listens on port 9090. Stop it with Ctrl+C or IntelliJ’s Stop button.
On macOS, allow IntelliJ Local Network access if the Pi works in a browser but
Java reports “No route to host”.

- Live Nest preview: http://localhost:9090/ (1024 × 600 logical pixels, no upscaling)
- Cast view: http://localhost:9090/?cast=1&motion=on (fills the tab, minimal margins)
- Demo: http://localhost:9090/?demo=1
- Demo states: add `&scene=dubai`, `departure`, `uncertain`, `quiet`, or `offline`.

Demo data is visibly labelled and is never a fallback for a failed live feed.
Normal motion respects the system’s reduced-motion preference; `motion=on`
explicitly enables the radar sweep. Chrome tab mirroring requires the Mac to
remain running; this project does not launch casting automatically.

## Receiver and location

The default receiver is `192.168.1.131:8080`, using
`/skyaware/data/aircraft.json`. Override it with `PIAWARE_HOST` and `PIAWARE_PORT`,
or edit `src/main/resources/application.conf`.

`GET /api/data/aircraft` returns aircraft sorted by distance. Positions older
than 30 seconds are excluded. The display shows only the 10 km home zone and
prioritises Dublin route matches, then likely Dublin movements, then other traffic.
Colours are lime for arrivals, cyan for departures, amber for likely Dublin
movements, and grey for other/unknown traffic.

The home position is currently set in `aircraft/AircraftService.kt` and in the
browser’s `radar.js`; keep both aligned when moving the receiver. Dublin Airport’s
position and the display radius are in `traffic.js`. The backend radius is in
`AircraftService.HOME_RADIUS_KM`.

## Route and identity limitations

Routes come from [adsbdb](https://www.adsbdb.com/), a callsign database rather than
verified current flight plans. Lookups run in the background, with at most two
requests in flight. Successful matches are cached for six hours, missing routes
for 30 minutes and failures for one minute, with at most 512 entries. The nearest
eight aircraft within 30 km and aircraft in the 10 km zone are considered, up to
32 aircraft per response. A failed route service does not block local positions.

Conflicting Dublin routes are flagged. A low aircraft climbing away from or
descending towards the airport may be labelled “Likely”; this is an estimate,
not proof of an arrival/departure, and never supplies a guessed airport.
Passenger flight numbers are shown only when the database supplies a numeric
IATA identifier; otherwise the radio callsign is labelled explicitly. Airline
logos are local assets; their source is in `display/logos/SOURCES.txt`.

## Images and external requests

- Route queries send callsigns to adsbdb, not receiver coordinates.
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
```

The tests use local fixture servers and do not need a Pi or external API.
Review `git diff HEAD` to include both previously staged work and later changes.
