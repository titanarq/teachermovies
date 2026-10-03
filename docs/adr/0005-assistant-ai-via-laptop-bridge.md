# ADR-0005: Assistant AI through a laptop bridge on the Claude subscription; automatic bilingual subtitles

- **Status:** Accepted
- **Date:** 2026-09-27
- **Deciders:** MatillaM
- **Amends:** ADR-0001 §6 (assistant AI), ADR-0002 (token scopes), ADR-0004 (adds the `kotlin-jvm`
  plugin, the Gradle `application` plugin and JmDNS)

## Context

Claude must run on the household's **subscription** through the Claude Code CLI, which only the
laptop can log in to; no Anthropic API key is used and the TV never stores one. The TV already has
a LAN-only HTTP server with PIN pairing, bearer tokens and SSE (ADR-0002). A real Spanish subtitle
beats machine translation, and what the learner really needs from the AI is *explanation*.
OpenSubtitles needs an API key + login and has daily quotas; those credentials live on the laptop.
`:http-server` and `:core-model` are Android libraries, so a laptop program cannot depend on them.

## Decision

1. **Bridge module.** `:laptop-bridge` (Kotlin/JVM, a `systemd --user` unit, with
   `loginctl enable-linger` so it runs without a desktop login) pairs with the TV via
   `POST /api/pair` with `scope:"bridge"`. The DTOs shared with `:http-server` live in a new
   pure-JVM `:bridge-protocol` module. Both modules are covered by the one label
   `module:laptop-bridge`.
2. **The TV stays the only server.** The bridge holds `GET /api/bridge/jobs` open (SSE;
   `Authorization` header only) and answers `POST /api/bridge/jobs/{id}/result`. Jobs are typed
   (`translate`, `explain`) with random single-use ids; the newest bridge stream wins. With no
   bridge connected the TV answers `NoBridge` immediately.
3. **Claude Code CLI** (per the studentassistant howto): one long-lived
   `claude -p --input-format stream-json --output-format stream-json` process per job kind, with
   `--tools ""`, `--strict-mcp-config`, `--setting-sources ""`, `--disable-slash-commands`,
   `--no-session-persistence`, a private empty absolute cwd and the system prompt via an absolute
   `--system-prompt-file`. `ANTHROPIC_API_KEY`/`ANTHROPIC_AUTH_TOKEN` are stripped from the
   environment and the bridge refuses to run unless `init.apiKeySource` is `none`. JSON replies go
   through tolerant extraction -> validation -> one re-ask. Model **Sonnet, low effort**, for both
   explain and the translation fallback, tuned for speed and configurable in the bridge. Processes
   are pre-warmed and rotated after N turns; the per-turn cost delta is logged as
   `billing=subscription`; a daily cap (default ~300 requests, configurable) applies.
4. **Token scopes.** Bridge token hashes are stored separately from phone token hashes. Bridge
   tokens reach only `/api/bridge/*` and `/api/logs*`; phone tokens are refused on `/api/bridge/*`.
   "Olvidar portátil" clears the bridge set. The `?token=` exception of ADR-0002 stays exclusive to
   `GET /api/events`.
5. **Automatic subtitles.** The TV computes the OpenSubtitles moviehash of each completed movie
   and publishes its subtitle needs; the bridge (the only holder of the credentials, in
   `.secrets/opensubtitles.env` on the laptop) searches by hash, then by title/year/IMDb id; it
   prefers non-HI and excludes machine-translated subtitles. Spanish: **Castilian first,
   Latin-American only as a last resort, labelled "latino"**. English only if the movie has none.
   Results are uploaded to `<volume>/Movies/<id>/subs/<base>.<lang>.opensubtitles.srt`; fetch state
   is kept in Room; not-found is retried after 7 days; the quota is tracked. If the laptop was off,
   the bridge catches up on reconnect. The phone web UI stays the management surface: a per-movie
   **"Buscar subtítulos"** action asks the bridge to retry now; manual upload is unchanged.
6. **LEFT = Spanish.** The ES cue aligned to the captured EN cue (global offset + frame-rate scale,
   with a quality score), labelled "subtítulo"; fallback a Claude translation via the bridge,
   labelled "IA"; neither -> "Traducción no disponible".
7. **RIGHT = "Explicar"** in the assistant panel (replaces the former "Escuchar"/TTS). Claude gets
   the title, the captured line, 3 EN lines before and 2 after, and the aligned ES line, and returns
   a short Spanish JSON shown on screen. **No TTS and no prefetch.** Panel keys: OK = repeat the
   original line, LEFT = Spanish, RIGHT = Explain, UP unchanged (unhandled in the panel), BACK =
   close; with the panel closed UP still opens the tracks panel. Hint:
   `OK Repetir · IZQUIERDA Español · DERECHA Explicar · ATRÁS Cerrar`.
