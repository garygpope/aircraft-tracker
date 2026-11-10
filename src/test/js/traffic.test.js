const assert = require("assert").strict;
require("../../main/resources/display/traffic.js");
const s = globalThis.SkyTraffic;
const cross = {
  hex: "cross",
  distanceKm: 1,
  routeStatus: "found",
  originCode: "EGLL",
  destinationCode: "KJFK",
  altitudeFeet: 35000,
};
const arrival = {
  hex: "in",
  distanceKm: 9,
  routeStatus: "found",
  destinationCode: "EIDW",
};
const departure = {
  hex: "out",
  distanceKm: 6,
  routeStatus: "found",
  originCode: "EIDW",
};
assert.equal(s.select([cross, arrival]).hex, "in");
assert.equal(s.select([arrival, departure]).hex, "out");
assert.equal(s.select([{ ...arrival, distanceKm: 10.01 }]), undefined);
assert.equal(s.inRange({ ...arrival, distanceKm: 10 }), true);
assert.equal(s.classify(cross).kind, "other");
const low = {
  distanceKm: 2,
  lat: 53.417,
  lon: -6.13,
  track: 275,
  altitudeFeet: 2000,
  verticalRate: -800,
  routeStatus: "uncertain",
};
assert.equal(s.classify(low).label, "Likely Dublin arrival");
assert.equal(
  s.classify({ ...low, track: 95, verticalRate: 800 }).label,
  "Likely Dublin departure"
);
assert.equal(s.classify({ ...low, altitudeFeet: 35000 }).kind, "other");
assert.equal(s.classify({ ...low, track: null }).kind, "other");
console.log("9 traffic priority, range, and classification checks passed");
