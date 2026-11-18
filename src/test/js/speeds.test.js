const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.join(__dirname, '../../main/resources/display');
const elements = new Map();
function element() { return {textContent:'', title:'', hidden:false, dataset:{}, classList:{add(){},remove(){},toggle(){}}, replaceChildren(){}, append(){}}; }
const noop = () => {};
const context = {
  URLSearchParams, Date, Math, Number, location:{search:''}, setInterval:noop,
  initializeLayout:noop, initializeRadar:noop, initializeHistory:noop,
  airlineHeader:noop, clearPhoto:noop, setPhoto:noop, radar:noop,
  document:{body:{classList:{add(){},remove(){},contains(){return false;}}},
    createElement:element, getElementById(id){if(!elements.has(id))elements.set(id,element());return elements.get(id);}},
};
vm.createContext(context);
vm.runInContext(fs.readFileSync(path.join(root,'traffic.js'),'utf8'), context);
const source = fs.readFileSync(path.join(root,'app.js'),'utf8').replace(/^import .*;\n/gm,'').replace(/refresh\(\);\s*$/, '');
vm.runInContext(source,context);
const aircraft = {hex:'first',flight:'EAI85Y',distanceKm:1,lat:53.417,lon:-6.148,altitudeFeet:1300,routeStatus:'not-found',groundSpeedKnots:142,indicatedAirspeedKnots:138};
context.render([aircraft]);
assert.equal(elements.get('ias').textContent,'138');
assert.equal(elements.get('ias-kmh').textContent,'· 256 km/h');
// A new closest aircraft with no reported speeds must clear the previous one.
context.render([{...aircraft,hex:'next',groundSpeedKnots:null,indicatedAirspeedKnots:null}]);
assert.equal(elements.get('ias').textContent,'—');
assert.equal(elements.get('ias-kmh').textContent,'');
context.render([{...aircraft,groundSpeedKnots:0,indicatedAirspeedKnots:0}]);
assert.equal(elements.get('ias').textContent,'0');
context.offline();
assert.equal(elements.get('ias-kmh').textContent,'');
context.render([]);
assert.equal(elements.get('ias').textContent,'—');
assert.equal(elements.get('ias').title,'');
// Type must follow the featured aircraft and clear on handover, empty sky and outage.
context.render([{...aircraft,aircraftType:'Boeing 737-800',aircraftTypeCode:'B738'}]);
assert.equal(elements.get('live-aircraft-type').textContent,'Boeing 737-800');
assert.equal(elements.get('focus-label').textContent,'Current aircraft · closest to home');
assert.equal(elements.get('live-aircraft-type').hidden,false);
context.render([{...aircraft,hex:'different'}]);
assert.equal(elements.get('live-aircraft-type').textContent,'');
assert.equal(elements.get('live-aircraft-type').hidden,true);
context.render([{...aircraft,aircraftTypeCode:'A320'}]);
assert.equal(elements.get('live-aircraft-type').textContent,'A320');
context.offline();
assert.equal(elements.get('live-aircraft-type').hidden,true);
context.render([{...aircraft,aircraftType:'Airbus A320'}]);
context.render([]);
assert.equal(elements.get('live-aircraft-type').textContent,'');
assert.equal(elements.get('live-aircraft-type').hidden,true);
assert.equal(elements.get('flight').textContent,'');
assert.equal(elements.get('focus-label').textContent,'Current aircraft · none nearby');
// The browser title follows receiver health, including a live but empty sky.
context.show([]);
assert.equal(context.document.title,"St Marnock's Airspace - Live");
context.offline();
assert.equal(context.document.title,"St Marnock's Airspace");
context.show([aircraft]);
assert.equal(context.document.title,"St Marnock's Airspace - Live");
const demoContext = {...context, location:{search:'?demo=1'}, document:{...context.document,title:"St Marnock's Airspace"}};
vm.createContext(demoContext); vm.runInContext(source,demoContext);
demoContext.show([aircraft]);
assert.equal(demoContext.document.title,"St Marnock's Airspace");
console.log('IAS conversion, closest-aircraft handover, zero speed, type display and receiver-title checks passed');
