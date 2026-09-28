# Laptop bridge runbook -- installing and operating `teachermovies-bridge`

The laptop side of ADR-0005: one Kotlin/JVM program that pairs with the TV, answers its assistant
jobs through the Claude Code CLI on this laptop's own subscription, looks subtitles up on
OpenSubtitles and mirrors the TV's log. What it does and how it is built is
`docs/modules/laptop-bridge.md`; this runbook is how a human installs it, keeps it running and reads
its failures. Linux only -- the bridge writes POSIX permissions and #278 installs it as a
`systemd --user` service.

## What lives where

| path | what | mode |
|---|---|---|
| `~/.local/share/teachermovies-bridge/` | the installed distribution (`bin/teachermovies-bridge`, `lib/*.jar`); the location this runbook recommends, so no `clean` or move of the checkout breaks the unit | `0755` |
| `~/.config/teachermovies-bridge/config.json` | the TV's URL and the bridge-scoped token | `0600` |
| `~/.config/teachermovies-bridge/.secrets/opensubtitles.env` | the OpenSubtitles API key, user and password (#281) | `0600` |
| `~/.config/teachermovies-bridge/bridge.log` | the bridge's own log, appended by `run` | `0600` |
| `~/.config/teachermovies-bridge/tv-logs/<yyyy-mm-dd>.log` | the TV's log mirror, 14 days kept (#272); the unit sets `TEACHERMOVIES_TV_LOGS_DIR` to this, so it does not depend on the service's working directory | `0600` in `0700` |
| `~/.config/systemd/user/teachermovies-bridge.service` | the unit `install-service` writes | `0644` |

`XDG_CONFIG_HOME` moves every `~/.config` path above, including the unit directory. Nothing in this
table holds a secret except the two `0600` files under `.secrets` and `config.json`: the unit file is
world-readable by design and carries no token, no PIN and no credential (see "The unit").

## One-time install

```sh
git clone https://github.com/titanarq/teachermovies.git && cd teachermovies
./gradlew :laptop-bridge:installDist                    # JDK 17 toolchain, ADR-0004
mkdir -p ~/.local/share && cp -r laptop-bridge/build/install/teachermovies-bridge ~/.local/share/
B=~/.local/share/teachermovies-bridge/bin/teachermovies-bridge
$B --help
```

Then, in this order:

```sh
# 1. Pair with the TV. The PIN is the one the TV shows on screen; it is never printed back.
$B pair --url http://192.168.1.20:8787 --pin 482916 --name portatil

# 2. OpenSubtitles credentials, if this laptop should fetch subtitles (#281). Never committed,
#    never read aloud by an agent, never printed by any subcommand.
mkdir -p ~/.config/teachermovies-bridge/.secrets && chmod 700 ~/.config/teachermovies-bridge
$EDITOR ~/.config/teachermovies-bridge/.secrets/opensubtitles.env
chmod 600 ~/.config/teachermovies-bridge/.secrets/opensubtitles.env
#    OPENSUBTITLES_API_KEY=... / OPENSUBTITLES_USERNAME=... / OPENSUBTITLES_PASSWORD=...

# 3. Check the laptop before installing anything that restarts itself.
$B doctor

# 4. Write the systemd user unit and start it.
$B install-service                                      # run the installed script, not `gradlew run`
systemctl --user daemon-reload
systemctl --user enable --now teachermovies-bridge.service
loginctl enable-linger                                  # keeps the bridge up with nobody logged in
```

`install-service` writes the unit and nothing else: it never enables, reloads or starts anything, so
the three commands after it are the human's to run and to see fail. It takes one option,
`--exec <path>`, for the start script the unit must launch; with no `--exec` it derives the script of
the distribution it was started from, which is why it must be the *installed* one -- a unit pointing
into `build/install/` breaks at the next `clean`, and `./gradlew :laptop-bridge:run --args=...` has
no distribution to derive from and asks for `--exec` instead.

### `loginctl enable-linger`, and why the bridge needs it

A `systemd --user` manager normally exists only while its user has a session: it is spawned at login
and torn down at logout, so a user service stops when the lid closes on the desktop session and does
not exist at all after a reboot until somebody logs in. `loginctl enable-linger` changes that for
this user: the manager is spawned at boot and kept after logout, so the bridge runs on a laptop that
is switched on and connected, with no desktop session behind it (ADR-0005 §1, the human's decision 7
of 2026-09-26).

```sh
loginctl enable-linger            # no argument: the calling user; polkit may ask for the password
loginctl show-user "$USER" | grep -i linger     # Linger=yes
```

It is per user and survives reboots. `loginctl disable-linger` undoes it, after which the bridge only
runs while that user is logged in. On a shared or managed laptop this may need an administrator.

## The unit

`install-service` renders it from `laptop-bridge/src/main/resources/teachermovies-bridge.service.tmpl`
with three things only this laptop knows: the absolute `ExecStart`, the working directory and the
`Environment=` lines. What a laptop with the Claude Code CLI in `~/.local/bin` gets:

```ini
[Unit]
Description=teachermovies bridge (TV jobs, automatic subtitles, TV log mirror; ADR-0005)
StartLimitIntervalSec=300
StartLimitBurst=5

[Service]
Type=exec
WorkingDirectory=/home/alumno
Environment="PATH=/usr/lib/jvm/java-17-openjdk-amd64/bin:/home/alumno/.local/share/teachermovies-bridge/bin:/home/alumno/.local/bin:/usr/local/bin:/usr/bin:/bin"
Environment="TEACHERMOVIES_TV_LOGS_DIR=/home/alumno/.config/teachermovies-bridge/tv-logs"
Environment="CLAUDE_BIN=/home/alumno/.local/bin/claude"
ExecStart="/home/alumno/.local/share/teachermovies-bridge/bin/teachermovies-bridge" run
Restart=on-failure
RestartSec=10

[Install]
WantedBy=default.target
```

- **`ExecStart` is absolute and quoted**, and ends in `run`: systemd will not resolve a bare command
  name and there is no shell in between.
- **`PATH` is the installing shell's**, with the JDK's `bin`, the start script's directory and the
  CLI's directory ahead of it. A user manager comes up with a minimal `PATH` of its own and, with
  linger, no session to inherit one from; this is what lets the start script find `java` and the
  Claude Code CLI find its own `node`.
- **`CLAUDE_BIN` is the override #276's transport reads.** `install-service` resolves it from the
  environment's own `CLAUDE_BIN`, then a `claude` on `PATH`, then `~/.local/bin/claude`, and leaves
  the line out (saying so) when it finds none. Point it elsewhere by re-running
  `CLAUDE_BIN=/ruta/claude $B install-service`.
- **`Restart=on-failure` with `RestartSec=10`.** `run` does not exit while the TV is merely
  unreachable -- it backs off and re-discovers it over mDNS -- so a restart means the process itself
  died. The one exit a restart cannot mend is the TV refusing this bridge's token (401), and that is
  what `StartLimitIntervalSec=300`/`StartLimitBurst=5` are for: after five attempts in five minutes
  the unit parks in `failed` instead of looping. Re-pair, then restart it.
- **No secret, and no directive that could hold one.** No `EnvironmentFile=`, no `PassEnvironment=`:
  the bridge reads its `0600` token and credentials files itself. `ServiceUnitTest` asserts the
  unit's `Environment=` names are exactly `PATH`, `TEACHERMOVIES_TV_LOGS_DIR` and `CLAUDE_BIN`, and
  that a rendered unit from a paired, credentialed laptop contains no token, PIN or credential.

Change one of them with a drop-in, not by editing the file -- `install-service` rewrites it:

```sh
systemctl --user edit teachermovies-bridge.service      # writes ~/.config/systemd/user/…d/override.conf
systemctl --user cat teachermovies-bridge.service       # the unit plus every drop-in
```

## Day to day

```sh
systemctl --user status teachermovies-bridge.service --no-pager
journalctl --user -u teachermovies-bridge -f            # the same lines as bridge.log
tail -20 ~/.config/teachermovies-bridge/bridge.log
$B doctor                                               # seven checks, exit 1 on any problem
$B logs --level warn --limit 50                         # one page of the TV's ring buffer
systemctl --user restart teachermovies-bridge.service   # after a drop-in edit or a re-pair
systemctl --user stop teachermovies-bridge.service      # the TV answers NoBridge while it is down
```

**Upgrade.** The unit points at a fixed path, so upgrading is: build, copy over the same path,
re-install the unit, reload, restart.

```sh
./gradlew :laptop-bridge:installDist
cp -r laptop-bridge/build/install/teachermovies-bridge/. ~/.local/share/teachermovies-bridge/
$B install-service && systemctl --user daemon-reload && systemctl --user restart teachermovies-bridge
```

**Uninstall.** `systemctl --user disable --now teachermovies-bridge.service`, delete the unit file,
`systemctl --user daemon-reload`, then `$B unpair` (and "Olvidar portátil" on the TV, which is what
drops the TV's copy of the token's hash). Linger is a property of the user, not of this service:
leave it on if anything else uses it.

**Re-pair** after the TV forgot this laptop (a 401, or `doctor` saying the token is refused):
`$B pair --url … --pin …` then `systemctl --user restart teachermovies-bridge.service`. The config
file is written `0600` and replaced in place; the unit never changes.

## Reading a failure

| symptom | cause | fix |
|---|---|---|
| `install-service` exits 1: "No se ha podido deducir la ruta del script que arrancar" | run from `gradlew run`, or from a distribution with no `bin` next to its `lib` | pass `--exec <absolute path of the start script>` |
| `install-service` exits 1: the script "no existe" / "no es ejecutable" | a wrong or stale `--exec` | `installDist` again and copy, or point `--exec` at the real script |
| unit `failed`, five restarts in five minutes | `run` exited: the TV refused the token (401) | `journalctl --user -u teachermovies-bridge -n 50`, then re-pair and `restart` |
| unit `failed` at once: `status=203/EXEC` | `ExecStart` no longer exists (the distribution moved) or its `java` is gone from `PATH` | `$B install-service` from the new location, `daemon-reload`, `restart` |
| `run` up but the TV shows no bridge | the TV and this laptop are not on the same LAN, or the TV's port is firewalled | `$B doctor`; the bridge backs off and re-discovers over mDNS, so waiting is normal after a TV move |
| jobs answered "no disponible" | no `CLAUDE_BIN` in the unit, the CLI not logged in to the subscription, or the daily cap spent | `$B install-service` again with the CLI on `PATH`; `claude` by hand to see its own complaint |
| no automatic subtitles | the credentials file is missing, incomplete or not `0600` | `$B doctor` reports which; the file is read once, so restart the service after fixing it |
| `tv-logs` empty | the TV's ring buffer is only in memory and the mirror starts from its cursor | normal after a TV restart: the bridge writes a gap marker and copies the new boot from the start |

## What no check here proves

A real reboot with linger enabled and nobody logged in, a real TV answering on the LAN, the Claude
Code CLI's own login state and subscription window, and OpenSubtitles' quota. Those are #295's
end-to-end verification, on the hardware itself.
