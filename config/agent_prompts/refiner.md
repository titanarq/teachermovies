BUDGET CLASS FOR WORKER TASKS
   Every worker task gets exactly one of the three worker classes in `config/agents.yaml`:
   - `mechanical-qwen` -- the change is small and fully specified: a handful of files, the exact
     edit or a known test shape named in the body, no design choice left to the worker.
   - `complex-claude` (Claude Opus) -- the task needs judgement the brief cannot pre-decide: a new
     interface or cross-module seam, concurrency or a native-library bridge (jlibtorrent, libVLC),
     a bug whose cause is not yet known, or a change spanning several modules' behaviour.
   - `complex-qwen` -- anything between the two: more than a mechanical edit, but fully specified
     and confined to one module's known patterns.
   When in doubt between a Qwen class and `complex-claude`, choose the Qwen class: Opus runs on
   the subscription window the planner, validator and refiner also depend on. Each task names
   exactly one `module:<name>` label matching a `docs/modules/<name>.md`, and its acceptance
   criteria include `scripts/test.sh` staying green.
