# Module: mobile-app

**Gradle path:** `:mobile-app` (phase 2 -- not part of MVP 1.0).

## Responsibility
- Small Android phone app: discover the TV via NSD, pair with the PIN, keep the token.
- `Share -> Movie Assistant` intent filter that sends a magnet/link to the TV API; show download progress.

## Boundaries
- Uses only the public HTTP API; no shared code with `app-tv` beyond `discovery` and API DTOs.

## Tests
JVM tests for link parsing and API client with a fake server.
