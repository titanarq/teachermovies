# Module: laptop-bridge

**Gradle path:** `:laptop-bridge` (a pure-JVM `application`); its wire DTOs come from `:bridge-protocol`.

## Responsibility
- The laptop-side half of ADR-0005: a Kotlin/JVM command-line program, `teachermovies-bridge`, that
  runs on the machine holding the human's Claude Code subscription -- with the laptop on and no
  desktop login, as the systemd `--user` service #278 installs with `loginctl enable-linger`.
- #271 is the skeleton: pair with the TV over its PIN, keep the resulting bridge-scoped token safely
  on disk, report its own health with `doctor`, and print one page of the TV's log ring buffer with
  `logs`. #281 adds the OpenSubtitles client (package `opensubtitles`), a library nothing calls
  yet besides `doctor`'s credentials check: #282's fetch loop will drive it (see "Boundaries").
  #277 adds `run`, the long-lived loop that holds the TV's job stream open, answers its jobs through
  a handler registry and reconnects (backoff, then an mDNS browse) for as long as the process is up.
  #272 adds the TV log mirror (package `logs`), which `run` keeps going alongside the job loop.
  #282 adds the subtitle fetch loop (package `subtitles`), which `run` keeps going too and which
  drives the OpenSubtitles client against the TV's subtitle routes of #280. #278 adds the systemd
  user unit (package `service`), the `install-service` subcommand that writes it, and the install
  runbook `docs/runbooks/laptop-bridge.md`. #291 adds the explain handler (package `explain`) and
  #286 the translate handler (package `translate`): the two job handlers `run` answers with, each
  over a Claude conversation of its own.
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
  `UsageError(message)`. `Command` is `Pair(url, pin, deviceName)`, `Unpair`, `Doctor`,
  `Logs(since, level, limit)`, `Run` or `InstallService(exec)`. A URL must be an `http`/`https` URL
  with a host and loses its trailing slash; `level` is lower-cased because that is how the TV
  compares it; an unusable `--since`/`--limit` number, an unknown level or a repeated `--config` are
  usage errors. No usage error ever quotes the value it refused, since that value may be the PIN.
- `pair --url <url> --pin <pin> [--name <name>]`: asks the TV for a bridge-scoped token and stores
  it. `--url` and `--pin` are required; `--name` defaults to this laptop's host name
  (`InetAddress.getLocalHost().hostName`, a fixed fallback name when that is unavailable or blank).
  Prints the TV URL, the scope and the config path -- never the token, never the PIN.
- `unpair`: deletes the config file. With nothing stored it is not an error (exit 0) and says so.
  The TV keeps its own copy of the token's hash until the human forgets this laptop from the TV.
