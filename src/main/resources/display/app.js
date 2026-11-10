import { airlineHeader } from "./airlines.js";
import { clearPhoto, setPhoto } from "./city-photos.js";
import { initializeLayout } from "./layout.js";
import { initializeRadar, radar } from "./radar.js";

("use strict");
const el = (id) => document.getElementById(id),
  params = new URLSearchParams(location.search),
  demo = params.has("demo"),
  scenario = params.get("scene");
let selectedKey = "",
  lastSuccess = null;

initializeLayout(params);
initializeRadar(params);

const examples = [
  {
    flight: "DEMO123",
    airlineName: "Aer Lingus",
    airlineIcao: "EIN",
    reportedFlightNumber: "EI123",
    hex: "demo01",
    distanceKm: 1.2,
    isWithinRange: true,
    altitudeFeet: 2100,
    verticalRate: -640,
    origin: "Helsinki (HEL)",
    destination: "Dublin (DUB)",
    originCode: "EFHK",
    destinationCode: "EIDW",
    routeStatus: "found",
    lat: 53.426,
    lon: -6.137,
    track: 250,
  },
  {
    flight: "DEMO 456",
    hex: "demo02",
    distanceKm: 6.7,
    isWithinRange: true,
    altitudeFeet: 3400,
    verticalRate: 800,
    origin: "Dublin (DUB)",
    destination: "Paris (CDG)",
    originCode: "EIDW",
    destinationCode: "LFPG",
    routeStatus: "found",
    lat: 53.46,
    lon: -6.22,
    track: 310,
  },
  {
    flight: "DEMO 789",
    hex: "demo03",
    distanceKm: 8.4,
    isWithinRange: true,
    altitudeFeet: 35000,
    verticalRate: -400,
    routeStatus: "not-found",
    lat: 53.36,
    lon: -6.22,
    track: 90,
  },
];
function clock() {
  el("clock").textContent = new Date().toLocaleTimeString("en-IE", {
    timeZone: "Europe/Dublin",
    hour: "2-digit",
    minute: "2-digit",
  });
}
clock();
setInterval(clock, 1000);
const cityName = (label) =>
  (label || "").replace(/\s*\([^)]*\)\s*$/, "").trim();
