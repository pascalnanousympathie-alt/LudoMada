# Tool annotation rationales — current private server

The machine-readable policy is in ../personal-mcp-commander/publication-policy.mjs. The private server advertises the three booleans to MCP clients; the accompanying rationales are for a future human review and do not enforce permissions.

Correct annotations do not make the 44-tool private server suitable for public release. The public listing must use the separate, least-privilege tool interface described in PUBLIC_TOOL_BOUNDARY.md.

| Tool category | Read only | Open world | Destructive | Rationale |
| --- | --- | --- | --- | --- |
| Local status, lists, reads, searches, diagnostics | true when no state changes occur | false | false | Bounded to the local workspace/device; does not edit user data. |
| File create/edit/move/restore | false | false | true for overwrite/move/restore; false for additive mkdir/snapshot | These actions modify the workspace; overwrites and moves may lose the current state. |
| Arbitrary shell, configurable jobs, browser JavaScript | false | true | true | Can execute code, reach arbitrary destinations, and modify external or local state. |
| Local browser navigation | false | true | false | Navigation changes the browser page and can visit arbitrary URLs. |
| Android install/screenshot/launch | false for mutating or file-writing actions | false | true for install or overwrite | These actions target the connected device; screenshots can overwrite files and expose private screen contents. |
| GitHub review/merge/workflow actions | false | true for externally visible changes | true | Reviews, merges, reruns, and cancellations alter remote repository or workflow state. |
| Local Git status/diff | true | false | false | Reads the configured local repository only. |
| Local Git commit/build/test | false | true only where scripts/workflows can reach external destinations | true | These can change repository/artifact state or execute project-controlled code. |

For an actual submission, check each annotation shown by OpenAI's tool scan and enter matching justifications in the review portal. The annotations are descriptions, not a security boundary.
