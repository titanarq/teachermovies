# Module: discovery

**Gradle path:** `:discovery`.

## Responsibility
- Announce the HTTP service over Android NSD / DNS-SD (service type `_http._tcp`) with its port and
  a device-derived instance name, so the phase-2 phone app can find the TV without typing anything.
  Only the service is announced: Android NSD cannot publish a resolvable host name, so no
  `movieassistant.local` host name is published (decision on #34) and the IP:port shown on the TV
  stays the way to reach it from a browser.
- Shared discovery client code the phase-2 mobile app reuses: browse and resolve the TV service
  (see "Client contract").

## Public contract (package `com.teachermovies.discovery`)
- `TvServiceInfo(instanceName, port, serviceType = "_http._tcp", attributes = emptyMap())`: what is
  announced; each `attributes` entry becomes one TXT record attribute.
- `ServiceNames.instanceName(deviceName: String?)`: `Movie Assistant` for a null or blank device
  name, `Movie Assistant (<deviceName>)` otherwise, with whitespace runs collapsed to one space and
  the device name truncated so the result is at most 63 UTF-8 bytes (the mDNS label limit) without
  ever splitting a code point.
- `AnnouncementState`: `Idle`, `Announcing(info)`, `Announced(info, registeredName)` and
  `Failed(info, reason)`. mDNS may rename an instance on a name conflict, so `registeredName` is
  the name actually registered.
- `ServiceAnnouncer { val state: StateFlow<AnnouncementState>; fun announce(info); fun stop() }`.
  Neither method throws: an invalid port (outside `1..65535`, rejected before the platform is
  touched), a platform failure callback and any exception raised by the platform all become
  `Failed` with a short reason. `announce` while something is already announced unregisters it
  first; `stop()` while `Idle` is a no-op. Callbacks from a registration that has been replaced or
  stopped are ignored.
- `NsdServiceAnnouncer(registrar: NsdRegistrar)` is the production `ServiceAnnouncer` and holds the
  whole state machine. `NsdRegistrar { register(info, callback); unregister() }` with
  `RegistrationCallback { onRegistered(registeredName); onFailed(reason); onUnregistered() }` is the
  seam that keeps NSD out of the unit tests.
- `AndroidNsdRegistrar(context)` is the only file that touches `NsdManager`: it builds an
  `NsdServiceInfo` (name, type, port, TXT attributes), calls `registerService` with
  `PROTOCOL_DNS_SD`, keeps the `RegistrationListener` for `unregisterService` and maps the listener
  callbacks onto `RegistrationCallback`. No `android.net.nsd` type appears in any public signature.
- `fake.FakeServiceAnnouncer` (ADR-0003, main source set): records `announce`/`stop` calls in order
  (`calls`); `announce` moves to `Announcing` and `stop` to `Idle`, and `emitAnnounced`,
  `emitFailed` and `setState` drive `state` from a test.
- The manifest declares `android.permission.INTERNET`. The module depends on no other project
  module.

## Client contract (package `com.teachermovies.discovery.client`)
Shared with the phase-2 phone app (#35), which reuses it unchanged.
- `DiscoveredTv(instanceName, host, port, attributes = emptyMap())` with
  `baseUrl = "http://$host:$port"`: a resolved TV service; `host` is an IP address literal (IPv4
  preferred when the platform reports several addresses), `attributes` the TXT record.
- `ServiceDiscoverer { fun discover(serviceType = "_http._tcp"): Flow<List<DiscoveredTv>> }`. Each
  emission is the complete current set, deduplicated by `instanceName` (the last resolution wins)
  and sorted by `instanceName`; a new emission follows every appearance, re-resolution or
  disappearance, and nothing is emitted for a service found but not resolved yet (so there is no
  initial empty emission). Failures (a browse or resolution error, or `NsdBrowser.start` throwing)
  leave the last emitted list in place and the flow open; only the collector cancelling ends it.
- `NsdBrowser { start(serviceType, callback); stop() }` with
  `BrowseCallback { onFound(instanceName); onLost(instanceName); onResolved(tv); onFailed(reason) }`
  is the seam that keeps NSD out of the unit tests.
- `NsdServiceDiscoverer(browser: NsdBrowser)` is the production `ServiceDiscoverer`: every
  collection builds a `callbackFlow` (unlimited buffer, so no callback is dropped), starts its own
  browse and calls `browser.stop()` exactly once in `awaitClose`.
- `AndroidNsdBrowser(context)` is the only client file that touches `NsdManager`:
  `discoverServices` with `PROTOCOL_DNS_SD`, `resolveService` for every found service (serialised,
  one resolution at a time, because older platforms reject concurrent ones), `stopServiceDiscovery`
  on `stop()`. The resolved `NsdServiceInfo` maps onto `DiscoveredTv` (host from its address, port,
  TXT attributes decoded as UTF-8, a valueless attribute as `""`). A resolution that completes
  after its service was lost or after `stop()` is dropped; platform errors become `onFailed`, and
  neither method throws.
- `fake.FakeServiceDiscoverer` (ADR-0003, main source set): `MutableStateFlow`-backed; `add(tv)`
  (replacing a TV with the same name), `remove(instanceName)` and `clear()` control the visible
  TVs, emitted deduplicated and sorted for any service type; `requestedServiceTypes` records the
  `discover` calls.

## Boundaries
- Announcement failure never blocks the server; the IP:port shown on the TV remains the fallback.
- Starting/stopping the announcement with the HTTP server lives in `:app-tv` (#103).

## Tests
JVM tests: `ServiceNamesTest` (default, blank, whitespace and truncation rules, including multi-byte
and surrogate-pair code points), `NsdServiceAnnouncerTest` (every state transition over a fake
`NsdRegistrar`) and `NsdServiceDiscovererTest` (found-then-resolved, lost, re-resolution, sorting,
failures keeping the list and the flow open, `stop()` exactly once on cancel, over a fake
`NsdBrowser`). NSD itself (`AndroidNsdRegistrar`, `AndroidNsdBrowser`) has no JVM test -- CI only checks that it
compiles; announcing and discovering on a real TV are manual checks.