function route(p) {
  el("direction").classList.remove("warning");
  if (p.routeStatus === "found") {
    const inbound = p.destinationCode === "EIDW",
      outbound = p.originCode === "EIDW",
      label = inbound ? p.origin : outbound ? p.destination : null;
    el("direction").textContent = inbound
      ? "Coming from"
      : outbound
      ? "Heading to"
      : "Reported route";
    el("city").textContent = label
      ? cityName(label)
      : `${cityName(p.origin)} → ${cityName(p.destination)}`;
    el("airport").textContent = `${p.origin || "Unknown"} → ${
      p.destination || "Unknown"
    }`;
    el("note").textContent = demo
      ? "Demonstration · fictional aircraft, sample route"
      : "Database route · callsign match, not a verified live flight plan";
    if (label) setPhoto(cityName(label));
    else clearPhoto();
  } else {
    clearPhoto();
    const uncertain = p.routeStatus === "uncertain";
    el("direction").textContent = uncertain
      ? "Route needs verification"
      : "Every flight has a story";
    if (uncertain) el("direction").classList.add("warning");
    el("city").textContent = p.flight || p.hex;
    el("airport").textContent =
      {
        pending: "Finding its origin and destination…",
        "not-found": "No route in the callsign database",
        uncertain:
          "The route is unverified; movement may suggest Dublin traffic",
        unavailable: "Route service temporarily unavailable",
        "no-callsign": "No callsign for a route lookup",
      }[p.routeStatus] || "Route information unavailable";
    el("note").textContent = uncertain
      ? "Movement is an estimate · origin and destination remain unverified."
      : "Aircraft positions and altitude are live from your receiver";
  }
}
function traffic(planes, selected) {
  el("traffic").replaceChildren();
  const others = planes
    .filter((p) => p.hex !== selected?.hex)
    .sort(SkyTraffic.order)
    .slice(0, 3);
  for (const p of others) {
    const item = document.createElement("div");
    item.className = "traffic-item " + SkyTraffic.classify(p).kind;
    const title = document.createElement("strong"),
      distance = document.createElement("span"),
      sub = document.createElement("small");
    title.textContent = p.flight || p.hex;
    distance.textContent = p.distanceKm.toFixed(1) + " km";
    sub.textContent =
      SkyTraffic.classify(p).label +
      " · " +
      (p.routeStatus === "found" && SkyTraffic.classify(p).kind !== "likely"
        ? `${cityName(p.origin)} → ${cityName(p.destination)}`
        : p.routeStatus === "uncertain"
        ? "Route needs verification"
        : p.altitudeFeet == null
        ? "Altitude unavailable"
        : p.altitudeFeet.toLocaleString() + " ft");
    item.append(title, distance, sub);
    el("traffic").append(item);
  }
  if (!others.length) {
    const item = document.createElement("div");
    item.className = "traffic-item";
    item.textContent = "Watching the 10 km home zone";
    el("traffic").append(item);
  }
}
function show(planes) {
  const receivedCount = planes.length;
  planes = planes.filter(SkyTraffic.inRange).sort(SkyTraffic.order);
  const p = SkyTraffic.select(planes);
  lastSuccess = new Date();
  document.body.classList.remove("offline");
  el("status").textContent = demo ? "Demo · sample flights" : "Receiver live";
  el("mode").textContent = demo
    ? "DEMONSTRATION / SAMPLE DATA"
    : "PIAWARE / DUBLIN";
  el("updated").textContent =
    "Updated " +
    lastSuccess.toLocaleTimeString("en-IE", {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
  el("card").classList.toggle("empty", !p);
  if (p) {
    const key = p.hex + "|" + p.routeStatus;
    if (key !== selectedKey) {
      selectedKey = key;
      el("card").classList.remove("changed");
      void el("card").offsetWidth;
      el("card").classList.add("changed");
    }
    airlineHeader(p);
    const category = SkyTraffic.classify(p);
    el("card").dataset.kind = category.kind;
    el("badge").textContent = category.label;
    el("badge").title = category.basis;
    el("distance").textContent = p.distanceKm.toFixed(1);
    el("altitude").textContent =
      p.altitudeFeet == null ? "—" : p.altitudeFeet.toLocaleString();
    el("movement").textContent =
      p.verticalRate == null
        ? "Unknown"
        : p.verticalRate > 200
        ? "↗ Climbing"
        : p.verticalRate < -200
        ? "↘ Descending"
        : "→ Level";
    route(category.kind === "likely" ? { ...p, routeStatus: "uncertain" } : p);
  } else {
    airlineHeader(null);
    el("card").dataset.kind = "other";
    selectedKey = "";
    clearPhoto();
    el("flight").textContent = "LOCAL SKY";
    el("badge").textContent = "ALL QUIET";
    el("direction").textContent = "A little room to daydream";
    el("direction").classList.remove("warning");
    el("city").textContent = "The sky is yours.";
    el("airport").textContent =
      "No aircraft within 10 km of home. Watching for the next one.";
    el("distance").textContent = "—";
    el("altitude").textContent = "—";
    el("movement").textContent = "—";
    el("note").textContent =
      "The radar will light up when the next aircraft appears.";
  }
  radar(planes, p);
  traffic(planes, p);
  if (!demo) el("mode").textContent = `10 KM ZONE / ${receivedCount} RECEIVED`;
}
function offline() {
  airlineHeader(null);
  document.body.classList.add("offline");
  clearPhoto();
  selectedKey = "";
  el("status").textContent = "Receiver offline";
  el("flight").textContent = "SIGNAL LOST";
  el("badge").textContent = "RECONNECTING";
  el("direction").textContent = "Back in a moment";
  el("direction").classList.remove("warning");
  el("city").textContent = "Watching for a signal.";
  el("airport").textContent =
    "The receiver is unavailable. Retrying automatically.";
  ["distance", "altitude", "movement"].forEach(
    (id) => (el(id).textContent = "—")
  );
  el("note").textContent = "No stale aircraft are being shown.";
  el("updated").textContent = lastSuccess
    ? "Last received " + lastSuccess.toLocaleTimeString()
    : "No connection to receiver";
  radar([], null);
  traffic([], null);
}
async function refresh() {
  try {
    if (demo) {
      if (scenario === "offline") {
        offline();
        el("status").textContent = "Demo · offline preview";
        el("mode").textContent = "DEMONSTRATION / SAMPLE DATA";
        return;
      }
      const sample = examples.map((p) => ({ ...p }));
      if (scenario === "dubai") {
        sample[0].origin = "Dubai (DXB)";
        sample[0].originCode = "OMDB";
        sample[0].airlineName = "Emirates";
        sample[0].airlineIcao = "UAE";
        sample[0].reportedFlightNumber = "EK161";
      }
      if (scenario === "uncertain") sample[0].routeStatus = "uncertain";
      if (scenario === "departure") {
        sample[0].origin = "Dublin (DUB)";
        sample[0].destination = "Paris (CDG)";
        sample[0].originCode = "EIDW";
        sample[0].destinationCode = "LFPG";
        sample[0].verticalRate = 800;
      }
      show(scenario === "quiet" ? [] : sample);
      return;
    }
    const controller = new AbortController(),
      timer = setTimeout(() => controller.abort(), 10000);
    let response;
    try {
      response = await fetch("./api/data/aircraft", {
        cache: "no-store",
        signal: controller.signal,
      });
    } finally {
      clearTimeout(timer);
    }
    if (!response.ok) throw Error("Feed unavailable");
    const planes = await response.json();
    if (!Array.isArray(planes)) throw Error("Invalid feed");
    show(planes);
  } catch {
    offline();
  } finally {
    if (!demo) setTimeout(refresh, 5000);
  }
}
refresh();
