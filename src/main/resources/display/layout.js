const el = (id) => document.getElementById(id);
export function initializeLayout(params) {
  document.body.classList.add("nest");
  const castMode = params.get("cast") === "1";
  let fillPreview = castMode || params.get("preview") === "fit";
  document.body.classList.toggle("cast-view", castMode);
  function fitNest() {
    if (!document.body.classList.contains("nest")) return;
    const desktop = innerWidth > 1100 || innerHeight > 700;
    if (castMode) {
      const main = document.querySelector("main"),
        scale = innerWidth / 1024;
      main.style.width = "1024px";
      main.style.height = innerHeight / scale + "px";
      main.style.left = "0";
      main.style.top = "0";
      main.style.transform = `scale(${scale})`;
      document.body.classList.remove("preview");
      el("preview-controls").hidden = true;
      return;
    }
    const availableHeight = innerHeight - (desktop && !fillPreview ? 56 : 0);
    const scale = Math.min(
        fillPreview ? Infinity : 1,
        innerWidth / 1024,
        availableHeight / 600
      ),
      main = document.querySelector("main");
    main.style.left = (innerWidth - 1024 * scale) / 2 + "px";
    main.style.top = (availableHeight - 600 * scale) / 2 + "px";
    main.style.transform = `scale(${scale})`;
    document.body.classList.toggle("preview", desktop && !fillPreview);
    el("preview-controls").hidden = !desktop;
    el("preview-size").textContent = `Nest Hub · 1024 × 600 · ${Math.round(
      scale * 100
    )}%`;
    el("preview-toggle").textContent = fillPreview
      ? "Actual-size preview"
      : "Fill tab for casting";
  }
  el("preview-toggle").addEventListener("click", () => {
    if (!fillPreview) {
      const url = new URL(location.href);
      url.searchParams.set("cast", "1");
      location.href = url.href;
    } else {
      fillPreview = false;
      fitNest();
    }
  });
  fitNest();
  addEventListener("resize", fitNest);
}
