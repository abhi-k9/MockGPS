# Tasker integration

MockGPS accepts the authenticated **version 3** intent API. Earlier protocol versions are rejected; configure existing senders for version 3 and the generic commands below. Profiles can use any context Tasker supports, such as time, application, network, or a user action. MockGPS does not inspect or assume the context. This sample is optional; other explicit-intent automation tools can implement the same [API](../docs/AUTOMATION.md).

`request_simulation` makes a delayed request using the approval mode selected in MockGPS. Manual confirmation is the default. Optional **Unattended automation** can start injection after the delay while its foreground session is armed. `pause` cancels requests and blocks new ones until the source requests again. `unknown` reports an unreconciled context. `stop` selects the persistent Force Real override; `status` reads current state.

## Choose the approval mode

To run unattended, enable **Settings → Unattended automation** in MockGPS while the app is visible, check that the session is ready, select Auto, and then send a fresh request. This is a general MockGPS setting; Tasker cannot enable it or clear Force real through the API. All enabled sources must still agree.

The prepared session keeps Android's required ongoing notification while idle. A saved opt-in is not proof of an armed service. Reopen MockGPS after process death or a device reboot to prepare the session, then reconcile the current context. If the session is missing when the delay finishes, automatic start falls back to manual confirmation instead of recreating a background service.

Configure optional **Change alerts** in MockGPS as a notification (default), toast, both, or none. This does not remove the required ongoing notification; delivery depends on Android permissions and restrictions. See [approval and status fields](../docs/AUTOMATION.md#approval-and-change-alerts).

## Configure a single dispatcher

1. In MockGPS, enable **Intent automation** and copy its private credential. Set Tasker global `%MockGPSReady` to `0` while configuring.
2. Set `%MockGPSToken` to that credential. For a newly issued credential only, initialize `%MockGpsSeq` to `0`. Keep the existing counter when retaining a credential. Losing the counter requires revoking and reissuing the credential in MockGPS before reseeding. Remove or disable old senders first.
3. Create one Tasker task named **MockGPS Dispatch**. Use collision handling **Abort Existing Task** and avoid Wait or dialog actions. Add a **JavaScriptlet** action containing [dispatch.js](dispatch.js).
4. After that action, continue only if `%mgps_ready` equals `1`. Add a **Java Function** action returning a local `Intent` object from `Intent.parseUri(%mgps_uri, 0)`, then another Java Function calling `CONTEXT.sendBroadcast(Intent)` with that object. Using `parseUri` preserves the required `int` and `long` extras; a sender that converts these fields to strings will be rejected.
5. Set `%MockGPSReady` to `1`. Invoke the dispatcher with **Perform Task**, passing a command in parameter 1. Start with `status`, then `unknown`, then `stop`. Check the accepted-command/rejection display in MockGPS; sending a broadcast is not acknowledgement of acceptance or simulation.

Keep the credential and generated URI out of logs, screenshots, exports and shared backups. Tasker owns storage of its variables; MockGPS cannot protect another application's backup files. Revoke the credential if it may have been disclosed. The sample intentionally does not initialize globals, reset counters, or select a profile context.

## Connect your profiles

Call the same dispatcher with:

| Profile outcome | Parameter 1 |
| --- | --- |
| Current context permits a simulation request | `request_simulation` |
| Current context blocks simulation | `pause` |
| Context unavailable, permissions missing, or automation just restarted | `unknown` |
| Explicit user action to remain stopped | `stop` |
| Read current status | `status` |

Derive commands from the current context immediately before dispatch. Do not queue or replay old observations. Reconcile after Tasker startup, device restart or monitoring changes; silence is not permission to simulate. With both intent automation and app shortcuts enabled, both sources must agree before a request can proceed. Source observations expire across application recovery.

One serialized task must own the counter for a credential. Concurrent tasks, restored backups and multiple automation apps must not independently reuse the same credential. A skipped sequence is harmless; a reused or decreasing sequence is rejected. The sample stops before counter exhaustion and requires deliberate credential rotation. A status request never advances the mutation counter.

## Verify on your device

Test a request, cancellation, context loss, application recovery and credential revocation. With manual approval, confirm that requests still need visible confirmation. With unattended approval enabled and armed, confirm that a fresh request starts only after the configured minimum delay. Test missing-session fallback, opting out during a pending delay, and notification/toast preferences. Cancellation must stop active simulation, and Force real must remain selected until you explicitly return to Auto. Android and manufacturer restrictions can delay automation or stop the session; a dispatched event does not grant permission to recreate a missing background service.

The JavaScript has local contract tests (`node scripts/test-tasker-dispatch.cjs`). Import and execution in Tasker require device verification.
