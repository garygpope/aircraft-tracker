const el = (id) => document.getElementById(id);
const airlineDirectory = {
  RYR: ["Ryanair", "FR"],
  RUK: ["Ryanair UK", "RK"],
  MAY: ["Malta Air", "AL"],
  EIN: ["Aer Lingus", "EI"],
  EAI: ["Aer Lingus Regional", "EI"],
  BAW: ["British Airways", "BA"],
  SWR: ["SWISS", "LX"],
  DLH: ["Lufthansa", "LH"],
  AFR: ["Air France", "AF"],
  KLM: ["KLM", "KL"],
  FIN: ["Finnair", "AY"],
  ETD: ["Etihad Airways", "EY"],
  UAE: ["Emirates", "EK"],
  QTR: ["Qatar Airways", "QR"],
  THY: ["Turkish Airlines", "TK"],
  SAS: ["Scandinavian Airlines", "SK"],
  IBE: ["Iberia", "IB"],
  VLG: ["Vueling", "VY"],
  TAP: ["TAP Air Portugal", "TP"],
  UAL: ["United Airlines", "UA"],
  DAL: ["Delta Air Lines", "DL"],
  AAL: ["American Airlines", "AA"],
  ACA: ["Air Canada", "AC"],
};
let logoKey = "";
export function airlineHeader(p) {
  if (!p) {
    logoKey = "";
    el("logo-box").hidden = true;
    el("airline-logo").removeAttribute("src");
    el("airline-name").textContent = "LOCAL SKY";
    el("flight-reference").textContent = "";
    return;
  }
  const prefix = (p.airlineIcao || p.flight?.slice(0, 3) || "").toUpperCase(),
    known = airlineDirectory[prefix];
  el("airline-name").textContent =
    p.airlineName || known?.[0] || "Aircraft nearby";
  const number =
    p.routeStatus === "found" &&
    /^[A-Z0-9]{2}[0-9]{1,4}$/.test(p.reportedFlightNumber || "")
      ? p.reportedFlightNumber
      : null;
  el("flight").textContent = number
    ? number.slice(0, 2) + " " + number.slice(2)
    : p.flight || p.hex;
  el("flight-reference").textContent = number
    ? "Reported flight · callsign " + (p.flight || p.hex)
    : "CALLSIGN · flight number unavailable";
  const logoCode =
    prefix === "EAI"
      ? "EIN"
      : prefix === "RUK" || prefix === "MAY"
      ? "RYR"
      : prefix;
  if (logoCode === logoKey) return;
  logoKey = logoCode;
  el("logo-box").hidden = true;
  if (!airlineDirectory[prefix]) return;
  const img = el("airline-logo");
  img.onload = () => {
    el("logo-box").hidden = false;
  };
  img.onerror = () => {
    el("logo-box").hidden = true;
  };
  img.alt = (p.airlineName || known[0]) + " logo";
  img.src = "./logos/" + logoCode + ".png";
}
