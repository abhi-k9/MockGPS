// Tasker JavaScriptlet for one serialized dispatcher task. No credentials are logged.
// Protocol 3. Pass request_simulation, pause, unknown, stop, status, or select_preset as parameter 1; preset ID as parameter 2.
var command = local('par1') || '';
var commands = ['request_simulation', 'pause', 'unknown', 'stop', 'status', 'select_preset'];
var preset = local('par2') || '';
var token = global('MockGPSToken') || '';
var counter = global('MockGpsSeq') || '';
var ready = global('MockGPSReady') === '1';
var valid = ready && commands.indexOf(command) >= 0 &&
    /^[0-9a-f]{64}$/.test(token) && /^\d+$/.test(counter);
valid = valid && (command !== 'select_preset' || /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(preset));
var sequence = Number(counter);
valid = valid && Number.isSafeInteger(sequence) && sequence >= 0 && sequence < 2147483000;
var uri = '';
if (valid) {
    // Never seed or reset automatically. Persist before sending; gaps are harmless.
    if (command !== 'status') { sequence += 1; setGlobal('MockGpsSeq', String(sequence)); }
    else sequence = 0; // Read-only status does not consume mutation ordering.
    uri = 'intent:#Intent;action=dev.abhik.mockgps.CONTROL;' +
        'component=dev.abhik.mockgps/dev.burixon.mockgps.MockGpsReceiver;' +
        'i.api=3;S.token=' + token + ';S.command=' + command +
        ';l.event_time=' + Date.now() + ';l.seq=' + sequence +
        (command === 'select_preset' ? ';S.preset_id=' + preset : '') + ';end';
}
setLocal('mgps_uri', uri);
setLocal('mgps_ready', valid ? '1' : '0');