- `doctor`: checks in order, each printing `OK`/`FALLO` plus a one-line fix hint, then a count
  of the problems (exit 1 if there is any): the config file exists, its permissions are `0600`, it
  holds a pairing, the TV answers the public `GET /api/status`, the stored token still
  authenticates against `GET /api/logs` (probed cheaply with `since=0&limit=1`), and -- always,
  even when the config is missing -- the OpenSubtitles credentials file exists and is `0600` (#281).
  That last check never opens the file: it reports the path and the mode, never a line of it, not
  even whether it is complete. A token the TV no
  longer accepts is reported as such, without printing it. The permission check reads the file's
  mode only: the directory is created `0700` but never re-checked, and `Files.createDirectories`
  does not tighten one that already existed. Then, also always (#276): the Claude Code binary
  (`ClaudeBinary.locate`: `CLAUDE_BIN`, else `claude` on `PATH`, else `~/.local/bin/claude`), when
  there is one `claude auth status --json` -- a login check, never a model call -- reporting the
  auth method and plan or "no hay sesión iniciada" with `claude auth login` as the hint, and whether
  `claude.json` is usable (see "Claude Code transport"). Last, as `INFO` lines that are not checks,
  the stderr tail each job kind's last dead Claude process left behind.
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
  (default `JmDnsDiscovery()`) as constructor parameters, which is where the handlers of #286/#291
  join it (`cli.JobHandlers`, see "Translate handler" and "Explain handler").
  Next to the job loop, in the same coroutine scope, it runs `TvLogMirror` (see "TV log mirror")
  into `TvLogFiles.defaultDir(home, env)`; the mirror is cancelled when the job loop ends. Also in
  that scope, when the OpenSubtitles credentials file (`CredentialsFile.defaultPath`) loads, it runs
  `SubtitleFetchLoop` (see "Subtitle fetch loop") over one `OpenSubtitlesApi` for the whole process
  (so the JWT and the quota tracker survive between passes), sharing the CIO client with `TvApi`.
  A missing, incomplete or unreadable file is said once in the log (path and key names only) and
  `run` goes on without automatic subtitles; the file is read once, so adding it takes a restart.
- `install-service [--exec <ruta>]` (#278): writes the systemd `--user` unit that keeps `run` up (see
  "Systemd user unit") and prints the three commands that put it to work -- `daemon-reload`,
  `enable --now` and `loginctl enable-linger` -- plus `doctor` and `journalctl` to check it. It
  writes a unit and nothing else: it never enables, reloads or starts anything, so a mistake in
  those is the human's to run and to see. `--exec` names the start script for `ExecStart` (`~`
  expanded, a relative path resolved against the working directory); with no `--exec` the script of
  the distribution this very process was launched from is derived, and a program run out of a build
  directory derives none and is told to pass one (exit 1, no unit written). The report carries the
  unit's path, its `ExecStart`, the `CLAUDE_BIN` it resolved or why there is none, the TV log
  directory the unit points at, and the config file the token stays in -- never the token, and it
  says so out loud. With no stored pairing it also says that `run` would end at once, so `pair`
  comes first.

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
- Subtitle routes (#280), all with the bearer header: `subtitleNeeds(baseUrl, token)` ->
  `GET /api/bridge/subtitle-needs`, a `List<SubtitleNeedDto>`; `postSubtitleStatus(baseUrl, token,
  SubtitleStatusDto)` -> `POST /api/bridge/subtitle-status`, `Success(Unit)` on 204;
  `uploadSubtitle(baseUrl, token, torrentId, language, variant?, bytes)` -> multipart
  `POST /api/bridge/subtitles` (fields `torrentId`, `language`, `variant` only when not null, and
  `file` as `application/x-subrip` with the placeholder name `subtitle.srt`, which the TV never
  reads), `SubtitleUploadedDto(path)` on 201. Refusals map onto `ApiFailure` as everywhere else.
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
  `kind` -- both of them when the laptop has no usable Claude CLI, since neither handler works
  without one; a `kind` this bridge's `:bridge-protocol` does not even know is answered the same way,
  because the loop reads `kind` and
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

## Claude Code transport (package `com.teachermovies.bridge.claude`, #276)
- ADR-0005 §3: Claude runs on the human's subscription through the Claude Code CLI on this laptop,
  never an API key. `interface ClaudeCli { kind; warmUp(); ask(prompt): ClaudeOutcome; close() }` is
  what the translate and explain handlers of #286/#291 hold, one per job kind with that kind's own
  system prompt; `ClaudeCliTransport` is the real one and `FakeClaudeCli` (main source set,
  ADR-0003: a queue of outcomes, the prompts it received) the one their tests use. `run` builds
  one per handled kind through `cli.JobHandlers` (#286: translate, #291: explain) -- see "Translate
  handler" and "Explain handler".
- `ClaudeCliTransport(kind, systemPrompt, executable, settings, dirs, cap, log, baseEnvironment)`
  keeps one long-lived process per kind, started with `ClaudeCommandLine.argv`: `claude -p
  --input-format stream-json --output-format stream-json --verbose --model <m> --effort <e>
  --system-prompt-file <abs> --tools "" --strict-mcp-config --setting-sources ""
  --disable-slash-commands --no-session-persistence`. `executable` and the prompt file must be
  absolute; the working directory is a fresh empty `0700` directory of its own under
  `ClaudeDirs.workDirs`, and the prompt file (`0600`) lives outside it under `ClaudeDirs.prompts`;
  both are deleted when the process ends. `ClaudeDirs.default` is `teachermovies-bridge/claude`
  under `$XDG_CACHE_HOME` or `~/.cache`. The child's environment is `baseEnvironment` minus
  `ANTHROPIC_API_KEY` and `ANTHROPIC_AUTH_TOKEN`, replaced rather than inherited.
- One stdin line per turn (`ClaudeEvents.userTurn`: a user message with one text block), stdout
  read line by line until the turn's `result` (`ClaudeEvents.parse`: `system/init`, `assistant`
  text blocks, `result`; everything else ignored), stderr drained all along into a 40-line
  `StderrTail`. Turns are serialised: one job at a time per kind.
- Warm-up: every process's first turn is `WARM_UP_PROMPT`, whose answer is dropped, so a job never
  pays for a cold start. The `init` of that turn must report `apiKeySource` `none` -- and any later
  `init` too; any other value, or no `init` at all on the first turn, kills the process and makes
  the transport answer `Refused(source)` to every job until the bridge restarts, without starting
  another process (the source name is logged; it is a name such as `ANTHROPIC_API_KEY`, not a key).
- Spare and rotation: as soon as a process is ready, a replacement is started and warmed up in the
  background. The current process is replaced -- the next job goes to the spare, already waiting --
  after `rotateAfterTurns` jobs (closed gracefully: stdin closed, then killed after 2 s), when a turn
  outlives `turnTimeoutSeconds` (killed at once: `TimedOut`), and when it dies (`Failed` with the
  exit code and its last stderr line; the whole tail is written `0600` to
  `ClaudeDirs.stderrTail(kind)`, which `doctor` shows). A failed turn the CLI answered cleanly keeps
  its process: a `429` (`api_error_status`, number or string, or a `429` in the text when there is
  no status) is `RateLimited`; any other error result is `Failed`.
- Cost: `total_cost_usd` is the process's running total, so each turn logs its delta to the
  `RunLog` -- `claude <kind>: trabajo en <ms> ms, coste 0.0125 USD billing=subscription` (warm-ups
  too, as `proceso <pid> listo, calentamiento ...`) -- and `Answered` carries it. Under the
  subscription it is Claude Code's list-price equivalent, not money charged. No prompt or reply text
  ever reaches the log.
- `DailyCap(limit)`, one instance shared by every transport of a bridge: jobs per calendar day in
  the laptop's time zone; checked before a job reaches Claude (`DailyCapReached`), warm-ups not
  counted. The count is in memory, so a restarted bridge starts the day over.
- `ClaudeOutcome` is `Answered(text, costUsd, elapsedMs)`, `RateLimited`, `TimedOut`,
  `DailyCapReached(limit)`, `Refused(apiKeySource)` or `Failed(reason)`; `failureResult()` gives
  the `BridgeJobResultDto.Failed` a handler posts for a non-answer -- codes `rate_limited`,
  `timeout`, `daily_cap`, `api_key_billing`, `claude_error`. Extracting and validating JSON from
  `Answered.text` (and the one re-ask) is the handler's job.
- Settings: `claude.json` next to the config file (`ClaudeSettingsFile.besideConfig`), every field
  optional: `model` (`sonnet`), `effort` (`low`), `dailyJobCap` (300), `turnTimeoutSeconds` (60),
  `rotateAfterTurns` (25). Unknown keys are ignored; a model or effort that is blank, holds
  whitespace or starts with `-` (it would be read as a flag), or a number below 1, is `Corrupt`.
- `ClaudeAuth.status(executable)` runs `claude auth status --json` with the same stripped
  environment and a 10 s timeout, and reads `loggedIn`/`authMethod`/`subscriptionType` from its
  JSON whatever the exit code: `LoggedIn`, `LoggedOut` or `Failed(reason, stderr)`.

## Translate handler (package `com.teachermovies.bridge.translate`, #286)
- ADR-0005 §6: `TranslateHandler(cli)` is the `translate` `JobHandler` -- the fallback behind LEFT
  when no aligned Spanish subtitle is good enough (#288 asks, #287 caches). It takes a
  `TranslateJobDto` (the captured English `line` and nothing else) and answers `Done(text)` whose
  `text` is the Spanish line **bare**: no JSON document around it, and so no `promptVersion` either,
  unlike an explanation. That is what the merged TV side reads -- `HubBridgeTranslateGateway` hands
  the hub's `text` straight to `BridgeTranslationProvider`, which shows it verbatim (#287) -- and the
  translation cache is keyed by the English line alone (#274), so there is no field for a version to
  travel in and nothing that would read one.
- Prompt: `TranslatePrompt.SYSTEM_PROMPT` is fixed Spanish text -- Castilian, the character's own
  register, a similar length, nothing added or explained, proper names untouched, a non-English or
  untranslatable line returned as it came -- and holds no job's text. The line goes only in the user
  turn, as the one-field JSON document `{"linea": …}` the prompt tells Claude to read as data and
  never as instructions.
- Reply: Claude answers the `{"translation": …}` object. Validation (`TranslateReply`), after
  `claude.JsonReply.extractObject` (the first parsable JSON object in the reply, fences and prose
  around it ignored): `translation` required, a JSON string, trimmed and non-blank, at most
  `TranslateReply.MAX_CHARS` code points -- `2 × MAX_LINE_CHARS`, this handler's own bound (the issue
  fixed none), twice what the protocol allows the English line because Spanish runs longer and the
  answer is one subtitle line, not a paragraph. Unknown keys are dropped.
- Size: the encoded `BridgeJobResultDto.Done` -- envelope and escapes included, which is what the
  TV's `POST /api/bridge/jobs/{id}/result` measures and refuses over `MAX_PAYLOAD_BYTES` with a 413
  `too_large` -- is checked before posting, so a reply inside the char bound but over the body limit
  is re-asked instead of lost on the way back.
- One re-ask: a reply that breaks the schema gets a second turn naming the problem in the handler's
  own words (never Claude's text) followed by the same data document, so it stands on its own even if
  another job's turn came between. A second bad reply is `Failed("invalid_reply")` -- the code the
  explain handler uses too -- and there is no third ask. A non-answer (`rate_limited`, `timeout`,
  `daily_cap`, `api_key_billing`, `claude_error`) is posted as the transport's own `failureResult()`
  and never re-asked, which is how the TV tells "Claude would not answer" from "Claude answered
  rubbish".
- Process: the handler needs a `ClaudeCli` of kind `translate` (anything else is refused at
  construction), so it never shares a process -- or a system prompt -- with explain (#291).
  `JobHandlers.claude` builds both conversations from the same CLI, the same `claude.json` settings
  and the one `DailyCap`, so the day's cap covers translate and explain together.

## Explain handler (package `com.teachermovies.bridge.explain`, #291)
- ADR-0005 §7: `ExplainHandler(cli)` is the `explain` `JobHandler`. It takes an `ExplainJobDto` (title,
  captured line, EN lines before/after, aligned ES line; which lines is the TV's choice, #292) and
  answers `Done(text)` whose `text` is the JSON of `:bridge-protocol`'s `ExplanationDto`:
  `{"promptVersion":"explain-v1","resumen":…,"puntos":[{"expresion":…,"explicacion":…}],"diferencia_subtitulo":…|null}`.
  `promptVersion` (`ExplainPrompt.VERSION`) is on every explanation returned, so the TV's cache,
  keyed by it (#274), drops what an older prompt wrote; bump it whenever the prompt or the schema
  changes. Failures carry no version: they are never stored (ADR-0005 §8).
- Prompt: `ExplainPrompt.SYSTEM_PROMPT` is fixed Spanish text for a Castilian learner of English and
  holds no job's text. The job's context goes only in the user turn, as a JSON document
  (`titulo`, `antes`, `linea`, `despues`, `subtitulo_es`) the prompt tells Claude to read as data and
  never as instructions -- a line trying to escape it stays one JSON string.
- Validation (`ExplainReply`), after `claude.JsonReply.extractObject` (the first parsable JSON object
  in the reply, fences and prose around it ignored): `resumen` required, ≤160 code points; `puntos`
  a list of ≤3 objects with non-empty `expresion` (≤80) and `explicacion` (≤140); `diferencia_subtitulo`
  a string ≤160 or null. Strings are trimmed; unknown keys dropped; a missing `puntos` is empty and a
  missing or blank difference null; a difference is forced to null when the job had no ES line.
  The 80-char bound on `expresion` is this handler's own (the issue fixed none); it keeps the whole
  document well under `MAX_PAYLOAD_BYTES`, which is also checked before posting.
- One re-ask: a reply that breaks the schema gets a second turn naming the problem in the handler's
  own words (never Claude's text) followed by the same data document, so it stands on its own even if
  another turn came between. A second bad reply is `Failed("invalid_reply")`; there is no third ask.
  A non-answer (`rate_limited`, `timeout`, `daily_cap`, `api_key_billing`, `claude_error`) is posted as
  the transport's own `failureResult()` and never re-asked.
- Process: the handler needs a `ClaudeCli` of kind `explain` (anything else is refused at
  construction), so it never shares a process -- or a system prompt -- with translate (#286).
  `JobHandlers.claude(home, env, store, log)` builds it for `run`: the CLI from `ClaudeBinary.locate`
  (`CLAUDE_BIN`, `PATH`, `~/.local/bin`), the settings of `claude.json` (default model `sonnet`,
  effort `low`), one `DailyCap` meant for every conversation. `run` warms each conversation up as it
  starts (a first job never waits for a cold start) and closes it when it ends. No CLI, an unusable
  `CLAUDE_BIN` or a corrupt `claude.json`: one line in the run log and no handler, so every job is
  answered `unsupported_kind`.

## Subtitle fetch loop (package `com.teachermovies.bridge.subtitles`, #282)
- ADR-0005 §5: the bridge works through the TV's subtitle needs, searching OpenSubtitles and
  reporting each result back, without the laptop having to be on all the time.
- `SubtitleFetchLoop(api, searcher, log, interval = 30 min, sleep, now)`. `searcher` is
  `opensubtitles.SubtitleSearcher` (`find(SubtitleRequest)`, `quota()`), which `SubtitleFinder`
  implements (#281).
- Triggers: `request(reason)` (it is the `SubtitleTrigger` `RunLoop` takes as `subtitles`) on every
  accepted job stream (`conexión`) and every `subtitles-needed` frame (`aviso de la TV`), and a timer
  every `interval` (`temporizador`). Requests go through a conflated channel into one consumer, so
  passes never overlap and any number of requests during a pass become one more pass after it.
  `run(url, token, onPass)` re-reads the TV URL before every pass (it follows the job loop's mDNS
  re-discovery) and runs until cancelled.
- Catch-up: nothing is queued for a bridge that is off (#280); the pass on connect reads the whole
  needs list, which holds everything that accumulated meanwhile, and works through all of it.
- A pass: reads `GET /api/bridge/subtitle-needs`; for each need in the TV's order posts
  `searching`, asks the searcher with the need's language (`en` -> English, `es` -> Spanish), its
  moviehash and its title, and then: a found subtitle is uploaded with its variant label
  (`latino` for the last-resort Latin-American one); nothing found is `not_found` (the TV starts its
  seven-day retry); anything else is `failed` with a short Spanish `message` -- the quota, refused
  credentials, a rate limit, an OpenSubtitles status or network reason, a refused upload (`la TV
  no ha aceptado el fichero (413 too_large)`) -- never a credential, the JWT or a body. A language
  other than `en`/`es` is `failed` without a search; a need whose `searching` the TV refuses (404
  `unknown_need`, …) is skipped. The result of a pass is `Pass(uploaded, notFound, failed, skipped,
  stoppedBy)`.
- Quota: a pass does not start -- not even the list is read -- and stops before its next need while
  `searcher.quota().isExhausted(now)` (no download left and the reset still ahead). A search
  answered `QuotaExhausted` is reported `failed` (so the TV lists it again) and ends the pass; so do
  refused credentials and a rate limit (`Stop.OPENSUBTITLES`), which would fail every need behind
  them. With `OpenSubtitlesApi` also refusing locally until the reset, the daily quota is never
  overrun by more than the one 406 that reveals it.
- Its lines go to the `RunLog`: how many needs each pass found and why it ran, one line per need
  (title, language, outcome, the stored path), and a summary with the downloads left today. A timer
  pass with nothing to do writes nothing.

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

## Systemd user unit (package `com.teachermovies.bridge.service`, #278)
- ADR-0005 §1: the bridge runs as a `systemd --user` service with `loginctl enable-linger`, so it is
  up whenever the laptop is on and connected, with no desktop session behind it. Installing it and
  operating it day to day is `docs/runbooks/laptop-bridge.md`; this section is what the code
  guarantees.
- The unit is a template on the classpath (`src/main/resources/teachermovies-bridge.service.tmpl`),
  so an installed distribution carries its own. `ServiceUnit.render(UnitSpec)` substitutes the three
  things only this laptop knows -- the absolute `ExecStart`, `WorkingDirectory` (the home directory)
  and the `Environment=` lines -- and leaves the policy in the template's own text, which is why the
  runbook can quote the unit verbatim: `Type=exec`, `Restart=on-failure` with `RestartSec=10`,
  `StartLimitIntervalSec=300`/`StartLimitBurst=5`, `WantedBy=default.target`. There is no network
  ordering, because `run` backs off and re-discovers the TV by itself: a unit that comes up before
  the network does is retrying, not failed.
- `ExecStart` is the start script `installDist` generated -- quoted, absolute, followed by `run`.
  `BridgeLauncher.locate` takes it from `--exec`, or derives it from this process's own code source
  (`<root>/lib/*.jar` -> `<root>/bin/teachermovies-bridge`); a program run out of a build directory
  derives nothing and comes back `Underived(tried)`, since a unit pointing into `build/` would break
  at the next `clean`. An `--exec` that is missing or cannot run is `Unusable(path, exists)`. No unit
  is written in either case.
- `Environment=` carries three names and nothing else (`ServiceUnit.ENVIRONMENT_KEYS`), each an
  absolute path: `PATH`, `TEACHERMOVIES_TV_LOGS_DIR` and `CLAUDE_BIN`.
  - `PATH` is `ServiceUnit.pathValue`: the running JDK's `bin`, the start script's directory, the
    CLI's directory, then the installing shell's own `PATH`, duplicates dropped. A user manager comes
    up with a minimal one and, with linger, has no session to inherit one from, so this is what lets
    the start script find `java` and the Claude Code CLI find its own `node`.
  - `TEACHERMOVIES_TV_LOGS_DIR` is `ServiceUnit.tvLogsDir`: the environment's own when set and
    non-blank, else `tv-logs` inside the bridge's config directory -- never `TvLogFiles.defaultDir`'s
    working-directory default, which a service's cwd would decide.
  - `CLAUDE_BIN` is `ClaudeBinary.locate`'s answer: the environment's own `CLAUDE_BIN`, else the
    first `claude` on `PATH`, else `~/.local/bin/claude`. The line is left out when there is none
    (`Absent`) or when the named file cannot run (`Unusable`), and the report says which. It is the
    override #276's Claude Code transport reads.
- A value systemd would misread is refused rather than escaped around: `UnitRender.Refused(field,
  character)` for a `"`, a `\`, a `$` or a line break in anything rendered, each of which would
  forge a directive or an argument. A `%` is doubled instead, because a path may legitimately hold
  one and `%%` is systemd's literal.
- `ServiceInstaller` writes the text to `ServiceUnit.unitFile` -- `systemd/user` under
  `ConfigLocation.configHome`, so `XDG_CONFIG_HOME` moves the unit along with everything else --
  creating the directory `0700` when it has to, and setting the file to `0644` on every install, so
  one whose permissions had been loosened is tightened back. Installing again replaces that one unit
  and reports `replaced`; an `IOException` is `Failed(reason)` with the exception's class name, and a
  filesystem with no POSIX permissions raises through to the JVM as everywhere else in this module.

## Boundaries
- The bridge token and the PIN are never printed to stdout or stderr, and the module has no logging
  framework of its own for them to reach: `doctor` and `logs` say that a token is stored and valid,
  never what it is, and `install-service` (#278) prints the path of the file the token stays in and
  nothing from inside it. The TV's ring buffer arrives already redacted by the TV (`LogRedactor`,
  ADR-0006 §3) and this module prints those lines verbatim, adding no redaction of its own -- also
  in the mirror's files (#272), which is one reason they are `0600` in a `0700` directory.
- Known noise, no secret involved: `slf4j-api` arrives transitively with the Ktor client and no
  provider is on the runtime classpath, so every command that talks to the TV prints three
  `SLF4J(W)` lines to the real stderr. The tests cannot see them, because they capture the injected
  `Appendable`s instead of the console.
- Not here yet, each its own issue: the moviehash itself (#279: the TV
  computes it, this module only sends it). The seven-day not-found retry is the TV's
  (`SubtitleFetch.isDue`, #280): the bridge only reports `not_found` and never tracks it itself.
- The OpenSubtitles credentials follow the token's rule: no command prints them, `doctor` reports
  the file's existence and mode only, and nothing in the `opensubtitles` package puts one -- or the
  JWT -- in a result, a failure or a `toString`.
- The `laptop-bridge` row in AGENTS.md's module table is the human's edit, not this module's.

## Tests
`bash scripts/test.sh :laptop-bridge:test` (thirty-two test classes as of #286) and
`./gradlew :laptop-bridge:ktlintCheck` for style. No test touches the network or the real home
directory: `tv.FakeTv` is a real Ktor CIO server on a loopback port standing in for the TV (the
criterion "tests run against an in-test Ktor server"), serving `/api/pair`, `/api/status`,
`/api/logs`, (#280) the three subtitle routes -- a scripted needs list that `searching` empties and
`failed` refills, as the TV does, plus `statusAnswer`/`uploadAnswer` refusals -- and (#277) the job stream `GET /api/bridge/jobs` -- a `: ping`, then whatever frames a
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
- `SubtitleFetchLoopTest` (#282, over `FakeTv` and a scripted `SubtitleSearcher`): the pass on the
  job stream opening catching up on three accumulated needs, a `subtitles-needed` frame running
  another pass, the 30-minute timer driving passes on its own, `not_found` reported with nothing
  uploaded, the multipart upload (fields, `latino` variant, text, bearer header), a spent quota
  failing the need, leaving the rest listed, not even reading the list before the reset and picking
  up after it, a quota exhausted by the last download stopping before the next need, refused
  credentials stopping the pass, a network failure failing only its need, a refused upload, an
  unsupported language, an unreadable list and a refused `searching`.
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
- `ServiceUnitTest` (#278) is the unit's text: an absolute, quoted `ExecStart` ending in `run`,
  `Restart=on-failure` with its delay, the start limit, `WantedBy=default.target`, the three
  `Environment=` names in order and their exact values, no `CLAUDE_BIN` line when there is no CLI,
  `%%` for a `%` in a path, `Refused(field, character)` for a `"`, a `$` or a `\`, the unit and TV
  log directories under `XDG_CONFIG_HOME`, and `pathValue`'s order and deduplication. Its last test
  is the acceptance criterion's own: a unit rendered and installed for a home that holds both a
  stored token and an OpenSubtitles credentials file carries no token, PIN, key, user or password,
  none of the config file's own text, and none of the spellings a secret could hide behind
  (`EnvironmentFile`, `PassEnvironment`, `Bearer`, `Authorization`, `TOKEN`, `API_KEY`, `PASSWORD`,
  `SECRET`, `ANTHROPIC`, `--pin`); the template on the classpath is held to that same list and to its
  own placeholder set.
- `ServiceInstallerTest` (the file, `0644` in a `0700` directory, a loosened unit tightened back,
  `replaced` on a second install, a directory that cannot exist as `Failed` with the exception's
  name), `BridgeLauncherTest` (`--exec` absolute and `~`-expanded, missing, not executable, and no
  derivation out of a build directory) and `ClaudeBinaryTest` (the override winning, `PATH` order,
  the `~/.local/bin` fallback, an unusable override reported instead of replaced, `Absent`, a `claude`
  that cannot run skipped) cover the rest of the package; `ArgsParserTest` and `BridgeCliTest` cover
  `install-service`'s parsing and its whole report -- that the next steps it prints name
  `daemon-reload`, `enable --now` and `loginctl enable-linger`, that it warns when there is no
  pairing, that a second install says "sustituida", that nothing is written when the script cannot
  run, and that neither stream carries a token, a PIN or a credential. `ConfigLocationTest` covers
  the new `configHome` the unit directory hangs off.
- No JVM test covers the JmDNS browse itself (it needs a LAN with multicast and a TV announcing on
  it), nor `run` against the real `:http-server` hub: `FakeTv` spells the #275 contract out from
  `docs/modules/http-server.md`.
- No JVM test covers `main` itself (the stream flushes and `exitProcess`), the start script
  `installDist` generates, or a real TV on the LAN -- those are manual checks, as elsewhere. For
  #271 they were done by driving the installed `teachermovies-bridge` against a stub of the TV's
  three routes, which is where the observed modes, exit codes and `~` expansion above come from.
- Nothing runs systemd either: no test installs into the real `~/.config/systemd/user`, and none
  enables, reloads, starts or verifies a unit. The unit's syntax was checked once by hand, with
  `systemd-analyze verify --user` on this host's systemd 255 (exit 0 and no diagnostics for a unit
  holding every setting the template fixes), which is where the confidence in `Type=exec`, in a
  quoted `ExecStart` and in the start limit living in `[Unit]` comes from; that a user manager has no
  `network*.target` to order itself after was read off `systemctl --user list-unit-files`, and that
  `loginctl enable-linger` with no argument means the calling user off `loginctl(1)`. A real install,
  a reboot with linger and a service that restarts itself are #295's end-to-end verification.
- `TranslateHandlerTest` (#286, over `FakeClaudeCli`): a good reply posted as the bare Spanish line
  with no JSON around it, the line sent as the one-field JSON data turn with none of it in the system
  prompt, an injection attempt staying a JSON string, the re-ask (problem plus the same data) and its
  success, two bad replies as `invalid_reply` with no third ask and no reply text in the message, a
  translation inside the char bound but over the result body's byte limit re-asked rather than
  posted, each non-answer code without a re-ask, a failure on the re-ask, dispatch off the wire
  through the registry, and the wrong kind of conversation or job refused. `TranslateReplyTest`
  covers the bound at its edge, code points, what is tolerated and each schema break's wording.
- `ExplainHandlerTest` (#291, over `FakeClaudeCli`): a good reply posted as the `ExplanationDto` JSON
  with its `promptVersion`, the context sent as a JSON data turn with none of it in the system prompt,
  an injection attempt staying a JSON string, the re-ask (problem plus the same data) and its success,
  two bad replies as `invalid_reply` with no third ask and no reply text in the message, each
  non-answer code without a re-ask, a failure on the re-ask, the forced-null difference, dispatch off
  the wire through the registry, and the wrong kind of conversation or job refused. `ExplainReplyTest`
  covers every limit at its edge, code points, and each schema break's wording; `JsonReplyTest` the
  extraction; `JobHandlersTest` the wiring (one handler per kind over a conversation of its own,
  none started; no CLI, an unusable `CLAUDE_BIN` and a corrupt `claude.json` as no handler, logged);
  `:bridge-protocol`'s `ExplanationDtoTest` the golden JSON. No test runs the real CLI, so how well
  Sonnet keeps to the limits -- how often the re-ask is needed -- is #295's to observe.
- The Claude transport (#276) never runs the real CLI: `claude.FakeClaudeMain` is a small Kotlin
  `main` speaking the `stream-json` subset the transport reads, started through a `/bin/sh` wrapper
  script (`claude.FakeClaude`) on the test JVM's own `java` and classpath, so the tests drive real
  processes -- argv, environment, working directory, timeouts, deaths and rotation -- and assert on
  the fake's own logs of what it was started with and what each turn received. It refuses a
  relative or missing `--system-prompt-file` like the real CLI. `BridgeCliTest` installs a copy as
  `~/.local/bin/claude` in its temporary home for `doctor`. What the real CLI emits was taken from
  the research note behind ADR-0005 (`init.apiKeySource`, `result.api_error_status`,
  `total_cost_usd` as a running total), not observed in this task: #295 is where the real CLI meets it.
