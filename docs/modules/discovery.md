# Module: discovery

**Gradle path:** `:discovery`.

## Responsibility
- Announce the HTTP service over Android NSD / mDNS (e.g. `movieassistant.local`, service type `_http._tcp`/custom) with port and device name.
- Shared discovery client code the phase-2 mobile app reuses.

## Boundaries
- Announcement failure never blocks the server; the IP:port shown on the TV remains the fallback.

## Tests
JVM tests for service-info building; NSD itself is instrumented/manual.
