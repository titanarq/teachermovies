# Module: laptop-bridge

**Gradle path:** `:laptop-bridge` (a pure-JVM `application`); its wire DTOs come from `:bridge-protocol`.

## Responsibility
- The laptop-side half of ADR-0005: a Kotlin/JVM command-line program, `teachermovies-bridge`, that
  runs on the machine holding the human's Claude Code subscription -- with the laptop on but no
  desktop login, once #278 installs it as a systemd user service.
- #271 is the skeleton: pair with the TV over its PIN, keep the resulting bridge-scoped token safely
  on disk, report its own health with `doctor`, and print one page of the TV's log ring buffer with
  `logs`. Nothing else runs on the laptop yet (see "Boundaries").
- Pure JVM, never an Android library, so it depends only on `:bridge-protocol` and never on
  `:http-server` or any other Android module (ADR-0005 §1, AGENTS.md). The Ktor *server* artifacts
  are test-only here, for the in-test fake TV.
- This doc covers `:bridge-protocol` too, which has no label or doc of its own (AGENTS.md): the
  pure-JVM DTOs shared with `:http-server` -- `LogsPageDto(bootId, entries)` of
  `LogEntryDto(seq, timeMs, level, module, message)`, and `LogStreamBootDto(bootId)`, the first
  frame of the SSE stream that #272 mirrors and this module does not call yet.
- Every string it prints is Spanish, the product's language; the code and this doc are English.

## Command line (package `com.teachermovies.bridge.cli`)
- Entry point `com.teachermovies.bridge.MainKt`. The Gradle `application` plugin sets
  `applicationName = "teachermovies-bridge"` (ADR-0004, "Gradle `application` plugin"), so
  `./gradlew :laptop-bridge:installDist` writes the start script
  `laptop-bridge/build/install/teachermovies-bridge/bin/teachermovies-bridge` that #278 will launch,
  and `./gradlew :laptop-bridge:run --args="doctor"` works without installing.
- `BridgeCli(out, err, home = ConfigLocation.systemHome(), env = { System.getenv(it) })` parses the
  command line, dispatches it and returns the exit code. It writes to `Appendable`s rather than to
  the console, so a test captures both streams; `main` flushes them and calls `exitProcess` only for
  a non-zero code.
- Exit codes (`ExitCode`): `0` `OK`, `1` `FAILED` (the operation itself failed), `2` `USAGE` (bad
  command line). Usage goes to stdout for `--help` (exit 0) and to stderr for a usage error.
- Shape: `teachermovies-bridge [--config <file>] <subcommand> [options]`. `--config` is read
  wherever it appears, but `-h`, `--help` and a bare `help` only as the first token, optionally
  after a `--config <file>`: `pair --help` is a usage error today (exit 2, "opción desconocida").
  No arguments at all is a usage error too, like an unknown subcommand or option. An option takes
  its value space-separated; `--name=value` is not understood.
- `ArgsParser.parse(args)` returns `ParseResult`: `Help`, `Parsed(configSpec, command)` or
  `UsageError(message)`. `Command` is `Pair(url, pin, deviceName)`, `Unpair`, `Doctor` or
  `Logs(since, level, limit)`. A URL must be an `http`/`https` URL with a host and loses its
  trailing slash; `level` is lower-cased because that is how the TV compares it; an unusable
  `--since`/`--limit` number, an unknown level or a repeated `--config` are usage errors. No usage
  error ever quotes the value it refused, since that value may be the PIN.
- `pair --url <url> --pin <pin> [--name <name>]`: asks the TV for a bridge-scoped token and stores
  it. `--url` and `--pin` are required; `--name` defaults to this laptop's host name
  (`InetAddress.getLocalHost().hostName`, a fixed fallback name when that is unavailable or blank).
  Prints the TV URL, the scope and the config path -- never the token, never the PIN.
- `unpair`: deletes the config file. With nothing stored it is not an error (exit 0) and says so.
  The TV keeps its own copy of the token's hash until the human forgets this laptop from the TV.
- `doctor`: five checks in order, each printing `OK`/`FALLO` plus a one-line fix hint, then a count
  of the problems (exit 1 if there is any): the config file exists, its permissions are `0600`, it
  holds a pairing, the TV answers the public `GET /api/status`, and the stored token still
  authenticates against `GET /api/logs` (probed cheaply with `since=0&limit=1`). A token the TV no
  longer accepts is reported as such, without printing it. The permission check reads the file's
  mode only: the directory is created `0700` but never re-checked, and `Files.createDirectories`
  does not tighten one that already existed.
