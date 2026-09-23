# Module: storage

**Gradle path:** `:storage`.

## Responsibility
- Enumerate storage volumes (internal, USB, SSD) via `StorageManager`; pick and persist the download volume.
- Layout `<volume>/Movies/<torrent-id>/...`; free/total space; detect removed volumes.
- File access helpers used by torrent (save path) and player (file URI).

## Boundaries
- No knowledge of torrents beyond an id and a path. Removable media going away is a normal state, not a crash.

## Tests
JVM tests for layout/path logic with a fake filesystem root.
