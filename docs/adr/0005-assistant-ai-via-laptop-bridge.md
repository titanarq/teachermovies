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
