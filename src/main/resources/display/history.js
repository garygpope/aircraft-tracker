const el = (id) => document.getElementById(id);
const number = (value, digits = 0) => Number.isFinite(value) ? value.toLocaleString("en-IE", { maximumFractionDigits: digits }) : "—";
export const dateTime = (time) => {
  const parts = Object.fromEntries(new Intl.DateTimeFormat("en-GB", {
    timeZone: "Europe/Dublin", day: "2-digit", month: "short", year: "numeric",
    hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23",
  }).formatToParts(new Date(time)).map(({ type, value }) => [type, value]));
  return `${parts.day} ${parts.month} ${parts.year} ${parts.hour}:${parts.minute}:${parts.second}`;
};
function node(tag, text, className) {
  const element = document.createElement(tag);
  if (text != null) element.textContent = text;
  if (className) element.className = className;
  return element;
}
export function initializeHistory(params) {
  if (document.body.classList.contains("nest")) return;
  const form = el("log-filters"), demo = params.has("demo");
  let dirty = false, applied = new URLSearchParams(), page = 1, responsePage = 1, pages = 1, request = 0, controller, timer;
  const fields = () => {
    const result = new URLSearchParams();
    for (const [key, value] of new FormData(form)) if (String(value).trim() && key !== "period") result.set(key, String(value).trim());
    const period = form.elements.period.value;
    if (period !== "custom") { result.delete("from"); result.delete("to"); }
    if (period !== "night") result.delete("night");
    if (period === "night") result.set("nightOnly", "true");
    else result.delete("nightOnly");
    if (!["all", "custom", "night"].includes(period)) result.set("period", period);
    result.set("exact", "true");
    return result;
  };
  function periodFields() {
    for (const label of form.querySelectorAll("[data-period]")) {
      const shown = label.dataset.period === form.elements.period.value;
      label.hidden = !shown;
      for (const input of label.querySelectorAll("input")) input.disabled = !shown;
    }
    if (form.elements.period.value === "night" && !form.elements.night.value) {
      // Pick the previous local date without assuming the client is in Dublin.
      const parts = Object.fromEntries(new Intl.DateTimeFormat("en-GB", {timeZone:"Europe/Dublin",year:"numeric",month:"2-digit",day:"2-digit"}).formatToParts(new Date()).map(({type,value})=>[type,value]));
      const local = `${parts.year}-${parts.month}-${parts.day}`;
      const day = new Date(local + "T12:00:00Z"); day.setUTCDate(day.getUTCDate() - 1);
      form.elements.night.value = day.toISOString().slice(0, 10);
    }
    form.elements.night.required = form.elements.period.value === "night";
  }
  function stat(label, value, detail) {
    const card = node("div", null, "log-stat");
    card.append(node("span", label, "eyebrow"), node("strong", value), node("span", detail, "log-stat-detail"));
    return card;
  }
  function renderStats(data) {
    const s = data.stats, container = el("log-stats"); container.replaceChildren();
    const percent = (n) => s.passages ? Math.round(n / s.passages * 100) : 0;
    const peak = s.busiestHour;
    const hour = (time) => new Intl.DateTimeFormat("en-GB", {timeZone:"Europe/Dublin", hour:"2-digit", minute:"2-digit", hourCycle:"h23"}).format(new Date(time));
    const peakDay = peak ? new Intl.DateTimeFormat("en-GB", {timeZone:"Europe/Dublin", day:"2-digit", month:"short", year:"numeric"}).format(new Date(peak.startsAt)) : "";
    container.append(stat("Movements", number(s.passages), "Recorded in this window"),
      stat("Night movements", number(s.nightPassages), `${percent(s.nightPassages)}% of this selection`),
      stat("Dublin route matches", number(s.routeMatches), `${number(s.arrivals)} arrivals · ${number(s.departures)} departures`),
      stat("Distinct aircraft", number(s.uniqueAircraft), "By aircraft identifier"),
      stat("Closest movement", number(s.distanceKm.minimum, 2) + " km", `Average closest distance ${number(s.distanceKm.average, 2)} km`),
      stat("Average altitude", number(s.altitudeFeet.average) + " ft", `${number(s.altitudeFeet.count)} reported measurements`),
      stat("Busiest hour", peak ? `${hour(peak.startsAt)}–${hour(peak.startsAt + 3600000)}` : "—", peak ? `${number(peak.movements)} movements · ${peakDay} · Dublin time` : "No movements in this selection"),
      stat("Average IAS", number(s.indicatedAirspeedKnots.average) + " kt", `${number(s.indicatedAirspeedKnots.average == null ? null : s.indicatedAirspeedKnots.average * 1.852)} km/h · ${number(s.indicatedAirspeedKnots.count)} reported`));
    const insights = el("log-insights"); insights.replaceChildren();
    for (const [title, values] of [["Airlines in this window", s.airlines], ["Most recorded routes", s.routes]]) {
      const box = node("div"), list = node("ol"); box.append(node("h3", title));
      for (const item of values) { const li = node("li"); li.append(node("span", item.label), node("strong", number(item.count))); list.append(li); }
      if (!values.length) box.append(node("p", "No recorded matches.")); else box.append(list);
      insights.append(box);
    }
  }
  let expandedMovement = null, openRow = null;
  function closeMovement() {
    if (!openRow) return;
    openRow.details.hidden = true;
    openRow.tr.classList.remove("is-expanded");
    openRow.button.setAttribute("aria-expanded", "false");
    openRow.button.setAttribute("aria-label", `Show details for ${openRow.name}`);
    openRow = null; expandedMovement = null;
  }
  function toggleMovement(view) {
    if (openRow?.key === view.key) { closeMovement(); return; }
    closeMovement();
    openRow = view; expandedMovement = view.key;
    view.details.hidden = false;
    view.tr.classList.add("is-expanded");
    view.button.setAttribute("aria-expanded", "true");
    view.button.setAttribute("aria-label", `Hide details for ${view.name}`);
  }
  function renderRows(data) {
    const body = el("log-rows"), previousExpanded = expandedMovement;
    body.replaceChildren(); openRow = null; expandedMovement = null;
    for (const row of data.entries) {
      const p = row.passage, key = `${p.hex}-${p.firstSeenAt}`;
      const tr = node("tr", null, `movement-row${row.nightDate ? " night-flight" : ""}`);
      const time = node("td"), stamp = node("time", dateTime(p.closestAt)); stamp.dateTime = new Date(p.closestAt).toISOString(); time.append(stamp);
      if (row.nightDate) stamp.title = `Night movement · night starting ${row.nightDate} · Dublin time`;
      const identity = node("td"), button = node("button", null, "movement-toggle"), name = p.flight || p.hex;
      button.type = "button";
      button.append(node("strong", name));
      const chevron = node("span", "⌄", "movement-chevron"); chevron.setAttribute("aria-hidden", "true"); button.append(chevron);
      const panelId = `movement-details-${key.replace(/[^a-zA-Z0-9-]/g, "_")}`;
      button.setAttribute("aria-expanded", "false"); button.setAttribute("aria-controls", panelId);
      button.setAttribute("aria-label", `Show details for ${name}`);
      identity.append(button, node("small", p.hex));
      const type = node("td", p.aircraftType || "Unknown");
      if (p.aircraftTypeCode) type.append(node("small", p.aircraftTypeCode));
      const route = node("td", p.routeStatus === "found" ? `${p.origin || "Unknown"} → ${p.destination || "Unknown"}` : "Unknown");
      if (p.routeSource) route.append(node("small", p.routeSource));
      const speed = node("td", number(p.indicatedAirspeedKnots));
      speed.append(node("small", Number.isFinite(p.indicatedAirspeedKnots) ? `${number(p.indicatedAirspeedKnots * 1.852)} km/h` : "Not reported"));
      tr.append(time, identity, node("td", p.reportedFlightNumber || "—"), node("td", p.airlineName || "Unknown"), type, route,
        node("td", number(p.closestDistanceKm, 2)), node("td", number(p.altitudeFeet)), speed);
      const details = node("tr", null, "movement-details"), cell = node("td"), panel = node("div", null, "movement-detail-panel");
      details.hidden = true; cell.colSpan = 9; panel.id = panelId;
      const heading = node("div", null, "movement-detail-heading"), close = node("button", "Close details", "log-button"); close.type = "button";
      heading.append(node("strong", `Movement details · ${name}`), close); panel.append(heading);
      const groups = node("div", null, "movement-detail-groups");
      const sections = [
        ["Observed movement", [["Closest approach", dateTime(p.closestAt)], ["First seen", dateTime(p.firstSeenAt)], ["Last seen", dateTime(p.lastSeenAt)],
          ["Observed duration", `${Math.round((p.lastSeenAt - p.firstSeenAt) / 1000)} s`], ["Area", row.nearHome ? "Near home · within 2 km" : "Neighbourhood · within 10 km"],
          ["Night movement", row.nightDate ? `Night starting ${row.nightDate}` : "No"]]],
        ["Aircraft & route", [["Aircraft type", p.aircraftType || "Unknown"], ["Type code", p.aircraftTypeCode || "Unknown"], ["Aircraft type source", p.aircraftTypeSource || "Unknown"],
          ["Origin", p.origin || "Unknown"], ["Destination", p.destination || "Unknown"], ["Route status", p.routeStatus], ["Route source", p.routeSource || "Unknown"]]],
        ["Other readings", [["Vertical rate", p.verticalRate == null ? "Not reported" : `${number(p.verticalRate)} ft/min`], ["Track", p.track == null ? "Not reported" : `${number(p.track)}°`],
          ["Squawk", p.squawk || "Not reported"], ["Emergency", p.emergency || "Not reported"], ["Category", p.category || "Not reported"],
          ["Military metadata", p.militaryStatus === "unknown" ? "Unclassified" : p.militaryStatus], ["Identification basis", p.militaryBasis || "Not recorded"]]]
      ];
      for (const [title, facts] of sections) {
        const section = node("section"), dl = node("dl"); section.append(node("h3", title));
        for (const [label, value] of facts) { const pair = node("div"); pair.append(node("dt", label), node("dd", value)); dl.append(pair); }
        section.append(dl); groups.append(section);
      }
      panel.append(groups); cell.append(panel); details.append(cell);
      const view = {key, tr, details, button, name};
      tr.addEventListener("click", (event) => {
        if (event.target.closest("a, input, select, textarea") || window.getSelection()?.toString()) return;
        toggleMovement(view);
      });
      close.addEventListener("click", () => { closeMovement(); button.focus(); });
      body.append(tr, details);
      if (previousExpanded === key) toggleMovement(view);
    }
    if (!data.entries.length) { const tr = node("tr"), td = node("td", "No movements match these filters."); td.colSpan = 9; tr.append(td); body.append(tr); }
    responsePage = data.page; page = data.page; pages = Math.max(1, Math.ceil(data.total / data.pageSize));
    el("log-page").textContent = `${number(data.total)} matching movements · page ${page} of ${pages}`;
    el("log-prev").disabled = page <= 1; el("log-next").disabled = page >= pages;
    el("log-caption").textContent = `${number(data.total)} matching movements · closest approach in Dublin time`;
  }
  let editTimer;
  function applyFilters(force = false) {
    clearTimeout(editTimer);
    if (!form.checkValidity()) {
      el("log-status").textContent = "Complete the date and time to update the movements.";
      return;
    }
    const next = fields();
    if (!force && next.toString() === applied.toString() && page === 1) {
      dirty = false; clearTimeout(timer); timer = setTimeout(refresh, 60000); return;
    }
    applied = next; dirty = false; page = 1; refresh();
  }
  async function refresh() {
    const current = ++request; controller?.abort(); controller = new AbortController(); const ownController = controller; clearTimeout(timer);
    el("log-status").textContent = "Updating movements…";
    el("log-results").setAttribute("aria-busy", "true");
    el("log-results").classList.add("updating");
    const timeout = setTimeout(() => ownController.abort(), 15000);
    el("log-export").setAttribute("aria-disabled", "true");
    try {
      const query = new URLSearchParams(applied); query.set("page", String(page)); query.set("pageSize", "50");
      const response = await fetch(`./api/data/logbook?${query}`, {cache:"no-store", signal:ownController.signal});
      let data;
      try { data = await response.json(); } catch { throw Error("Flight log is unavailable."); }
      if (!response.ok) throw Error(data.error || "Flight log unavailable");
      if (!Array.isArray(data.entries) || !data.stats || !Array.isArray(data.health)) throw Error("Invalid flight log");
      if (current !== request) return;
      renderRows(data); renderStats(data);
      el("logbook").classList.remove("results-updated");
      void el("logbook").offsetWidth;
      el("logbook").classList.add("results-updated");
      const notes = [`Showing ${number(data.total)} movements · Dublin time · statistics reflect these filters.`];
      el("log-coverage").textContent = (data.allAircraftSince ? `All-aircraft 10 km logging since ${dateTime(data.allAircraftSince)}. ` : "") +
        (data.oldestAt ? `Available observations: ${dateTime(data.oldestAt)} to ${dateTime(data.newestAt)}.` : "No observations recorded yet.");
      for (const h of data.health) {
        if (!h.enabled) notes.push("Recording is disabled.");
        if (h.storageStatus !== "saved") notes.push("Log storage needs attention; new observations may be in memory only.");
        if (h.receiverStatus !== "live" || !h.lastSampleAt || Date.now() - h.lastSampleAt > 30000) notes.push("Receiver unavailable or recorder delayed; saved records remain browsable.");
        if (h.capacityLimited) notes.push("Storage safety limit reached: some observations in the window may be missing.");
      }
      el("log-status").textContent = notes.join(" ");
      el("log-export").href = `./api/data/logbook.csv?${applied}`;
      el("log-export").removeAttribute("aria-disabled");
    } catch (error) {
      if (current !== request) return;
      el("log-status").textContent = `${error.name === "AbortError" ? "Request timed out." : error.message} Previous results, if shown, have not been refreshed. Check filters and retry.`;
      el("log-prev").disabled = true; el("log-next").disabled = true;
    } finally {
      clearTimeout(timeout);
      if (current === request) {
        el("log-results").setAttribute("aria-busy", "false");
        el("log-results").classList.remove("updating");
      }
      if (current === request && !demo) timer = setTimeout(() => { if (!dirty) refresh(); }, 60000);
    }
  }
  form.addEventListener("submit", (event) => { event.preventDefault(); applyFilters(true); });
  form.addEventListener("reset", () => { setTimeout(() => { periodFields(); applyFilters(true); }, 0); });
  form.addEventListener("input", () => {
    dirty = true; clearTimeout(editTimer);
    editTimer = setTimeout(() => applyFilters(), 350);
  });
  form.addEventListener("change", () => { periodFields(); applyFilters(); });
  el("log-prev").addEventListener("click", () => { page = Math.max(1, responsePage - 1); refresh(); });
  el("log-next").addEventListener("click", () => { page = Math.min(pages, responsePage + 1); refresh(); });
  el("log-export").addEventListener("click", (event) => { if (el("log-export").getAttribute("aria-disabled") === "true") event.preventDefault(); });
  if (demo) {
    el("log-status").textContent = "Demo: flight logging is available in the live view. Fictional aircraft are never recorded.";
    el("log-export").hidden = true; form.hidden = true; return;
  }
  async function loadPickers() {
    try {
      const response = await fetch("./api/data/logbook/options", {cache:"no-store"});
      if (!response.ok) throw Error("Options unavailable");
      const options = await response.json();
      for (const [name, key] of [["airline", "airlines"], ["origin", "origins"], ["destination", "destinations"]]) {
        if (!Array.isArray(options[key])) throw Error("Options unavailable");
        const picker = form.elements[name];
        for (const value of options[key]) {
          const option = node("option", value); option.value = value; picker.append(option);
        }
      }
    } catch { el("log-status").textContent = "Some filter choices could not be loaded. Reload to retry."; }
  }
  periodFields(); applied = fields(); refresh(); loadPickers();
}
