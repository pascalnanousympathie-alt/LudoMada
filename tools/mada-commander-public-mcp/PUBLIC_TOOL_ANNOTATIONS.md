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

## Additional workspace and browser tools

| Tool | `readOnlyHint` and rationale | `openWorldHint` and rationale | `destructiveHint` and rationale |
|---|---|---|---|
| `mada_workspace_list` | `true`: lists bounded names/types from one registered local project folder. | `false`: resolved only against the account's paired agent and project alias; sensitive/generated directories are excluded. | `false`: no local files are changed. |
| `mada_workspace_read` | `true`: reads a bounded text/source file and sanitizes the returned content. | `false`: file names are relative to one registered project; absolute paths and credentials are not accepted. | `false`: no files are changed. |
| `mada_workspace_write` | `false`: queues a change to a source/text file in the user's registered project. | `false`: only a paired agent and project-relative path are accepted, with blocked secret paths and content checks. | `true`: an existing source file can be replaced; subsequent tests and Git review are recommended. |
| `mada_run_task` | `false`: queues one operation from the fixed Git/npm/Gradle task allowlist. | `false`: the task runs only in a selected registered project and accepts no free-form command or arguments. | `false`: tasks are limited to Git status/diff, existing test/lint/build scripts and fixed Android test/build wrapper tasks; no file-deletion command is exposed. |
| `mada_run_command` | `false`: queues an approved command execution, creating an auditable job record, then returns bounded output. | `true`: the direct executable may access external services or the network using the paired user permissions. | `true`: approved commands can modify/delete files, install dependencies, change repositories, or affect external services; arguments are separate and shell parsing is not available. |
| `mada_run_shell` | `false`: queues a complete shell command and returns bounded, redacted output after the paired agent finishes. | `true`: shell execution runs with the paired OS user's network permissions. | `true`: pipelines, redirection and command chaining can read, modify or delete accessible files, install software and affect external services; output redaction is best-effort, so never request secret disclosure. |
| `mada_opera_status` | `true`: reads the paired Opera instance's version and open-tab count. | `false`: targets only the loopback CDP service of the user's paired Termux agent. | `false`: no browser state is changed. |
| `mada_opera_tabs` | `true`: lists bounded tab IDs, titles and sanitized addresses. | `false`: the tool cannot specify arbitrary browser or network targets. | `false`: it does not change tabs. |
| `mada_opera_navigate` | `false`: changes the selected Opera tab's address. | `false`: it accepts only a selected tab and validated HTTP(S) or Opera settings/about address; secret-like URL query values are rejected. | `false`: navigation is reversible and does not itself delete data or change account permissions. |
| `mada_opera_action` | `false`: can change visible page controls through a fixed structured action allowlist. | `false`: no arbitrary JavaScript is accepted; the target is one tab of the paired local Opera instance. | `true`: a supported click, fill, selection or toggle can change settings; password, identity, token and destructive controls are blocked. |


## Optional local MCP bridge tools

| Tool | `readOnlyHint` and rationale | `openWorldHint` and rationale | `destructiveHint` and rationale |
|---|---|---|---|
| `mada_mcp_integrations` | `false`: it queues a local inventory job and therefore creates job state, while the local operation only reports configuration metadata. | `false`: it reads only the signed-in user's own paired agent's local MCP registry; no endpoint or command is accepted from the caller. | `false`: it does not execute an external MCP tool or change the registry. |
| `mada_mcp_tools` | `false`: it queues a tool-discovery job and creates job state, even though the discovery request does not intentionally mutate the target service. | `true`: the selected, owner-configured MCP server can be a remote HTTPS service, so discovery communicates outside the local workspace. | `false`: discovery lists only locally allowlisted tools and does not call a tool. |
| `mada_mcp_call` | `false`: it queues and runs an external MCP call; a queue record is created and the external tool may have side effects. | `true`: a locally approved MCP tool can communicate with remote services or browser targets. The caller cannot supply a new server URL, command, or server configuration. | `true`: the owner may deliberately enable explicitly allowlisted write tools in the local config. By default, only the `readOnlyTools` list is exposed; credentials are mapped from local environment variables and never returned by the catalog. |