8. Translations and explanations are cached in Room (database v2); only successes are stored.
9. **API-key path unwired.** The Anthropic API provider (#90/#212) is removed from Configuración
   and from the wiring, and the stored key is cleared on upgrade; `AnthropicTranslationProvider`
   and its dependency are kept **dormant** for future extensions, not deleted.
10. **TTS deferred** (future, optional, low priority). Nothing is spoken aloud; OK (replay of the
    original audio) is enough.

## Amendment 2026-09-29: D-pad interaction model (human decision after trying the web simulator)

The human tried the web simulator of the assistant player and replaced the panel-centred key model
of decisions 4, 6, 7 and 10 above with the one below. Where they disagree, this amendment wins.

- **DOWN opens the menu, informational only** (the captured English line, hints, the explanation
  panel). The menu is not needed to replay phrases.
- **LEFT (the arrow, not BACK) goes back N phrases and shows the English subtitle; RIGHT goes back N
  phrases and shows the Spanish subtitle.** N is the number of presses in a group: presses belong to
  one group while the gap between consecutive presses is < 1.5 s. After >= 1.5 s with no press the
  player rewinds N phrases and resumes playback with no menu, showing the chosen subtitle (EN for
  LEFT, ES for RIGHT; an already-visible EN stays, ES switches to EN on LEFT). When playback reaches
  the point where the first press happened it continues normally and the subtitle state from before
  the rewind is restored (nothing shown if nothing was shown).
- **With the menu open, UP explains, in English, what was said**; N presses cover N phrases. The
  video stays paused and the menu visible. The explanation is spoken with TTS when a voice exists
  and is also shown in a text panel that uses more of the screen (smaller font if needed). This
  replaces decision 7's "Spanish, on screen, no TTS" and lifts decision 10's TTS deferral for the
  explanation only; the translation line and the replayed audio are unchanged. The explanation
  stays a bridge job (decision 7's context, cache and no-prefetch rules stand); its prompt now
  answers in English, and the cache prompt version is bumped so Spanish rows are never served.
- OK keeps its transport/replay meaning; the "OK N times walks back" run of #339 is superseded by
  LEFT/RIGHT and is no longer the way to step back.

## Amendment 2026-10-03: English and Spanish subtitles are always fetched (human decision)

The human replaced decision 5's "English only if the movie has none" with the rule below. Where they
disagree, this amendment wins; the rest of decision 5 (search order, non-HI preference, no
machine-translated subtitles, Castilian first, storage path, retry and quota rules, "Buscar
subtítulos") stands.

- **Both languages are always requested.** For every stored movie the TV publishes an English and a
  Spanish subtitle need, also when the movie already carries an English track (embedded or
  sidecar); the bridge tries to download both from OpenSubtitles. A failed or not-found download
  changes nothing for playback (#359).
- **The player loads the track that serves the feature best**, the movie's own or the downloaded
  file; a downloaded subtitle is one more candidate, not a replacement (#358):
  - **English** is the track the assistant's hidden mode reads (source priority unchanged:
    sidecar, then embedded, then downloaded), so the line shown during a phrase rewind is the same
    one whose cues define the phrases being counted and replayed.
  - **Spanish** is the candidate that aligns best to that English track, whatever its source,
    re-timed onto the English (playback) timeline before it is shown.

## Amendment 2026-10-03: phrase-rewind restore point (human decision after testing #358 on the TV)

The human tried the LEFT/RIGHT phrase rewind of the 2026-09-29 amendment on the TV and found the
phrase they had just heard cut short (#363): playback went on during the 1.5 s group window, so the
restore point "where the first press happened" fell at the start of that phrase. The restore
condition of that amendment is replaced by the one below; "rewinds N phrases and resumes playback"
and the rest of it stand. Where they disagree, this amendment wins.

- **Restore point.** The previous subtitle state is restored when playback reaches **the end of the
  phrase in progress at the moment of the first press** (or the position of the press, if it falls
  in a gap between phrases). The phrase in progress counts as phrase 1 when going back, so the
  phrase just heard is replayed whole, with the chosen subtitle.
- The restore point is fixed once, at the first press of the run, and later presses never move it.
- Playback is **not paused** while the group window is open; the rewind is a single seek when the
  window closes.

## Consequences

- Explanations and the fallback translation need the laptop on and paired; aligned ES subtitles
  and already downloaded subtitles work offline. With the laptop off the panel shows
  "Traducción/Explicación no disponible".
- Subtitle text and its context leave the LAN to Anthropic via the CLI; moviehash, filename and
  title go to OpenSubtitles.
- Usage counts against the subscription window; a 429 shows as "no disponible".
- Only the household's authorized content; subtitles are for personal use, and the OpenSubtitles
  API terms and quotas are respected.
- Two new Gradle modules (`:laptop-bridge`, `:bridge-protocol`) and one new label
  (`module:laptop-bridge`); ADR-0004 gains rows for `kotlin-jvm`, `application` and JmDNS.
