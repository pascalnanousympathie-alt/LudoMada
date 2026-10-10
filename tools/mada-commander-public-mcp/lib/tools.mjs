import crypto from "node:crypto";
import { query } from "./db.mjs";
import { redactSensitive, REQUIRED_SCOPE, isSafeProjectAlias } from "./security.mjs";

const oauth = [{ type: "oauth2", scopes: [REQUIRED_SCOPE] }];
const annotations = {
  readOnly: { readOnlyHint: true, openWorldHint: false, destructiveHint: false },
  queued: { readOnlyHint: false, openWorldHint: false, destructiveHint: false }
};

const agentSummary = {
  type: "object",
  properties: {
    name: { type: "string" },
    connected: { type: "boolean" }
  },
  required: ["name", "connected"],
  additionalProperties: false
};
const projectSummary = {
  type: "object",
  properties: {
    projectId: { type: "string" },
    name: { type: "string" },
    branch: { type: "string" },
    head: { type: "string" },
    agentId: { type: "string" },
    agentConnected: { type: "boolean" }
  },
  required: ["projectId", "name", "branch", "head", "agentId", "agentConnected"],
  additionalProperties: false
};
const diagnosticResult = {
  anyOf: [
    {
      type: "object",
      properties: {
        branch: { type: "string" },
        head: { type: "string" },
        dirtyCount: { type: "integer" }
      },
      required: ["branch", "head", "dirtyCount"],
      additionalProperties: false
    },
    {
      type: "object",
      properties: {
        node: { type: "string" },
        npm: { type: "string" },
        git: { type: "string" },
        javaAvailable: { type: "boolean" },
        gradleWrapperPresent: { type: "boolean" }
      },
      required: ["node", "npm", "git", "javaAvailable", "gradleWrapperPresent"],
      additionalProperties: false
    },
    {
      type: "object",
      properties: {
        hasGit: { type: "boolean" },
        hasPackageJson: { type: "boolean" },
        hasGradleWrapper: { type: "boolean" },
        hasReadme: { type: "boolean" },
        topLevelFileCount: { type: "integer" }
      },
      required: ["hasGit", "hasPackageJson", "hasGradleWrapper", "hasReadme", "topLevelFileCount"],
      additionalProperties: false
    }
  ]
};

