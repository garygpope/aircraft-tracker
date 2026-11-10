const el = (id) => document.getElementById(id);
const NS = "http://www.w3.org/2000/svg";
const markers = new Map();
export function initializeRadar(params) {
  // WMS bounds use the same local north-up projection as the aircraft overlay.
  const homeLat = 53.417032269941224,
    homeLon = -6.14787466897715;
  const latSpan = SkyTraffic.HOME_RANGE_KM / 111.32,
    lonSpan = latSpan / Math.cos((homeLat * Math.PI) / 180);
  const satelliteQuery = new URLSearchParams({
    service: "WMS",
    version: "1.1.1",
    request: "GetMap",
    layers: "s2cloudless-2025",
    styles: "",
    format: "image/jpeg",
    srs: "EPSG:4326",
    bbox: [
      homeLon - lonSpan,
      homeLat - latSpan,
      homeLon + lonSpan,
      homeLat + latSpan,
    ].join(","),
    width: "680",
    height: "680",
  });
  const satellite = el("satellite");
  satellite.addEventListener("load", () => {
    satellite.setAttribute("opacity", ".82");
    el("map-credit").hidden = false;
  });
  satellite.addEventListener("error", () => {
    satellite.setAttribute("opacity", "0");
    el("map-credit").hidden = true;
  });
  satellite.setAttribute(
    "href",
    "https://tiles.maps.eox.at/wms?" + satelliteQuery
  );
  // Updating SVG geometry creates actual repaints, rather than only a compositor animation.
  const motionPreference = matchMedia("(prefers-reduced-motion: reduce)");
  let sweepAngle = 0,
    lastSweep = performance.now();
  setInterval(() => {
    const now = performance.now(),
      elapsed = Math.min(now - lastSweep, 500);
    lastSweep = now;
    if (
      document.body.classList.contains("offline") ||
      (motionPreference.matches && params.get("motion") !== "on")
    )
      return;
    sweepAngle = (sweepAngle + (elapsed * 360) / 16000) % 360;
    el("radar-sweep").setAttribute(
      "transform",
      `rotate(${sweepAngle.toFixed(2)} 200 200)`
    );
  }, 80);
}
export function radar(planes, selected) {
  const range = SkyTraffic.HOME_RANGE_KM;
  el("range").textContent = range + " KM RANGE";
  el("zone").setAttribute("r", (170 * SkyTraffic.HOME_RANGE_KM) / range);
  el("count").textContent = planes.length;
  const active = new Set();
  for (const p of planes) {
    if (!Number.isFinite(p.lat) || !Number.isFinite(p.lon)) continue;
    const x =
        (p.lon + 6.14787466897715) *
        111.32 *
        Math.cos((53.417032269941224 * Math.PI) / 180),
      y = (p.lat - 53.417032269941224) * 111.32;
    if (Math.hypot(x, y) > range) continue;
    active.add(p.hex);
    let marker = markers.get(p.hex);
    if (!marker) {
      marker = document.createElementNS(NS, "g");
      marker.classList.add("plane");
      const symbol = document.createElementNS(NS, "g"),
        path = document.createElementNS(NS, "path");
      path.setAttribute(
        "d",
        "M0 -10 2 -2 9 3 9 5 2 2 2 8 5 10 5 11 0 9 -5 11 -5 10 -2 8 -2 2 -9 5 -9 3 -2 -2Z"
      );
      symbol.append(path);
      const text = document.createElementNS(NS, "text");
      text.setAttribute("x", "13");
      text.setAttribute("y", "4");
      marker.append(symbol, text);
      el("planes").append(marker);
      markers.set(p.hex, marker);
    }
    marker.style.transform = `translate(${200 + (x / range) * 170}px,${
      200 - (y / range) * 170
    }px)`;
    marker.firstChild.setAttribute("transform", `rotate(${p.track ?? 0})`);
    marker.lastChild.textContent = p.flight || p.hex;
    marker.setAttribute(
      "class",
      "plane " +
        SkyTraffic.classify(p).kind +
        (p.hex === selected?.hex ? " selected" : "")
    );
  }
  for (const [key, marker] of markers) {
    if (!active.has(key)) {
      marker.remove();
      markers.delete(key);
    }
  }
}
