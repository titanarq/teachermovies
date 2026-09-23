BUDGET CLASS FOR WORKER TASKS
   Every worker task goes to a Qwen class: `mechanical-qwen` when the change is small and fully
   specified, `complex-qwen` in every other case. Each task names exactly one `module:<name>`
   label matching a `docs/modules/<name>.md`, and its acceptance criteria include
   `scripts/test.sh` staying green.