export const PUBLIC_TOOLS = Object.freeze([
  {
    name: "mada_agent_status",
    title: "Mada Commander agent status",
    description: "List whether this signed-in account's own paired agents have checked in recently. Returns agent names and online/offline state only, without device paths, timestamps, credentials, or account identifiers.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
    outputSchema: {
      type: "object",
      properties: { agents: { type: "array", items: agentSummary } },
      required: ["agents"],
      additionalProperties: false
    },
    securitySchemes: oauth,
    annotations: annotations.readOnly
  },
  {
    name: "mada_list_projects",
    title: "List my connected projects",
    description: "List only project aliases explicitly registered by this signed-in account's own paired agents. It returns a project alias, display label, branch, short commit hash, and the owning paired-agent ID needed to disambiguate repeated aliases. Local absolute paths, file contents, timestamps, and credentials are never returned.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
    outputSchema: {
      type: "object",
      properties: { projects: { type: "array", items: projectSummary } },
      required: ["projects"],
      additionalProperties: false
    },
    securitySchemes: oauth,
    annotations: annotations.readOnly
  },
  {
    name: "mada_run_diagnostics",
    title: "Run approved project diagnostics",
    description: "Queue one fixed, non-shell diagnostic on a project already registered by this signed-in account's own paired agent. The local agent executes only git_status, toolchain, or inventory; this creates a diagnostic job but cannot accept commands, paths, scripts, URLs, or arbitrary arguments. The projectId and its exact agentId must come from mada_list_projects.",
    inputSchema: {
      type: "object",
      properties: {
        projectId: { type: "string", pattern: "^[a-z][a-z0-9_-]{0,39}$", description: "Project alias returned by mada_list_projects." },
        agentId: { type: "string", pattern: "^[0-9a-f-]{36}$", description: "Exact owning agent ID returned beside that project by mada_list_projects." },
        profile: { type: "string", enum: ["git_status", "toolchain", "inventory"], description: "A fixed, read-only diagnostic profile." }
      },
      required: ["projectId", "agentId", "profile"],
      additionalProperties: false
    },
    outputSchema: {
      type: "object",
      properties: {
        jobId: { type: "string" },
        status: { type: "string", enum: ["queued"] },
        profile: { type: "string", enum: ["git_status", "toolchain", "inventory"] },
        projectId: { type: "string" },
        projectName: { type: "string" }
      },
      required: ["jobId", "status", "profile", "projectId", "projectName"],
      additionalProperties: false
    },
    securitySchemes: oauth,
    annotations: annotations.queued
  },
  {
    name: "mada_job_status",
    title: "Get my diagnostic status",
    description: "Read the status and bounded sanitized output for a diagnostic job owned by this signed-in account. A job ID belonging to another account is indistinguishable from an unknown job. It does not return job timestamps, raw logs, absolute paths, or credentials.",
    inputSchema: {
      type: "object",
      properties: { jobId: { type: "string", pattern: "^[0-9a-f-]{36}$", description: "Job ID returned by mada_run_diagnostics." } },
      required: ["jobId"],
      additionalProperties: false
    },
    outputSchema: {
      type: "object",
      properties: {
        jobId: { type: "string" },
        projectId: { type: "string" },
        projectName: { type: "string" },
        profile: { type: "string", enum: ["git_status", "toolchain", "inventory"] },
        status: { type: "string", enum: ["queued", "running", "succeeded", "failed", "cancelled"] },
        result: { anyOf: [diagnosticResult, { type: "null" }] },
        error: { type: "string" }
      },
      required: ["jobId", "projectId", "projectName", "profile", "status", "result", "error"],
      additionalProperties: false
    },
    securitySchemes: oauth,
    annotations: annotations.readOnly
  },
  {
    name: "mada_connection_help",
    title: "Connect or revoke my device",
    description: "Explain how to pair the signed-in user's own device, register a local project alias, stop the local agent, or revoke it from the website. This tool only explains the process; it never creates pairing codes, discloses tokens, or revokes anything.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
    outputSchema: {
      type: "object",
      properties: {
        pairing: { type: "string" },
        projects: { type: "string" },
        revoke: { type: "string" }
      },
      required: ["pairing", "projects", "revoke"],
      additionalProperties: false
    },
    securitySchemes: oauth,
    annotations: annotations.readOnly
  }
]);

function asResult(data, text) {
  return { content: [{ type: "text", text }], structuredContent: data, isError: false };
}

export function authChallenge(base, error = "invalid_token") {
  const description = error === "insufficient_scope" ? "The required Mada Commander scope is missing." : "Sign in to use Mada Commander.";
  return {
    content: [{ type: "text", text: "Authentication required. Connect Mada Commander and retry." }],
    _meta: {
      "mcp/www_authenticate": [
        'Bearer resource_metadata="' + base + '/.well-known/oauth-protected-resource", error="' + error + '", error_description="' + description + '"'
      ]
    },
    isError: true
  };
}

