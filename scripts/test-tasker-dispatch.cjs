// Executes the shipped JavaScriptlet under a fake Tasker bridge; never phone/import proof.
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync('tasker/dispatch.js', 'utf8');
function run(globals, locals = {}) {
  vm.runInNewContext(source, {
    global: name => globals[name], local: name => locals[name],
    setGlobal: (name, value) => { globals[name] = value; },
    setLocal: (name, value) => { locals[name] = value; },
    Date: { now: () => 1792800000000 }
  });
  return locals;
}
const seed = () => ({ MockGPSReady:'1', MockGPSToken:'a'.repeat(64), MockGpsSeq:'12',
  BLUE:'on', MockGPSBTVerified:'1' });
for (const [bt, command] of [['yes','vehicle_connected'], ['no','vehicle_disconnected'],
  ['', 'vehicle_unknown'], ['unexpected','vehicle_unknown']]) {
  const g = seed(); const l=run(g,{mgps_bt:bt});
  assert(l.mgps_uri.includes(`S.command=${command};`));
  assert(l.mgps_uri.includes('l.seq=13;'));
  assert.equal(g.MockGpsSeq,'13');
}
for (const [key,value] of [['BLUE','off'], ['MockGPSBTVerified','0']]) {
  const g=seed();g[key]=value;assert(run(g,{mgps_bt:'no'}).mgps_uri.includes('S.command=vehicle_unknown;'));
}
for (const [key,value] of [['MockGPSReady','0'], ['MockGPSToken','bad;S.command=stop'],
  ['MockGpsSeq',''], ['MockGpsSeq','-1'], ['MockGpsSeq','2147483000']]) {
  const g=seed();g[key]=value;const old=g.MockGpsSeq;
  assert.equal(run(g).mgps_uri,'');assert.equal(g.MockGpsSeq,old);
}
const g=seed();assert(run(g,{par1:'status'}).mgps_uri.includes('l.seq=0;'));
assert.equal(g.MockGpsSeq,'12');
assert(run(g,{par1:'stop',mgps_bt:'yes'}).mgps_uri.includes('S.command=stop;'));
assert.equal(g.MockGpsSeq,'13');
console.log('Tasker dispatcher model: state, BT-off, unverified no, credential/counter guards, status and stop passed; actual import NOT RUN');
