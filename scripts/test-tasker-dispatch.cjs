// Executes the shipped JavaScriptlet with a Tasker-shaped bridge; no device is required.
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../tasker/dispatch.js'), 'utf8');
const protocol = fs.readFileSync(path.join(__dirname, '../app/src/main/java/dev/burixon/mockgps/core/AutomationCommand.kt'), 'utf8');
const apiVersion = Number(protocol.match(/const val API_VERSION = (\d+)/)[1]);
assert.equal(apiVersion, 3);
function run(globals, locals = {}) {
  vm.runInNewContext(source, {
    global: name => globals[name], local: name => locals[name],
    setGlobal: (name, value) => { globals[name] = value; },
    setLocal: (name, value) => { locals[name] = value; },
    Date: { now: () => 1700000000000 }
  });
  return locals;
}
const seed = () => ({ MockGPSReady: '1', MockGPSToken: 'a'.repeat(64), MockGpsSeq: '12' });
for (const command of ['request_simulation', 'pause', 'unknown', 'stop']) {
  const globals = seed(); const result = run(globals, { par1: command });
  assert(result.mgps_uri.includes(`S.command=${command};`));
  assert(result.mgps_uri.includes(`i.api=${apiVersion};`));
  assert(result.mgps_uri.includes('l.event_time=1700000000000;l.seq=13;'));
  assert(result.mgps_uri.includes('component=dev.abhik.mockgps/dev.burixon.mockgps.MockGpsReceiver;'));
  assert.equal(globals.MockGpsSeq, '13');
  assert.equal(result.mgps_ready, '1');
}
for (const command of ['', 'start', 'request_simulation;S.token=bad', 'vehicle_disconnected']) {
  const globals = seed();
  assert.equal(run(globals, { par1: command }).mgps_uri, '');
  assert.equal(globals.MockGpsSeq, '12');
}
for (const [key, value] of [['MockGPSReady', '0'], ['MockGPSToken', 'bad;S.command=stop'],
  ['MockGpsSeq', ''], ['MockGpsSeq', '-1'], ['MockGpsSeq', '2147483000'],
  ['MockGpsSeq', '9007199254740993']]) {
  const globals = seed(); globals[key] = value; const old = globals.MockGpsSeq;
  const result = run(globals, { par1: 'request_simulation' });
  assert.equal(result.mgps_uri, ''); assert.equal(result.mgps_ready, '0');
  assert.equal(globals.MockGpsSeq, old);
}
const globals = seed();
assert(run(globals, { par1: 'status' }).mgps_uri.includes('l.seq=0;'));
assert.equal(globals.MockGpsSeq, '12');
assert(run(globals, { par1: 'stop' }).mgps_uri.includes('l.seq=13;'));
assert.equal(globals.MockGpsSeq, '13');
const presetGlobals = seed();
const presetId = '00000000-0000-0000-0000-000000000000';
const selected = run(presetGlobals, { par1: 'select_preset', par2: presetId });
assert(selected.mgps_uri.includes(';S.preset_id=' + presetId + ';end'));
assert.equal(presetGlobals.MockGpsSeq, '13');
for (const preset of ['', 'bad;S.command=stop', 42]) {
  const invalid = seed();
  assert.equal(run(invalid, { par1: 'select_preset', par2: preset }).mgps_ready, '0');
  assert.equal(invalid.MockGpsSeq, '12');
}
console.log('Tasker dispatcher: command allowlist, typed URI, credentials, counter bounds and read-only status passed.');
