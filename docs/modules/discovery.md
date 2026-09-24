# Module: discovery

**Gradle path:** `:discovery`.

## Responsibility
- Announce the HTTP service over Android NSD / DNS-SD (service type `_http._tcp`) with its port and
  a device-derived instance name, so the phase-2 phone app can find the TV without typing anything.
  Only the service is announced: Android NSD cannot publish a resolvable host name, so no
  `movieassistant.local` host name is published (decision on #34) and the IP:port shown on the TV
  stays the way to reach it from a browser.
- Shared discovery client code the phase-2 mobile app reuses (browse/resolve: #102).

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

## Boundaries
- Announcement failure never blocks the server; the IP:port shown on the TV remains the fallback.
- Starting/stopping the announcement with the HTTP server lives in `:app-tv` (#103).

## Tests
JVM tests: `ServiceNamesTest` (default, blank, whitespace and truncation rules, including multi-byte
and surrogate-pair code points) and `NsdServiceAnnouncerTest` (every state transition over a fake
`NsdRegistrar`). NSD itself (`AndroidNsdRegistrar`) has no JVM test -- CI only checks that it
compiles; the announcement on a real TV is a manual check.
