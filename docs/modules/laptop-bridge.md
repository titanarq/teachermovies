# Module: laptop-bridge

**Gradle path:** `:laptop-bridge` (a pure-JVM `application`); its wire DTOs come from `:bridge-protocol`.

## Responsibility
- The laptop-side half of ADR-0005: a Kotlin/JVM command-line program, `teachermovies-bridge`, that
  runs on the machine holding the human's Claude Code subscription -- with the laptop on but no
  desktop login, once #278 installs it as a systemd user service.
- #271 is the skeleton: pair with the TV over its PIN, keep the resulting bridge-scoped token safely
  on disk, report its own health with `doctor`, and print one page of the TV's log ring buffer with
  `logs`. #281 adds the OpenSubtitles client (package `opensubtitles`), a library nothing calls
  yet besides `doctor`'s credentials check: #282's fetch loop will drive it (see "Boundaries").
  #277 adds `run`, the long-lived loop that holds the TV's job stream open, answers its jobs through
  a handler registry and reconnects (backoff, then an mDNS browse) for as long as the process is up.
  #272 adds the TV log mirror (package `logs`), which `run` keeps going alongside the job loop.
- Pure JVM, never an Android library, so it depends only on `:bridge-protocol` and never on
  `:http-server` or any other Android module (ADR-0005 §1, AGENTS.md). The Ktor *server* artifacts
  are test-only here, for the in-test fake TV.
