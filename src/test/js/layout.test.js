const assert = require("assert").strict;
const fs = require("fs");
const vm = require("vm");
const source = fs.readFileSync(require("path").join(__dirname, "../../main/resources/display/layout.js"), "utf8").replace("export function", "function");
function layout(query, width = 1440, height = 900) {
  const classes = new Set(), elements = {}, listeners = {}, main = { style: {} };
  const links = [];
  const context = {
    URL, innerWidth: width, innerHeight: height,
    location: { href: "http://local/overtheroof/?" + query },
    addEventListener(name, fn) { listeners[name] = fn; },
    document: {
      body: { classList: {
        toggle(key, enabled) { enabled ? classes.add(key) : classes.delete(key); },
        contains: key => classes.has(key), remove: key => classes.delete(key),
      } },
      getElementById(id) { return elements[id] || (elements[id] = { hidden: true, addEventListener(name, fn) { this[name] = fn; } }); },
      querySelectorAll() { return links; }, querySelector() { return main; },
    },
  };
  vm.createContext(context); vm.runInContext(source, context);
  context.initializeLayout(new URLSearchParams(query));
  return { classes, elements, listeners, main, links, context };
}
const responsive = layout("demo=1&scene=quiet", 320, 740);
assert.equal(responsive.classes.has("nest"), false);
assert.equal(responsive.main.style.transform, undefined);
const nest = layout("view=nest", 1024, 600);
assert.equal(nest.classes.has("nest"), true);
assert.equal(nest.main.style.transform, "scale(1)");
const cast = layout("cast=1&motion=on");
assert.equal(cast.classes.has("nest"), true);
assert.equal(cast.classes.has("cast-view"), true);
assert.equal(cast.main.style.width, "1024px");
assert.equal(cast.elements["preview-controls"].hidden, true);
cast.context.innerWidth = 1024; cast.context.innerHeight = 600; cast.listeners.resize();
assert.equal(cast.main.style.transform, "scale(1)");
assert.equal(cast.main.style.height, "600px");
const legacy = layout("preview=fit&demo=1");
assert.equal(legacy.classes.has("nest"), true);
const preview = layout("view=nest&demo=1&scene=offline");
preview.elements["preview-toggle"].click();
assert.equal(new URL(preview.context.location.href).searchParams.get("cast"), "1");
assert.equal(new URL(preview.context.location.href).searchParams.get("scene"), "offline");
console.log("Responsive, Nest, cast, resize, and legacy preview checks passed");
