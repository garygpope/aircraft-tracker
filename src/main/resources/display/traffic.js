/* Classification describes a route match or a movement estimate, never a verified flight plan. */
globalThis.SkyTraffic = (() => {
  const HOME_RANGE_KM = 10,
    AIRPORT = { lat: 53.4213, lon: -6.27007 };
  const angle = (a, b) => Math.abs(((a - b + 540) % 360) - 180);
  function bearing(lat, lon) {
    const r = Math.PI / 180,
      a = lat * r,
      b = AIRPORT.lat * r,
      d = (AIRPORT.lon - lon) * r;
    return (
      (Math.atan2(
        Math.sin(d) * Math.cos(b),
        Math.cos(a) * Math.sin(b) - Math.sin(a) * Math.cos(b) * Math.cos(d)
      ) /
        r +
        360) %
      360
    );
  }
  function classify(p) {
    if (p.routeStatus === "found") {
      if (p.destinationCode === "EIDW")
        return {
          kind: "arrival",
          label: "Dublin arrival",
          priority: 0,
          basis: "Route match",
        };
      if (p.originCode === "EIDW")
        return {
          kind: "departure",
          label: "Dublin departure",
          priority: 0,
          basis: "Route match",
        };
    }
    // A conservative local estimate; never use it to invent an origin/destination.
    if (
      p.distanceKm <= HOME_RANGE_KM &&
      p.altitudeFeet != null &&
      p.altitudeFeet > 0 &&
      p.altitudeFeet <= 6000 &&
      Number.isFinite(p.track) &&
      Number.isFinite(p.lat) &&
      Number.isFinite(p.lon)
    ) {
      const toward = bearing(p.lat, p.lon);
      if (p.verticalRate < -200 && angle(p.track, toward) <= 45)
        return {
          kind: "likely",
          label: "Likely Dublin arrival",
          priority: 1,
          basis: "Movement estimate",
        };
      if (p.verticalRate > 200 && angle(p.track, (toward + 180) % 360) <= 45)
        return {
          kind: "likely",
          label: "Likely Dublin departure",
          priority: 1,
          basis: "Movement estimate",
        };
    }
    return {
      kind: "other",
      label: p.routeStatus === "found" ? "Other route" : "Other / unknown",
      priority: 2,
      basis: p.routeStatus === "found" ? "Route match" : "Unclassified",
    };
  }
  const inRange = (p) =>
    Number.isFinite(p.distanceKm) && p.distanceKm <= HOME_RANGE_KM;
  const order = (a, b) =>
    classify(a).priority - classify(b).priority || a.distanceKm - b.distanceKm;
  return {
    HOME_RANGE_KM,
    classify,
    inRange,
    order,
    select: (planes) => planes.filter(inRange).sort(order)[0],
  };
})();
