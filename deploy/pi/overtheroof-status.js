(function () {
  "use strict";

  function initialize() {
    var indicators = document.getElementById("indicators");
    if (!indicators || document.getElementById("status-overtheroof")) return;

    var group = document.createElement("div");
    group.className = "btn-group";
    group.setAttribute("role", "group");
    var button = document.createElement("button");
    button.id = "status-overtheroof";
    button.type = "button";
    button.textContent = "Over the Roof";
    button.setAttribute("data-toggle", "tooltip");
    button.setAttribute("data-placement", "bottom");
    button.addEventListener("click", function () {
      window.location.assign("/overtheroof/");
    });
    group.appendChild(button);

    function status(color, health, message) {
      button.className = "btn btn-" + color;
      button.dataset.health = health;
      button.title = message;
      button.setAttribute("data-original-title", message);
      button.setAttribute("aria-label", "Over the Roof: " + message);
    }
    status("warning", "checking", "Checking Over the Roof");

    // PiAware rebuilds this row on each status update; retain our own indicator.
    function mount() {
      if (group.parentNode !== indicators) {
        indicators.appendChild(group);
        if (window.jQuery && window.jQuery.fn.tooltip) {
          window.jQuery(button).tooltip({ container: "body" });
        }
      }
    }
    new MutationObserver(mount).observe(indicators, { childList: true });
    mount();

    async function checkHealth() {
      var controller = new AbortController();
      var timeout = setTimeout(function () { controller.abort(); }, 5000);
      try {
        var response = await fetch("/overtheroof/api/data/aircraft", {
          cache: "no-store",
          signal: controller.signal
        });
        if (!response.ok || !Array.isArray(await response.json())) {
          throw new Error("Tracker or receiver unavailable");
        }
        status("success", "running", "Running — open Over the Roof");
      } catch (error) {
        status("danger", "unavailable", "Unavailable — click to try opening Over the Roof");
      } finally {
        clearTimeout(timeout);
        setTimeout(checkHealth, 10000);
      }
    }
    checkHealth();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initialize, { once: true });
  } else {
    initialize();
  }
})();
