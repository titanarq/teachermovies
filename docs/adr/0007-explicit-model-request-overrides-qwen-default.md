# ADR-0007: An explicit model request overrides the "workers run on Qwen" ADR

- **Status:** Accepted
- **Date:** 2026-09-29
- **Deciders:** MatillaM
- **Amends (in this project):** `agent_os/docs/adr/2026-09-16-workers-run-on-qwen-and-claude-only-reviews.md`

## Context

The agent_os ADR of 2026-09-16 says workers run on Qwen and Claude only reviews, and the
control-plane prompt repeats it ("never assign a worker task to a Claude backend"). This project
has since defined a Claude worker class (`complex-claude`, now on `claude-sonnet-5-5`), so the
ADR and the configuration conflict (recorded in `docs/runbooks/operations.md`). The file lives
under `agent_os/`, which this repository never edits.

## Decision

The human decided on 2026-09-29: "modifica el ADR del 16-09, si se solicita un modelo, eso manda
por encima del ADR".

1. When the human, or an issue/brief written on the human's behalf, explicitly requests a model or
   budget class (for example `complex-claude`, or `claude-sonnet-5-5`), that request takes
   precedence over the 2026-09-16 ADR.
2. Qwen (`mechanical-qwen` / `complex-qwen`) remains the default when no model is requested.
3. Claude review roles are unchanged.
4. This ADR is the project-side record of the amendment. The upstream ADR and the
   `agent_os/agents/control-plane.md` prompt are not edited here; the upstream change is
   "to report" to `titanarq/agent-os` (see `docs/runbooks/operations.md`).

## Consequences

- `.claude/agents/control-plane.md` keeps writing Qwen classes by default but assigns a Claude
  class when a model is explicitly requested.
- Quota risk of Claude workers (shared subscription window) is accepted by the human for those
  explicit requests.
