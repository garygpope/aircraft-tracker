const el = (id) => document.getElementById(id);
let cityKey = "",
  photoGeneration = 0;
const pictures = new Map();
export function clearPhoto() {
  photoGeneration++;
  cityKey = "";
  el("photo").classList.remove("loaded");
  el("credit").replaceChildren();
}
const plain = (html) =>
  new DOMParser()
    .parseFromString(html || "", "text/html")
    .body.textContent.trim();
async function cityPhoto(city) {
  if (pictures.has(city) && pictures.get(city).expires > Date.now())
    return pictures.get(city).promise;
  const promise = (async () => {
    try {
      async function commons(extra) {
        const query = new URLSearchParams({
          origin: "*",
          action: "query",
          format: "json",
          prop: "imageinfo",
          iiprop: "url|extmetadata",
          iiurlwidth: "960",
          ...extra,
        });
        const response = await fetch(
          "https://commons.wikimedia.org/w/api.php?" + query,
          { signal: AbortSignal.timeout(7000) }
        );
        if (!response.ok) return null;
        const data = await response.json();
        for (const page of Object.values(data.query?.pages || {})) {
          const info = page.imageinfo?.[0],
            m = info?.extmetadata;
          if (!info || !m) continue;
          const license = plain(m.LicenseShortName?.value),
            artist = plain(m.Artist?.value);
          if (!artist || !license || !/^(CC|Public domain|PDM)/i.test(license))
            continue;
          const image = info.thumburl || info.url,
            source = info.descriptionurl;
          if (
            !/^https:\/\/[^/]*wikimedia\.org\//.test(image) ||
            !/^https:\/\/commons\.wikimedia\.org\//.test(source)
          )
            continue;
          return { image, source, artist, license };
        }
        return null;
      }
      try {
        const response = await fetch(
          "https://en.wikipedia.org/api/rest_v1/page/summary/" +
            encodeURIComponent(city),
          { signal: AbortSignal.timeout(5000) }
        );
        if (response.ok) {
          const summary = await response.json();
          if (summary.type === "standard" && summary.originalimage) {
            const path = new URL(summary.originalimage.source).pathname;
            // Wikipedia-hosted images can be non-free; use Commons originals only.
            if (path.includes("/commons/")) {
              const pieces = path.split("/"),
                file = decodeURIComponent(
                  path.includes("/thumb/")
                    ? pieces[pieces.length - 2]
                    : pieces[pieces.length - 1]
                );
              const match = await commons({ titles: "File:" + file });
              if (match) return match;
            }
          }
        }
      } catch {}
      return await commons({
        generator: "search",
        gsrsearch:
          '"' + city.replace(/["|]/g, "") + '" skyline filetype:bitmap',
        gsrnamespace: "6",
        gsrlimit: "5",
      });
    } catch {
      return null;
    }
  })();
  pictures.set(city, { promise, expires: Date.now() + 10 * 60 * 1000 });
  if (pictures.size > 40) pictures.delete(pictures.keys().next().value);
  return promise;
}
export async function setPhoto(city) {
  if (!city || city === cityKey) return;
  clearPhoto();
  cityKey = city;
  const generation = photoGeneration;
  // A neutral illustration stays visible if no properly credited city photo is available.
  el("photo").src = "./city-fallback.svg";
  el("photo").alt = "Generic city illustration";
  el("photo").classList.add("loaded");
  el("credit").textContent =
    "City illustration · finding a photograph of " + city;
  const result = await cityPhoto(city);
  if (generation !== photoGeneration) return;
  if (!result) {
    el("credit").textContent =
      "City illustration · no city photograph available";
    return;
  }
  const image = new Image();
  image.onload = () => {
    if (generation !== photoGeneration) return;
    el("photo").src = result.image;
    el("photo").alt = city + " city photograph";
    el("photo").classList.add("loaded");
    const a = document.createElement("a");
    a.href = result.source;
    a.target = "_blank";
    a.rel = "noopener noreferrer";
    a.textContent = `Photo: ${result.artist} · ${result.license} · Wikimedia Commons`;
    el("credit").replaceChildren(a);
  };
  image.onerror = () => {
    if (generation === photoGeneration)
      el("credit").textContent = "City illustration · photograph unavailable";
  };
  image.src = result.image;
}