- This doc covers `:bridge-protocol` too, which has no label or doc of its own (AGENTS.md): the
  pure-JVM DTOs shared with `:http-server` -- `LogsPageDto(bootId, entries)` of
  `LogEntryDto(seq, timeMs, level, module, message)`, and `LogStreamBootDto(bootId)`, the first
  frame of the SSE stream that the log mirror (#272) reads.
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
  `Logs(since, level, limit)` or `Run`. A URL must be an `http`/`https` URL with a host and loses its
  trailing slash; `level` is lower-cased because that is how the TV compares it; an unusable
  `--since`/`--limit` number, an unknown level or a repeated `--config` are usage errors. No usage
  error ever quotes the value it refused, since that value may be the PIN.
- `pair --url <url> --pin <pin> [--name <name>]`: asks the TV for a bridge-scoped token and stores
  it. `--url` and `--pin` are required; `--name` defaults to this laptop's host name
  (`InetAddress.getLocalHost().hostName`, a fixed fallback name when that is unavailable or blank).
  Prints the TV URL, the scope and the config path -- never the token, never the PIN.
- `unpair`: deletes the config file. With nothing stored it is not an error (exit 0) and says so.
  The TV keeps its own copy of the token's hash until the human forgets this laptop from the TV.
- `doctor`: seven checks in order, each printing `OK`/`FALLO` plus a one-line fix hint, then a count
  of the problems (exit 1 if there is any): the config file exists, its permissions are `0600`, it
  holds a pairing, the TV answers the public `GET /api/status`, the stored token still
  authenticates against `GET /api/logs` (probed cheaply with `since=0&limit=1`), and -- always,
  even when the config is missing -- the OpenSubtitles credentials file exists and is `0600` (#281).
  That last check never opens the file: it reports the path and the mode, never a line of it, not
  even whether it is complete. A token the TV no
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

- `run` (#277): no options. Without a usable pairing it fails at once (exit 1, the same hints as
  `logs`); otherwise it hands over to `RunLoop` (see "Run loop") and never returns until the process
  is stopped -- except when the TV refuses the token (401), which is a new pairing to do rather than
  a connection to retry: exit 1, "La TV ya no acepta el token de este portátil". `BridgeCli` takes the
  `JobHandlerRegistry` (default `JobHandlerRegistry.default()`, empty) and the `TvDiscovery`
  (default `JmDnsDiscovery()`) as constructor parameters, which is where #286/#291 add their handlers.
  Next to the job loop, in the same coroutine scope, it runs `TvLogMirror` (see "TV log mirror")
  into `TvLogFiles.defaultDir(home, env)`; the mirror is cancelled when the job loop ends.

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
- `jobStream(baseUrl, token, onOpen, onEvent)` -> `GET /api/bridge/jobs` (#275) with the bearer
  header, no whole-request timeout and a 45 s socket timeout (three missed 15 s pings: a TV gone
  without closing the socket). `onOpen` runs on the 2xx, then every complete SSE frame goes to
  `onEvent` as `SseEvent(event, data)` in order, on the reading coroutine. `SseParser` reads the
  subset the TV writes: `event:`/`data:` (several `data:` lines joined by `\n`), `:` comments (the
  ping), a blank line ending the frame; `id:`/`retry:` are ignored. It never throws; the result is
  `JobStreamEnd`: `Closed` (accepted, then closed by the TV), `Broken(reason)` (accepted, then the
  connection failed or went silent) or `NotOpened(ApiFailure)` (unreachable, 401 or another status).
- `postJobResult(baseUrl, token, jobId, result)` -> `POST /api/bridge/jobs/{id}/result` with a
  `BridgeJobResultDto` body: `Success(Unit)` on 204, `Unauthorized` on 401, `Http(status, code, …)`
  otherwise (404 `unknown_job`, 409 `job_closed`, 400).
- `ApiResult<T>` is `Success(value)` or `Failure(ApiFailure)`, where `ApiFailure` is `Unauthorized`,
  `Http(status, code, message)` -- carrying the TV's own error code -- or `Network(reason)`. The
  reason is a short diagnostic ("timeout", "unexpected response body", an exception class name) that
  never includes a response body or a request's contents.

## OpenSubtitles client (package `com.teachermovies.bridge.opensubtitles`, #281)
- ADR-0005 §5: the bridge is the only holder of the OpenSubtitles credentials. `CredentialsFile`
  reads them from `<config dir>/.secrets/opensubtitles.env` (the bridge's config directory, e.g.
  `~/.config/teachermovies-bridge/.secrets/opensubtitles.env`, independent of `--config`), or from
  the file `$TEACHERMOVIES_OPENSUBTITLES_ENV` names (`~` expanded). Format: `KEY=VALUE` lines, `#`
  comments, optional `export ` and matching quotes. Required keys `OPENSUBTITLES_API_KEY`,
  `OPENSUBTITLES_USERNAME`, `OPENSUBTITLES_PASSWORD`; optional `OPENSUBTITLES_USER_AGENT`.
  `load()` is `Missing`, `Loaded`, `Incomplete(missingKeys)` (key names only) or
  `Unreadable(reason)`; `OpenSubtitlesCredentials.toString()` redacts all three secrets.
- `OpenSubtitlesApi(httpClient, credentials, baseUrl = https://api.opensubtitles.com/api/v1)`, 20 s
  timeout per request, never throws (`OsResult` / `DownloadOutcome`; `OsFailure` is `LoginRefused`
  (401 at `/login`), `Unauthorized` (401/403 with a fresh JWT: the API key), `RateLimited` (429),
  `Http(status)` or `Network(reason)` -- no body, credential or JWT in any of them).
  - Every request carries `Api-Key` and `User-Agent` (default `teachermovies-bridge v1.0`).
  - `POST /login` for a JWT kept in memory only, the first time `/download` or `/infos/user` needs
    one; on a 401 it logs in once more and repeats the request once. Concurrent 401s log in once
    (a mutex; a caller that finds the JWT already replaced reuses it). Searches send the JWT when
    there is one but never log in for it. The `base_url` the login answers is ignored.
  - `search(SearchQuery(languages, moviehash?, imdbId?, title?, year?))` -> `GET /subtitles` with
    only the fields given, alphabetical and lower-cased (OpenSubtitles redirects otherwise); the
    IMDb id is sent as digits (`tt0133093` -> `133093`). Results without a file are dropped; each
    becomes a `SubtitleCandidate` (first file id, language code, HI, machine/AI-translated,
    foreign-parts-only, `moviehash_match`, trusted, download count, release).
  - `download(fileId)` -> `POST /download {"file_id","sub_format":"srt"}`: a temporary `Link`, or
    `QuotaExhausted` on 406. `fetch(link)` gets the file with only a `User-Agent` -- the link is on
    another host, so neither the API key nor the JWT goes there.
  - Quota: `QuotaTracker` keeps the latest `Quota(remaining, allowed, resetsAt)`, each report
    replacing only the fields it carries -- `allowed` from `/login`, `remaining` and
    `reset_time_utc` from every `/download` (406 included), both from `refreshQuota()`
    (`GET /infos/user`). While `remaining <= 0` and `resetsAt` is still ahead, `download` refuses
    locally without spending a request.
- `SubtitleRanker`: excludes machine-translated, AI-translated and foreign-parts-only results, then
  orders by moviehash match, not hearing-impaired, trusted uploader, download count (stable).
- `SubtitleFinder(api).find(SubtitleRequest(language, moviehash?, imdbId?, title?, year?))`: searches
  by moviehash, then IMDb id, then title + year, skipping those the request cannot fill and stopping
  at the first that yields an acceptable subtitle; downloads only the chosen file (one quota unit at
  most). English asks for `en`. Spanish asks for `es,sp` (Castilian) and `ea` (Latin-American)
  together, but a Latin-American result is taken only after *every* search found no Castilian one;
  then the best `ea` result across all searches wins, `SubtitleVariant.LATINO`, label `"latino"`.
  Returns `Found(FetchedSubtitle, quota)`, `NotFound`, `QuotaExhausted` or `Failed(failure)`, each
  with the quota as last known.
- `SubtitleText.normalise(bytes)`: UTF-8/UTF-16LE/UTF-16BE by byte-order mark (dropped), else strict
  UTF-8, else Windows-1252. `FetchedSubtitle.text` is that string; `utf8Bytes()` the normalised file.

## Run loop (package `com.teachermovies.bridge.run`, #277)
- `interface JobHandler { val kind: String; suspend fun handle(job: BridgeJobDto): BridgeJobResultDto }`
  is what the translate and explain handlers of #286/#291 implement. `JobHandlerRegistry(handlers)`
  keys them by `kind` (two handlers of one kind are refused) and `dispatch(job: JsonObject)` returns
  the result to post: the handler's own, or `Failed` with code `unsupported_kind` (no handler for the
  `kind` -- every job today, since the shipped registry is empty; a `kind` this bridge's
  `:bridge-protocol` does not even know is answered the same way, because the loop reads `kind` and
  `id` off the raw JSON before decoding), `bad_job` (a handled kind whose fields do not decode) or
  `handler_error` (the handler threw; the message is the exception class name). So no job is ever
  left for the TV to time out.
- `RunLoop(api, store, registry, log, discovery, backoff = Backoff(), discoveryAfter = 2, sleep)`,
  `run(pairedConfig): End`:
  - Holds one `jobStream` open. Each `job` frame is logged and run in a coroutine of its own (a slow
    job never holds up the next frame, nor a `cancel` behind it), and its result is posted with
    `postJobResult`. A frame with no string `id` (or not JSON) cannot be answered: logged, ignored.
  - A `cancel` frame cancels that job's coroutine; nothing is posted for it. When the stream ends,
    every job still running is cancelled too: the TV has already resolved them `Disconnected`, so an
    answer would only earn a 409. A refused post (401/404/409/…) is logged and the loop goes on.
  - Reconnect: after any end of the stream (closed, broken, a non-401 status such as a 404 from a
    TV older than #275) it waits `Backoff`'s next delay -- 1 s doubling up to 60 s, no jitter (one
    bridge, one TV) -- and opens it again; the delay is back to 1 s as soon as a stream is accepted.
  - Discovery fallback: from the `discoveryAfter`-th consecutive attempt in which the saved URL did
    not answer at all (`NotOpened(Network)`), each attempt first asks `TvDiscovery` for candidates.
    Each one (other than the current URL) must answer the public `GET /api/status` before it gets
    the token -- so the token only ever reaches something that answers like a teachermovies TV --
    and then accept the token on `GET /api/logs?since=0&limit=1` (a TV that refuses it is somebody
    else's, skipped). The first that passes becomes the URL: saved with `BridgeConfigStore.save`
    (a failed save is logged and the new URL still used for this process), backoff reset, and the
    stream reopened at once.
  - `End.Unauthorized` is the only way out: the stream was refused 401.
- `JmDnsDiscovery(browseTime = 5 s)` is the production `TvDiscovery` (JmDNS, ADR-0004): one JmDNS
  instance per up, non-loopback, multicast-capable IPv4 interface address (not `JmDNS.create()`,
  which binds to `getLocalHost()`, `127.0.1.1` on Debian-family systems), a `list("_http._tcp.local.")`
  on each, instances whose name starts with `Movie Assistant` (`:discovery`'s `ServiceNames`, which
  mDNS may suffix), `http://<ipv4>:<port>` per address. Every instance is closed before it returns;
  an `IOException` is an empty list. The TV announces only the service, never a
  `movieassistant.local` host name, so the IP comes from the service's address records.
- `RunLog(out, file)`: one `yyyy-MM-dd HH:mm:ss.SSS mensaje` line per event (start and handled
  kinds, connected, closed/broken/refused, each retry delay, discovery and its verdicts, each job:
  kind, the first 8 chars of its id, `hecho`/`error <code>`, elapsed ms and whether the answer was
  delivered) to stdout -- the journal once #278 runs it -- and appended to `bridge.log` next to the
  config file, created `0600`. A job's text or result never reaches it, nor the token. When the
  file cannot be written it says so once on stdout and carries on without it; there is no rotation.

## TV log mirror (package `com.teachermovies.bridge.logs`, #272)
- ADR-0006 §5: while `run` is up, the bridge copies the TV's log ring buffer to dated files on the
  laptop, so a TV's history survives the TV process (whose buffer is in memory only).
- Directory: `$TEACHERMOVIES_TV_LOGS_DIR` when set and non-blank (a leading `~` is the home
  directory), otherwise `.cache/tv-logs` against the process's working directory
  (`TvLogFiles.defaultDir`); always absolute. Created `0700`; every file `0600`.
- Files: `<yyyy-mm-dd>.log`, one line per entry, `<ISO> <L> <module> <msg>` -- the entry's own
  `timeMs` as `yyyy-MM-dd'T'HH:mm:ss.SSSXXX` in the laptop's time zone, the level's initial
  (`D`/`I`/`W`/`E`), the module (whitespace -> `_`), the message with its line breaks written as a
  literal `\n`. An entry goes to the file of the day its own time falls on, so the files roll daily
  even for backfilled lines. Starting a day's file deletes the dated files more than 14 days older
  than today (`TvLogFiles.KEEP_DAYS`); other files in the directory are left alone.
- Cursor: `cursor.json` in the same directory, `MirrorCursor(bootId, seq)` of the last entry
  written, saved after every line (a `0600` temporary file moved over the old one atomically). The
  stream is opened with `since = <seq>` (no `since` on the first run: the whole buffer), and an
  entry at or below the cursor is dropped, so a restarted bridge neither repeats nor skips a line
  the TV still holds. A cursor that is missing or unreadable means "copy the whole buffer". A crash
  between a line and its cursor save can repeat that one line.
- TV restart: the stream's first frame `boot` names the TV process. A `bootId` other than the
  cursor's writes a gap line -- `<ISO now> - bridge ===== la TV ha vuelto a arrancar (arranque
  <old8> -> <new8>): lo que no se copió antes del reinicio se ha perdido =====`, level column `-`
  so a filter on `E`/`W` never hides it -- to today's file, and the cursor starts over at
  `(newBoot, 0)`. A stream that had been opened with a positive `since` is missing the new boot's
  first lines, so it is dropped and reopened at once from `since = 0`.
- Reconnect: any other end of the stream (closed, broken, a non-401 status such as a 404 from a TV
  older than #269) waits `Backoff(max = 30 s)` -- 1 s doubling up to 30 s, reset once a stream is
  accepted -- and asks again. The TV URL is re-read from the config before every attempt, so the
  mirror follows the job loop's mDNS re-discovery. A failed write (disk full, unwritable
  directory) stops the stream without moving the cursor, so the retry asks for that line again. A
  401 ends the mirror; the job loop, refused the same way, is what ends `run`.
- Its events (copying into a directory, the restart, each problem) go to the same `RunLog` as the
  job loop, each different problem once until a stream opens again. The copied lines themselves
  are not echoed there.
- `TvApi.logStream(baseUrl, token, since, onOpen, onEvent)` -> `GET /api/logs/stream?since=`
  with the bearer header only (the TV refuses `?token=` there): the same SSE reader and socket
  timeout as `jobStream`, except that `onEvent` returning false stops the stream (`Closed`).

## Boundaries
- The bridge token and the PIN are never printed to stdout or stderr, and the module has no logging
  framework of its own for them to reach: `doctor` and `logs` say that a token is stored and valid,
  never what it is. The TV's ring buffer arrives already redacted by the TV (`LogRedactor`,
  ADR-0006 §3) and this module prints those lines verbatim, adding no redaction of its own -- also
  in the mirror's files (#272), which is one reason they are `0600` in a `0700` directory.
- Known noise, no secret involved: `slf4j-api` arrives transitively with the Ktor client and no
  provider is on the runtime classpath, so every command that talks to the TV prints three
  `SLF4J(W)` lines to the real stderr. The tests cannot see them, because they capture the injected
  `Appendable`s instead of the console.
- Not here yet, each its own issue: the job handlers
  themselves (#286 translate, #291 explain, on #276's transport), the Claude Code CLI transport
  (#276), the systemd user unit and `install-service` (#278), the loop that decides when to search
  subtitles and uploads them to the TV (#282, through #280's routes), and the moviehash itself (#279:
  the TV computes it, this module only sends it).
- The OpenSubtitles credentials follow the token's rule: no command prints them, `doctor` reports
  the file's existence and mode only, and nothing in the `opensubtitles` package puts one -- or the
  JWT -- in a result, a failure or a `toString`.
- The `laptop-bridge` row in AGENTS.md's module table is the human's edit, not this module's.

## Tests
`bash scripts/test.sh :laptop-bridge:test` (nineteen test classes as of #272) and
`./gradlew :laptop-bridge:ktlintCheck` for style. No test touches the network or the real home
directory: `tv.FakeTv` is a real Ktor CIO server on a loopback port standing in for the TV (the
criterion "tests run against an in-test Ktor server"), serving `/api/pair`, `/api/status`,
`/api/logs` and (#277) the job stream `GET /api/bridge/jobs` -- a `: ping`, then whatever frames a
test queues with `sendFrame`, until `closeStreams()` -- and `POST /api/bridge/jobs/{id}/result`
(204, or `resultAnswer`), checking the bearer token, recording every request and failing or refusing on demand.
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
- `opensubtitles.FakeOpenSubtitles` is a real Ktor CIO server on a loopback port standing in for
  OpenSubtitles (the criterion "tests run against a fake OpenSubtitles HTTP server"): `/login`,
  `/subtitles`, `/download`, `/infos/user` and the download links, checking the API key and the
  JWT, keeping a daily quota, expiring the JWT on demand and recording every request's headers.
- `OpenSubtitlesApiTest`: headers on every call, login on first need and the re-login after a 401,
  wrong password vs wrong key, query parameters, attribute mapping, quota tracking, 406 and the
  local refusal until the reset, no credential sent to the download link, failure mapping.
- `SubtitleFinderTest`: search order and skipping, ranking, Castilian from a later search beating a
  Latin-American hash match, the "latino" last resort, NotFound/QuotaExhausted/Failed, the quota
  reported, and a Windows-1252 file arriving as UTF-8.
- `SubtitleRankerTest`, `SubtitleTextTest`, `CredentialsFileTest` (parsing, `Incomplete`, default
  path and the override variable, permissions); `BridgeCliTest` covers `doctor`'s credentials check
  (missing, `0644`, unreadable-without-config, moved by the variable, contents never printed).
- `RunLoopTest` (#277, over `FakeTv` and a fake `TvDiscovery`): an unhandled kind answered at once
  with `unsupported_kind`, a handler's result posted and logged (without its text, also in
  `bridge.log`), a slow job not holding up a fast one and `cancel` stopping it with no answer, a
  closed stream reopened after a backoff that resets, a TV that stays down retried 10/20/40/40 ms,
  401 ending the loop, a 503 retried, the discovery fallback adopting and saving the new URL only
  after two failed attempts, a non-TV candidate never seeing the token and a TV refusing it skipped,
  a refused result logged, frames without an id ignored. `JobHandlerRegistryTest`, `BackoffTest`,
  `RunLogTest` (format, `0600`, unwritable file), `SseParserTest`, `JmDnsDiscoveryTest` (the name
  filter and URL shape only); `ArgsParserTest`/`BridgeCliTest` cover `run`'s parsing, the missing
  pairing and the 401 exit.
- `TvLogMirrorTest` (#272, over a scripted `LogStreamSource` and a fixed UTC clock): the line
  format and daily roll by the entry's own time, `0600`/`0700`, the backfill of a restarted bridge
  from the stored `seq` without duplicates, the whole buffer on a first run, the gap marker on a
  `bootId` change with the reopen from 0 (and no reopen when already reading from 0), backoff
  1/2/4/8/16/30/30 s and its reset, 401 ending the mirror, a failed write keeping the cursor,
  unreadable frames skipped, the URL re-read each attempt, the 14-day pruning and the directory
  default and variable. `TvLogMirrorOverTvTest` runs it over the real `TvApi.logStream` against
  `FakeTv` (whose `/api/logs/stream` sends `boot`, the `logsPage` entries after `since`, then what
  `sendLog` queues): backlog, a live line, and a TV restart. `TvApiTest` covers `logStream`'s query,
  header, frames, stop and 401.
- No JVM test covers the JmDNS browse itself (it needs a LAN with multicast and a TV announcing on
  it), nor `run` against the real `:http-server` hub: `FakeTv` spells the #275 contract out from
  `docs/modules/http-server.md`.
- No JVM test covers `main` itself (the stream flushes and `exitProcess`), the start script
  `installDist` generates, or a real TV on the LAN -- those are manual checks, as elsewhere. For
  #271 they were done by driving the installed `teachermovies-bridge` against a stub of the TV's
  three routes, which is where the observed modes, exit codes and `~` expansion above come from.
