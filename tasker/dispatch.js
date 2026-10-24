// A single Tasker JavaScriptlet action: validate, allocate, snapshot; no secrets logged.
// Called after a fresh Test Net observation in the one mutation dispatcher.
var command = local('par1');
if (['stop', 'vehicle_unknown', 'status'].indexOf(command) < 0) {
    command = 'vehicle_unknown';
    if (global('BLUE') === 'on') {
        var connected = local('mgps_bt');
        if (connected === 'yes') command = 'vehicle_connected';
        if (connected === 'no' && global('MockGPSBTVerified') === '1') command = 'vehicle_disconnected';
    }
}
var token = global('MockGPSToken') || '';
var counter = global('MockGpsSeq') || '';
var ready = global('MockGPSReady') === '1';
var valid = ready && /^[0-9a-f]{64}$/.test(token) && /^\d+$/.test(counter);
var sequence = Number(counter);
valid = valid && Number.isSafeInteger(sequence) && sequence >= 0 && sequence < 2147483000;
var uri = '';
if (valid) {
    // Never seed or reset a counter automatically, including import/boot/token changes.
    if (command !== 'status') { sequence += 1; setGlobal('MockGpsSeq', String(sequence)); }
    else sequence = 0; // API status is read-only and cannot consume mutation ordering.
    uri = 'intent:#Intent;action=dev.abhik.mockgps.CONTROL;' +
        'component=dev.abhik.mockgps/dev.burixon.mockgps.MockGpsReceiver;' +
        'i.api=2;S.token=' + token + ';S.command=' + command +
        ';l.event_time=' + Date.now() + ';l.seq=' + sequence + ';end';
}
setLocal('mgps_uri', uri);
setLocal('mgps_ready', valid ? '1' : '0');