- `logs [--since <n>] [--level <debug|info|warn|error>] [--limit <n>]`: prints one page of the TV's
  ring buffer -- `YYYY-MM-DD HH:MM:SS.mmm LEVEL [module] message` per line, verbatim -- then the
  number of lines, the boot id and the `--since` cursor to continue from. Filtering is the TV's job
  (ADR-0006): `since` is a per-boot `seq` cursor, so the one printed here means nothing after a TV
  restart until a `bootId` change is handled (#272). The page size is the TV's to police too -- it
  answers 400 `bad_request` outside `1..5000` (`RingBufferLogSink.MAX_LINES`) and defaults to 500 --
  while the CLI refuses only a number below 1, so `--limit 6000` comes back as a failed operation
  (exit 1) rather than a usage error. With no pairing, or a TV with nothing to show, it says so.

## Configuration (package `com.teachermovies.bridge.config`)
- Default location `~/.config/teachermovies-bridge/config.json`, or
  `$XDG_CONFIG_HOME/teachermovies-bridge/config.json` when that variable is set and non-blank;
  `--config <file>` wins over both (`ConfigLocation.configFile`, `.configDir`).
- `ConfigLocation.expand` replaces a leading `~` or `~/` with the home directory and
  `BridgeConfigStore` keeps `path = given.toAbsolutePath().normalize()`, so every stored path is
  absolute. `~` is the JVM's `user.home`, which on Linux comes from the passwd entry rather than
  from `$HOME` in the environment (observed running the real CLI); use `XDG_CONFIG_HOME` or
  `--config` to point the program elsewhere, and tests inject `home`.
- The file is written with mode `0600` and missing parent directories are created `0700`
  (`PosixFilePermissions`), replacing a file whose permissions had been loosened. A save leaves no
  temporary file holding a second copy of the token.
- `BridgeConfig(tvUrl, token, deviceName)` -- all three nullable, so a newer bridge's config stays
  readable (`ignoreUnknownKeys`) and a null field is not written (`explicitNulls = false`);
  `isPaired` needs both a URL and a token. `toString()` redacts the token, as do `Command.Pair`
  (PIN), `PairOutcome.Paired`, `LogsCommand.PairedTv` and `TvApi`'s `PairRequest` (PIN). The one
  exception is `TvApi`'s private `PairResponse`, which keeps the generated `toString` and would
  print an issued token if it were ever interpolated; today nothing does.
- Foreseeable failures come back as sealed results, not exceptions (AGENTS.md): `ConfigLoad`
  (`Missing`, `Loaded`, `Corrupt`, `Unreadable`), `ConfigSave` (`Saved`, `Failed`), `ConfigDelete`
  (`Deleted`, `Absent`, `Failed`), each reason an exception class name -- a corrupt file is reported
  without quoting any part of it. `BridgeConfigStore.permissions()` renders the mode as four octal
  digits, or null when there is no file. The store catches `IOException` and is POSIX-only by design
  (the bridge runs as a Linux systemd user service, #278), so on a filesystem without POSIX
  permissions the `UnsupportedOperationException` is not caught and reaches the JVM; `BridgeCli` has
  no catch-all either, and relies on the subcommands' own results.

## TV API client (package `com.teachermovies.bridge.tv`)
- `TvApi(client)` with a 10 s timeout per request (connect, socket and whole request). It never
  throws: every call returns a sealed result.
- `POST /api/pair` with body `{"pin","deviceName","scope":"bridge"}`; the TV answers
  `{"token","scope"}`. The scope travels in the body, which is what `:http-server`'s `PairRoutes`
  reads (#270), not in a query string. `PairOutcome` is `Paired(token, scope)`, `WrongPin` (401
  `wrong_pin`), `TooManyAttempts` (429), `WrongScope(issued)` when the TV answers any other scope --
  including no scope at all, i.e. a TV from before #270 -- or `Failed(failure)`. A token of the
  wrong scope is never stored.
- `GET /api/status`, public (ADR-0002), decoded as `TvStatus(version, engine, freeBytes?,
  totalBytes?, torrents)` and sent with no token. The DTO is mirrored here because `:http-server` is
  an Android library this module may not depend on -- exactly how `:mobile-app` spells the contract
  out -- and unknown fields are ignored.
- `GET /api/logs` with `Authorization: Bearer <token>` and only the filters it was given, in one
  query (`since`, `level`, `limit`); the body is `:bridge-protocol`'s
  `LogsPageDto(bootId, entries)` of `LogEntryDto(seq, timeMs, level, module, message)`. The token
  goes in the header, never in the URL.
- `ApiResult<T>` is `Success(value)` or `Failure(ApiFailure)`, where `ApiFailure` is `Unauthorized`,
  `Http(status, code, message)` -- carrying the TV's own error code -- or `Network(reason)`. The
  reason is a short diagnostic ("timeout", "unexpected response body", an exception class name) that
  never includes a response body or a request's contents.

## Boundaries
- The bridge token and the PIN are never printed to stdout or stderr, and the module has no logging
  framework of its own for them to reach: `doctor` and `logs` say that a token is stored and valid,
  never what it is. The TV's ring buffer arrives already redacted by the TV (`LogRedactor`,
  ADR-0006 §3) and this module prints those lines verbatim, adding no redaction of its own -- worth
  remembering when #272 writes them to a file on the laptop.
- Known noise, no secret involved: `slf4j-api` arrives transitively with the Ktor client and no
  provider is on the runtime classpath, so every command that talks to the TV prints three
  `SLF4J(W)` lines to the real stderr. The tests cannot see them, because they capture the injected
  `Appendable`s instead of the console.
- Not here yet, each its own issue: tailing the TV log to a local file (#272), the job hub and
  `run` loop with reconnect and mDNS re-discovery (#275, #277), the Claude Code CLI transport
  (#276), the systemd user unit and `install-service` (#278), the OpenSubtitles client and its
  `.secrets/opensubtitles.env` credentials (#281).
- The `laptop-bridge` row in AGENTS.md's module table is the human's edit, not this module's.

## Tests
`bash scripts/test.sh :laptop-bridge:test` (six test classes as of #271) and
`./gradlew :laptop-bridge:ktlintCheck` for style. No test touches the network or the real home
directory: `tv.FakeTv` is a real Ktor CIO server on a loopback port standing in for the TV (the
criterion "tests run against an in-test Ktor server"), serving `/api/pair`, `/api/status` and
`/api/logs`, checking the bearer token, recording every request and failing or refusing on demand.
- `ArgsParserTest`: every flag of every subcommand, the defaults, and each usage error --
  unknown subcommand or option, a missing or valueless option, a loose argument, a repeated
  `--config`, a bad URL, level or number.
- `BridgeCliTest`: the whole CLI over `FakeTv` -- the lifecycle end to end, `pair` storing mode
  `0600` and refusing a phone-scoped or unreachable TV, every `doctor` verdict (healthy, missing,
  corrupt, rejected token, loose permissions, TV down), `logs` with and without a pairing and its
  filters in the header, `unpair` including a config it cannot read, `--help` to stdout, usage
  errors to stderr with exit 2, and `--config` pointing every subcommand at another file.
- `BridgeConfigStoreTest`: the round trip, mode `0600` for the file and `0700` for the
  directory, replacing a loosened file, no leftover temporary copy of the token, absolute normalized
  stored paths, and `Missing`/`Corrupt`/`Unreadable`/`Absent` outcomes.
- `ConfigLocationTest`: the documented default, `XDG_CONFIG_HOME` (including a tilde inside it
  and a blank value being ignored), `--config` winning, tilde expansion and normalization, and a
  relative spec made absolute against the working directory.
- `TvApiTest`: each `PairOutcome` branch, the status and log calls, what goes in the query and
  the header, and the mapping of refusals, an undecodable body and an unreachable TV onto
  `ApiFailure`.
- `BridgeConfigTest`: `isPaired` and a `toString` that redacts the token.
- No JVM test covers `main` itself (the stream flushes and `exitProcess`), the start script
  `installDist` generates, or a real TV on the LAN -- those are manual checks, as elsewhere. For
  #271 they were done by driving the installed `teachermovies-bridge` against a stub of the TV's
  three routes, which is where the observed modes, exit codes and `~` expansion above come from.