export async function executePublicTool(name, args, principal) {
  if (!principal?.userId || !Array.isArray(principal.scope) || !principal.scope.includes(REQUIRED_SCOPE)) {
    return { content: [{ type: "text", text: "The access token is missing the required scope." }], isError: true };
  }

  switch (name) {
    case "mada_agent_status": {
      const r = await query(
        "SELECT name, last_seen_at FROM mada_public_agents WHERE user_id = $1 AND revoked_at IS NULL ORDER BY created_at DESC LIMIT 20",
        [principal.userId]
      );
      const agents = r.rows.map(a => ({
        name: a.name,
        connected: Boolean(a.last_seen_at && Date.now() - new Date(a.last_seen_at).getTime() <= 90_000)
      }));
      return asResult({ agents }, agents.length ? "Found " + agents.length + " paired agent(s)." : "No agent is paired to this account yet.");
    }
    case "mada_list_projects": {
      const r = await query(
        "SELECT p.project_id, p.label, p.branch, p.head, a.id AS agent_id, a.last_seen_at FROM mada_public_projects p JOIN mada_public_agents a ON a.id = p.agent_id WHERE a.user_id = $1 AND a.revoked_at IS NULL ORDER BY p.label LIMIT 100",
        [principal.userId]
      );
      const projects = r.rows.map(p => ({
        projectId: p.project_id,
        name: p.label,
        branch: p.branch || "",
        head: p.head || "",
        agentId: p.agent_id,
        agentConnected: Boolean(p.last_seen_at && Date.now() - new Date(p.last_seen_at).getTime() <= 90_000)
      }));
      return asResult({ projects }, projects.length ? "Found " + projects.length + " project(s)." : "No project is registered. Pair your own agent and add a project locally.");
    }
    case "mada_run_diagnostics": {
      if (!isSafeProjectAlias(args?.projectId)) {
        return { content: [{ type: "text", text: "Invalid project alias. Use an ID returned by mada_list_projects." }], isError: true };
      }
      const profile = String(args?.profile || "");
      if (!["git_status", "toolchain", "inventory"].includes(profile)) {
        return { content: [{ type: "text", text: "Only git_status, toolchain, or inventory diagnostics are supported." }], isError: true };
      }
      if (!/^[0-9a-f-]{36}$/i.test(String(args?.agentId || ""))) {
        return { content: [{ type: "text", text: "Invalid agent ID. Use the agentId returned with the project by mada_list_projects." }], isError: true };
      }
      const project = await query(
        "SELECT p.agent_id, p.project_id, p.label FROM mada_public_projects p JOIN mada_public_agents a ON a.id = p.agent_id WHERE a.user_id = $1 AND a.revoked_at IS NULL AND p.project_id = $2 AND a.id = $3 LIMIT 1",
        [principal.userId, args.projectId, args.agentId]
      );
      if (!project.rowCount) {
        return { content: [{ type: "text", text: "Project not found in this account. Use mada_list_projects to select one of your linked projects." }], isError: true };
      }
      const jobId = crypto.randomUUID();
      await query(
        "INSERT INTO mada_public_jobs (id, user_id, agent_id, project_id, profile, status) VALUES ($1,$2,$3,$4,$5,'queued')",
        [jobId, principal.userId, project.rows[0].agent_id, project.rows[0].project_id, profile]
      );
      const data = { jobId, status: "queued", profile, projectId: project.rows[0].project_id, projectName: project.rows[0].label };
      return asResult(data, "Diagnostic queued for " + project.rows[0].label + ". Use mada_job_status with jobId " + jobId + " to check progress.");
    }
    case "mada_job_status": {
      const jobId = String(args?.jobId || "");
      if (!/^[0-9a-f-]{36}$/i.test(jobId)) {
        return { content: [{ type: "text", text: "Invalid job ID." }], isError: true };
      }
      const r = await query(
        "SELECT j.id, j.project_id, p.label, j.profile, j.status, j.result, j.error_summary FROM mada_public_jobs j JOIN mada_public_projects p ON p.agent_id = j.agent_id AND p.project_id = j.project_id JOIN mada_public_agents a ON a.id = j.agent_id WHERE j.id = $1 AND j.user_id = $2 AND a.user_id = $2 LIMIT 1",
        [jobId, principal.userId]
      );
      if (!r.rowCount) {
        return { content: [{ type: "text", text: "Job not found." }], isError: true };
      }
      const j = r.rows[0];
      const data = {
        jobId: j.id, projectId: j.project_id, projectName: j.label, profile: j.profile, status: j.status,
        result: j.result ? safePublicResult(j.result) : null,
        error: j.error_summary ? redactSensitive(j.error_summary) : ""
      };
      return asResult(data, "Job status: " + data.status + (data.result ? ". A bounded result is attached." : "."));
    }
    case "mada_connection_help": {
      const data = {
        pairing: "Sign in to the Mada Commander website and create a short-lived one-time pairing code. Run the local agent's pair command on your own device; never share the code.",
        projects: "Register a project alias and absolute path in the local agent configuration. The absolute path stays on the device and is not uploaded by the agent heartbeat.",
        revoke: "Use the signed-in website to revoke a paired agent. The public service invalidates its token and cancels queued jobs; stop and remove the local config if you no longer use the agent."
      };
      return asResult(data, "Connect only a device and workspace that you own or are authorized to administer.");
    }
    default:
      return { content: [{ type: "text", text: "Unknown Mada Commander public tool." }], isError: true };
  }
}

function safePublicResult(value) {
  let result = value;
  if (typeof result === "string") {
    try { result = JSON.parse(result); } catch { result = redactSensitive(result); }
  }
  const json = redactSensitive(result);
  try { return JSON.parse(json); } catch { return json; }
}
