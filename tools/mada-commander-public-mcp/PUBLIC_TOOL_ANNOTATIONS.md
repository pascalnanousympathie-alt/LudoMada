# Public tool annotation justification matrix

This is the operator's submission worksheet for OpenAI's MCP review. The current submission requirements ask for a justification for **each** `readOnlyHint`, `openWorldHint`, and `destructiveHint` value on every tool. Enter the corresponding rationale in the review portal for each tool. These explanations are review metadata; do **not** add invented justification fields to the MCP wire-format annotations.

| Tool | `readOnlyHint` and rationale | `openWorldHint` and rationale | `destructiveHint` and rationale |
|---|---|---|---|
| `mada_agent_status` | `true`: reads the signed-in user's own paired-agent names and recent online state; does not change records. | `false`: reads only agents owned by the authenticated account; it does not browse or act on arbitrary external targets. | `false`: the call cannot revoke, edit, enroll or delete an agent. |
| `mada_list_projects` | `true`: lists only aliases and bounded Git metadata already registered by the user's own agent. | `false`: results are restricted to the authenticated user's pre-registered projects; it accepts no URL, path or arbitrary external target. | `false`: it does not modify the project, repository, agent or service records. |
| `mada_run_diagnostics` | `false`: queues a new diagnostic job, which changes service state. The later local check is limited to fixed, read-only profiles. | `false`: the account must select an existing project and its exact owning agent; the profile must be one of `git_status`, `toolchain` or `inventory`. It cannot target arbitrary internet systems. | `false`: queueing a job does not delete or alter project files; the local agent runs only the fixed read-only diagnostic definitions. |
| `mada_job_status` | `true`: reads one diagnostic job only when it belongs to the authenticated user, returning bounded, sanitized output. | `false`: lookup is scoped to the authenticated user's own job and project; no arbitrary URL, system or third-party service is targeted. | `false`: the call does not cancel, rerun, modify or delete a job. |
| `mada_connection_help` | `true`: returns instructions only; it does not issue pairing codes or mutate agent state. | `false`: guidance applies only to the user's own paired device and locally registered project. | `false`: the call does not revoke an agent or delete account data; those actions require separate authenticated dashboard operations. |

The hosted endpoint being publicly reachable does not, by itself, make a tool open-world. These justifications describe the actual tool behavior and do not replace server-side authorization or validation.

Before submission, compare this matrix against the current tool scan. If any tool behavior or hint changes, update the justification for every affected hint in the OpenAI submission portal and rerun the scan.
